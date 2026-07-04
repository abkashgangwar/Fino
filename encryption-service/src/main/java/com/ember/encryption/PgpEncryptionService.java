package com.ember.encryption;

import com.ember.config.PgpConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.CompressionAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.jcajce.JcaPGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.jboss.logging.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.util.Iterator;

/**
 * Encrypts arbitrary byte payloads using an OpenPGP public key.
 *
 * Settings match the SOP:
 *  - Symmetric cipher: AES-256
 *  - Compression: ZIP
 *  - Integrity check (Modification Detection Code / integrity packet): enabled
 */
@ApplicationScoped
public class PgpEncryptionService {

    private static final Logger LOG = Logger.getLogger(PgpEncryptionService.class);

    @Inject
    PgpConfig pgpConfig;

    private PGPPublicKey encryptionKey;

    @PostConstruct
    void init() {
        Security.addProvider(new BouncyCastleProvider());
        try (InputStream keyIn = loadPublicKeyStream()) {
            this.encryptionKey = readEncryptionKey(keyIn);
            LOG.infof("Loaded PGP public key, keyId=%X", encryptionKey.getKeyID());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load PGP public key for encryption", e);
        }
    }

    /**
     * Loads the public key from an external source only - never from the classpath/jar.
     * Priority: PGP_PUBLIC_KEY_PATH (mounted file) > PGP_PUBLIC_KEY_CONTENT (inline env/secret).
     * Fails fast with a clear error if neither is configured, instead of silently
     * falling back to some key baked into the build.
     */
    private InputStream loadPublicKeyStream() throws IOException {
        var explicitPath = pgpConfig.publicKey().path();
        if (explicitPath.isPresent() && !explicitPath.get().isBlank()) {
            LOG.infof("Loading PGP public key from filesystem path: %s", explicitPath.get());
            return new FileInputStream(explicitPath.get());
        }

        var inlineContent = pgpConfig.publicKey().content();
        if (inlineContent.isPresent() && !inlineContent.get().isBlank()) {
            LOG.info("Loading PGP public key from inline PGP_PUBLIC_KEY_CONTENT env var");
            return new ByteArrayInputStream(inlineContent.get().getBytes(StandardCharsets.UTF_8));
        }

        throw new FileNotFoundException(
                "No PGP public key configured. Set PGP_PUBLIC_KEY_PATH to a mounted key file, " +
                "or PGP_PUBLIC_KEY_CONTENT to the raw armored key. The key must never be committed " +
                "to source or bundled into the jar.");
    }

    /** Finds the first key in the ring that is flagged for encryption. */
    private PGPPublicKey readEncryptionKey(InputStream input) throws IOException, PGPException {
        InputStream decoded = org.bouncycastle.openpgp.PGPUtil.getDecoderStream(input);
        JcaPGPPublicKeyRingCollection keyRingCollection = new JcaPGPPublicKeyRingCollection(decoded);

        Iterator<PGPPublicKeyRing> ringIterator = keyRingCollection.getKeyRings();
        while (ringIterator.hasNext()) {
            PGPPublicKeyRing keyRing = ringIterator.next();
            Iterator<PGPPublicKey> keyIterator = keyRing.getPublicKeys();
            while (keyIterator.hasNext()) {
                PGPPublicKey key = keyIterator.next();
                if (key.isEncryptionKey()) {
                    return key;
                }
            }
        }
        throw new IllegalArgumentException("No encryption-capable key found in provided public key file");
    }

    /**
     * Encrypts the given plaintext bytes and returns a binary (non-armored) .pgp payload,
     * compressed with ZIP, encrypted with AES-256, with integrity protection enabled.
     *
     * @param plaintext the raw file content (e.g. CSV bytes)
     * @param fileNameHint the literal filename to embed inside the PGP literal data packet
     */
    public byte[] encrypt(byte[] plaintext, String fileNameHint) throws IOException, PGPException, NoSuchProviderException {
        ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream();

        // 1. Compress the plaintext (ZIP) into a literal data packet, in-memory.
        byte[] compressedLiteral = compressToLiteralData(plaintext, fileNameHint);

        // 2. Set up the encrypted data generator: AES-256, integrity packet enabled.
        JcePGPDataEncryptorBuilder encryptorBuilder =
                new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                        .setWithIntegrityPacket(true)
                        .setSecureRandom(new java.security.SecureRandom())
                        .setProvider("BC");

        PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(encryptorBuilder);
        encryptedDataGenerator.addMethod(
                new JcePublicKeyKeyEncryptionMethodGenerator(encryptionKey).setProvider("BC"));

        try (OutputStream cipherOut = encryptedDataGenerator.open(encryptedOut, compressedLiteral.length)) {
            cipherOut.write(compressedLiteral);
        }

        return encryptedOut.toByteArray();
    }

    /** Same as {@link #encrypt}, but wraps the result in ASCII armor (base64 text) instead of raw binary. */
    public byte[] encryptArmored(byte[] plaintext, String fileNameHint) throws IOException, PGPException, NoSuchProviderException {
        ByteArrayOutputStream armoredOut = new ByteArrayOutputStream();
        try (ArmoredOutputStream armored = new ArmoredOutputStream(armoredOut)) {
            byte[] binary = encrypt(plaintext, fileNameHint);
            armored.write(binary);
        }
        return armoredOut.toByteArray();
    }

    private byte[] compressToLiteralData(byte[] plaintext, String fileName) throws IOException {
        ByteArrayOutputStream compressedOut = new ByteArrayOutputStream();
        PGPCompressedDataGenerator compressedDataGenerator =
                new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);

        try (OutputStream compressorStream = compressedDataGenerator.open(compressedOut)) {
            PGPLiteralDataGenerator literalDataGenerator = new PGPLiteralDataGenerator();
            try (OutputStream literalOut = literalDataGenerator.open(
                    compressorStream,
                    PGPLiteralData.BINARY,
                    fileName,
                    plaintext.length,
                    java.util.Calendar.getInstance().getTime())) {
                literalOut.write(plaintext);
            }
        }
        return compressedOut.toByteArray();
    }
}
