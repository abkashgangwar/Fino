package com.ember.service;

import com.ember.compression.ZipCompressionService;
import com.ember.encryption.PgpEncryptionService;
import com.ember.storage.FileStorageService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

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

    /**
     * How many files are processed concurrently per poll cycle. Keep this <=
     * sftp.pool-size so every worker can always get its own SFTP connection.
     */
    @ConfigProperty(name = "app.processing.parallelism", defaultValue = "8")
    int parallelism;

    private ExecutorService workerPool;
    private Path tempDir;

    void onStart(@Observes StartupEvent event) {
        try {
            fileStorageService.initialize();
            workerPool = Executors.newFixedThreadPool(Math.max(1, parallelism));
            tempDir = Files.createTempDirectory("ember-encryption-work-");
            LOG.infof("Encryption service started (parallelism=%d). Watching input location...", parallelism);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize storage backend on startup", e);
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        if (workerPool != null) {
            workerPool.shutdown();
        }
    }

    /**
     * Polls the input location on a cron schedule (configurable via app.poll.cron).
     * For each file found: download -> ZIP-compress -> PGP-encrypt (AES-256, integrity packet)
     * -> upload as <original-name>.zip.pgp to the processed location -> move the source
     * into an input-side "done" sub-location.
     * <p>
     * The source file is never deleted, only relocated once fully processed -
     * {@link FileStorageService#moveToDone} is what stops a file from being re-processed
     * on every future poll. This replaced an older approach that called
     * {@link FileStorageService#existsInProcessed} (an SFTP round-trip) for every single
     * input file on every poll cycle - harmless at low volume, but at 100,000+ files that
     * added minutes of pure "is this done yet?" checking before any real work even started,
     * every cycle, forever, since the backlog of already-processed files only ever grew.
     * Moving a done file out of {@link FileStorageService#listInputObjects}'s listing means
     * that listing only ever contains genuinely-pending work, so there's nothing left to
     * check per file at all.
     * <p>
     * concurrentExecution = SKIP is critical here and was missing before: without it,
     * Quarkus defaults to letting overlapping cron ticks run at the same time. A poll
     * cycle over a large backlog (e.g. 2000 files) can easily run longer than the cron
     * interval, so the next tick would start a second overlapping pollAndProcess() call.
     * That raced on the same SFTP connection pool and shared state - which is what was
     * causing the service to fall over under load. SKIP guarantees the next poll only
     * starts once the current one (including all its parallel workers below) is done.
     */
    @Scheduled(cron = "{app.poll.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
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

        LOG.infof("Found %d object(s) in input location to process (parallelism=%d)", objectKeys.size(), parallelism);

        List<Future<?>> futures = new ArrayList<>(objectKeys.size());
        for (String key : objectKeys) {
            futures.add(workerPool.submit(() -> {
                try {
                    processOne(key);
                } catch (Exception e) {
                    // Log and continue with the next file - one bad file shouldn't block the batch.
                    // The source file is never deleted, so it's simply retried next poll cycle
                    // whether the failure happened during encryption or during the upload step.
                    //
                    // Deliberately no in-cycle retry/backoff here: at this scale, a systemic issue
                    // (e.g. the SFTP server going unreachable) would hit many files at once, and
                    // retrying each one individually (with its own connection-timeout wait, doubled
                    // or tripled) would multiply how long the *whole* batch takes to fail through -
                    // which, under concurrentExecution = SKIP, directly delays how soon the next poll
                    // cycle (a cheaper, natural retry) can even start. Failing fast and letting the
                    // next cycle handle it is the safer default for a backlog this large.
                    LOG.errorf(e, "Failed to process object '%s'. It remains in the input location for retry.", key);
                }
            }));
        }

        // Block until this whole batch (across all worker threads) finishes before returning,
        // so the SKIP policy above has a well-defined "current run" to skip around.
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting for batch to complete.");
                break;
            } catch (ExecutionException ee) {
                // Individual failures are already logged inside the task itself.
            }
        }
    }

    private void processOne(String objectKey) throws Exception {
        String baseFileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        String outputKey = objectKey + ZIP_EXTENSION + PGP_EXTENSION; // e.g. testing.csv -> testing.csv.zip.pgp

        // No per-file "is this already done?" check anymore - listInputObjects() only ever
        // returns files that haven't been moved to "done" yet, so reaching this point at all
        // already means the file is genuinely pending. See moveToDone() below.
        LOG.infof("Processing '%s'...", objectKey);

        // Stage everything through local temp files instead of byte[] buffers, so a
        // large file (e.g. 100k CSV rows) never needs to sit fully in JVM heap. Each
        // stage streams from the previous stage's file straight to the next one.
        Path rawFile = Files.createTempFile(tempDir, "raw-", "-" + safeSuffix(baseFileName));
        Path zipFile = Files.createTempFile(tempDir, "zip-", ".zip");
        Path encFile = Files.createTempFile(tempDir, "enc-", ".pgp");
        try {
            fileStorageService.downloadFromInput(objectKey, rawFile);

            zipCompressionService.zip(rawFile, zipFile, baseFileName);

            long startNanos = System.nanoTime();
            pgpEncryptionService.encrypt(zipFile, encFile, baseFileName + ZIP_EXTENSION);
            long encryptMs = (System.nanoTime() - startNanos) / 1_000_000;
            LOG.infof("Encrypted '%s' (%d bytes -> %d bytes) in %d ms",
                    baseFileName, Files.size(zipFile), Files.size(encFile), encryptMs);

            fileStorageService.uploadToProcessed(outputKey, encFile);

            // Only now, after the encrypted output is safely uploaded, relocate the source
            // out of the pending listing. If anything above fails or the process dies first,
            // this line never runs, the source stays exactly where listInputObjects() will
            // find it again, and the file is simply retried next cycle - never lost, never
            // silently skipped.
            fileStorageService.moveToDone(objectKey);

            LOG.infof("Encrypted '%s' -> '%s' and uploaded to processed location. Source moved to done/.",
                    objectKey, outputKey);
        } finally {
            // These are transient scratch files, not the input source file - always clean up,
            // success or failure, so a busy poll cycle doesn't fill up local disk.
            deleteQuietly(rawFile);
            deleteQuietly(zipFile);
            deleteQuietly(encFile);
        }
    }

    private static String safeSuffix(String fileName) {
        // Keep temp-file names short and filesystem-safe regardless of the source name.
        String cleaned = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return cleaned.length() > 50 ? cleaned.substring(cleaned.length() - 50) : cleaned;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to delete temp file '%s' (non-fatal).", path);
        }
    }
}
