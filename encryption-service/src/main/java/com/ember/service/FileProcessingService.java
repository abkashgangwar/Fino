package com.ember.service;

import com.ember.compression.ZipCompressionService;
import com.ember.encryption.PgpEncryptionService;
import com.ember.storage.FileStorageService;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class FileProcessingService {

    private static final Logger LOG = Logger.getLogger(FileProcessingService.class);
    private static final String ZIP_EXTENSION = ".zip";
    private static final String PGP_EXTENSION = ".pgp";

    @Inject
    FileStorageService fileStorageService;

    @Inject
    ZipCompressionService zipCompressionService;

    @Inject
    PgpEncryptionService pgpEncryptionService;

    void onStart(@Observes StartupEvent event) {
        try {
            fileStorageService.initialize();
            LOG.info("Encryption service started. Watching input location...");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize storage backend on startup", e);
        }
    }

    /**
     * Polls the input location on a cron schedule (configurable via app.poll.cron).
     * For each file found: download -> ZIP-compress -> PGP-encrypt (AES-256, integrity packet)
     * -> upload as <original-name>.zip.pgp to the processed location -> delete source.
     */
    @Scheduled(cron = "{app.poll.cron}")
    void pollAndProcess() {
        List<String> objectKeys;
        try {
            objectKeys = fileStorageService.listInputObjects();
        } catch (Exception e) {
            // Listing failure (e.g. SFTP server unreachable) - log and retry next cycle.
            LOG.error("Failed to list input objects; will retry on the next poll.", e);
            return;
        }

        if (objectKeys.isEmpty()) {
            return;
        }

        LOG.infof("Found %d object(s) in input location to process", objectKeys.size());
        for (String key : objectKeys) {
            try {
                processOne(key);
            } catch (Exception e) {
                // Log and continue with the next file - one bad file shouldn't block the batch.
                // The source file is deliberately NOT deleted here, so it's left in place for retry
                // whether the failure happened during encryption or during the upload step.
                LOG.errorf(e, "Failed to process object '%s'. Leaving it in the input location for retry.", key);
            }
        }
    }

    private void processOne(String objectKey) throws Exception {
        LOG.infof("Processing '%s'...", objectKey);

        byte[] plaintext = fileStorageService.downloadFromInput(objectKey);

        // Use only the base filename (not the full key/path) as the name *inside*
        // the zip/PGP payload. If objectKey has a folder prefix (e.g. "data/testing.csv"),
        // using the full key here would make ZipOutputStream create a real "data/" folder
        // inside the archive instead of a flat file - which is why unzip was producing
        // a folder containing the file instead of the file itself.
        String baseFileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);

        // Step 1: wrap the raw file into a standalone .zip archive.
        String zipFileName = objectKey + ZIP_EXTENSION; // e.g. testing.csv -> testing.csv.zip
        byte[] zipped = zipCompressionService.zip(plaintext, baseFileName);

        // Step 2: PGP-encrypt the zip archive itself. Timed so encryption cost is visible
        // in the logs without needing a separate benchmark harness.
        long startNanos = System.nanoTime();
        byte[] encrypted = pgpEncryptionService.encrypt(zipped, baseFileName + ZIP_EXTENSION);
        long encryptMs = (System.nanoTime() - startNanos) / 1_000_000;
        LOG.infof("Encrypted '%s' (%d bytes -> %d bytes) in %d ms", baseFileName, zipped.length, encrypted.length, encryptMs);

        String outputKey = zipFileName + PGP_EXTENSION; // e.g. testing.csv.zip -> testing.csv.zip.pgp

        // If this upload throws, we fall out of processOne() before deleteFromInput() runs below -
        // so the source file stays put in the input location and gets retried next poll.
        fileStorageService.uploadToProcessed(outputKey, encrypted);

        fileStorageService.deleteFromInput(objectKey);

        LOG.infof("Encrypted '%s' -> '%s' and uploaded to processed location.", objectKey, outputKey);
    }
}
