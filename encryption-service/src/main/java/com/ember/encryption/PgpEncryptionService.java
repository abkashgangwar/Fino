package com.ember.encryption;

import com.ember.config.PgpConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.Calendar;
import java.util.Iterator;

/**
 * Encrypts file payloads using an OpenPGP public key.
 *
 * Settings match the SOP:
 *  - Symmetric cipher: AES-256
 *  - Compression: ZIP
 *  - Integrity check (Modification Detection Code / integrity packet): enabled
 * <p>
 * Exposes two overloads so the same service backs both pipeline modes (toggled via
 * {@code app.processing.streaming-enabled} in {@code FileProcessingService}):
 * <ul>
 *   <li>{@link #encrypt(Path, Path, String)} - file-based: streams source file ->
 *       literal data packet -> ZIP compression -> AES-256 encryption -> destination
 *       file. Used by the non-streaming pipeline.</li>
 *   <li>{@link #encrypt(OutputStream, String, PlaintextWriter)} - fully streamed: the
 *       plaintext itself is supplied by a caller-provided callback instead of a source
 *       file, so nothing here ever touches local disk. Used by the streaming pipeline.</li>
 * </ul>
 * Neither overload holds the whole payload in a byte[] at any point - both stream in
 * fixed-size chunks throughout, which is what lets large files (100k-row CSVs, at
 * volume) avoid blowing up heap usage under load.
 */
@ApplicationScoped
public class PgpEncryptionService {

    private static final Logger LOG = Logger.getLogger(PgpEncryptionService.class);
    private static final int STREAM_BUFFER_SIZE = 1 << 16; // 64KB

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
     * Encrypts the given source file and writes a binary (non-armored) .pgp payload
     * to dest, compressed with ZIP, encrypted with AES-256, with integrity protection
     * enabled. Streams throughout - source file size can be arbitrarily large without
     * increasing heap usage. Used by the file-based (non-streaming) pipeline.
     *
     * @param source       the file to encrypt (e.g. the intermediate .zip archive)
     * @param dest         where to write the resulting .pgp payload
     * @param fileNameHint the literal filename to embed inside the PGP literal data packet
     */
    public void encrypt(Path source, Path dest, String fileNameHint) throws IOException, PGPException {
        long sourceLength = Files.size(source);

        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(dest), STREAM_BUFFER_SIZE)) {
            JcePGPDataEncryptorBuilder encryptorBuilder =
                    new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                            .setWithIntegrityPacket(true)
                            .setSecureRandom(new java.security.SecureRandom())
                            .setProvider("BC");

            PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(encryptorBuilder);
            encryptedDataGenerator.addMethod(
                    new JcePublicKeyKeyEncryptionMethodGenerator(encryptionKey).setProvider("BC"));

            // Buffered/partial-length overload: we don't know the final compressed size
            // ahead of time when streaming, so this writes partial-length packets instead
            // of requiring a pre-known total length.
            try (OutputStream cipherOut = encryptedDataGenerator.open(fileOut, new byte[STREAM_BUFFER_SIZE])) {
                PGPCompressedDataGenerator compressedDataGenerator =
                        new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
                try (OutputStream compressorStream = compressedDataGenerator.open(cipherOut, new byte[STREAM_BUFFER_SIZE])) {
                    PGPLiteralDataGenerator literalDataGenerator = new PGPLiteralDataGenerator();
                    try (OutputStream literalOut = literalDataGenerator.open(
                            compressorStream,
                            PGPLiteralData.BINARY,
                            fileNameHint,
                            sourceLength,
                            Calendar.getInstance().getTime());
                         InputStream sourceIn = new BufferedInputStream(Files.newInputStream(source), STREAM_BUFFER_SIZE)) {
                        sourceIn.transferTo(literalOut);
                    }
                }
            }
        }
    }

    /** Supplies the plaintext bytes that go inside the PGP literal data packet. */
    @FunctionalInterface
    public interface PlaintextWriter {
        void writeTo(OutputStream literalDataOut) throws IOException;
    }

    /**
     * Streams a binary (non-armored) .pgp payload straight into {@code destination},
     * compressed with ZIP, encrypted with AES-256, with integrity protection enabled -
     * calling {@code plaintextWriter} to supply the content that goes inside the PGP
     * literal data packet (typically the zip step, writing straight into this method's
     * innermost stream). Nothing here is staged to local disk or buffered fully in
     * memory - only fixed-size chunks (STREAM_BUFFER_SIZE) move through at a time,
     * however large the plaintext turns out to be. Used by the streaming pipeline.
     * <p>
     * Because there's no intermediate .zip file to call Files.size() on ahead of time,
     * all three layers here - encryption, compression, and the literal data packet
     * itself - use their buffered/partial-length open() overloads instead of a
     * length-prefixed one, so none of them need to know the final size upfront.
     * <p>
     * {@code destination} is flushed but deliberately left open on return - the caller
     * (the SFTP upload stream) owns closing the actual transport once this returns, so
     * the remote file is only finalized after this method's framing is fully written.
     *
     * @param destination     where the encrypted PGP payload is streamed to (e.g. an SFTP upload stream)
     * @param fileNameHint    the literal filename to embed inside the PGP literal data packet
     * @param plaintextWriter callback that writes the plaintext (e.g. zipped bytes) into the pipeline
     */
    public void encrypt(OutputStream destination, String fileNameHint, PlaintextWriter plaintextWriter)
            throws IOException, PGPException {
        OutputStream fileOut = new BufferedOutputStream(destination, STREAM_BUFFER_SIZE);

        JcePGPDataEncryptorBuilder encryptorBuilder =
                new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                        .setWithIntegrityPacket(true)
                        .setSecureRandom(new java.security.SecureRandom())
                        .setProvider("BC");

        PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(encryptorBuilder);
        encryptedDataGenerator.addMethod(
                new JcePublicKeyKeyEncryptionMethodGenerator(encryptionKey).setProvider("BC"));

        // Buffered/partial-length overload: we don't know the final compressed size
        // ahead of time when streaming - this writes partial-length packets instead
        // of requiring a pre-known total length.
        //
        // IMPORTANT: each of these three open() calls gets its OWN fresh byte[], never
        // a shared one. Each layer uses its buffer internally to accumulate a partial
        // packet before flushing it to the stream below - since all three layers are
        // nested and active at once here, sharing one array between them would mean
        // one layer's in-progress accumulation gets clobbered by another layer writing
        // through the same memory. Three small arrays is a non-issue memory-wise; a
        // shared one would be a real (and hard to notice) data-corruption bug.
        try (OutputStream cipherOut = encryptedDataGenerator.open(fileOut, new byte[STREAM_BUFFER_SIZE])) {
            PGPCompressedDataGenerator compressedDataGenerator =
                    new PGPCompressedDataGenerator(CompressionAlgorithmTags.ZIP);
            try (OutputStream compressorStream = compressedDataGenerator.open(cipherOut, new byte[STREAM_BUFFER_SIZE])) {
                PGPLiteralDataGenerator literalDataGenerator = new PGPLiteralDataGenerator();
                // Buffered overload (out, format, name, modTime, buffer) - unlike the
                // file-based version, the plaintext length isn't known ahead of time here.
                try (OutputStream literalOut = literalDataGenerator.open(
                        compressorStream,
                        PGPLiteralData.BINARY,
                        fileNameHint,
                        Calendar.getInstance().getTime(),
                        new byte[STREAM_BUFFER_SIZE])) {
                    plaintextWriter.writeTo(literalOut);
                }
            }
        }
        fileOut.flush();
    }
}
