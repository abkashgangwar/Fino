package com.ember.storage;

import java.nio.file.Path;
import java.util.List;

/**
 * Abstraction over the input/output file transport used by the processing pipeline.
 * <p>
 * Backed by {@code SftpObjectService} (directory-based SFTP transport), which is the
 * only implementation on purpose - this keeps FileProcessingService decoupled from
 * the transport mechanism itself and makes it straightforward to mock in tests
 * (e.g. {@code @InjectMock FileStorageService}) without dragging in a real SFTP
 * connection.
 * <p>
 * All transfer methods are file-based (not byte[]-based) so that large payloads are
 * streamed straight to/from local disk instead of being held fully in JVM heap.
 * <p>
 * Idempotency at scale: the pipeline no longer checks the output location per file
 * (see {@link #moveToDone}) - that used to mean one extra round-trip per file, every
 * poll cycle, forever, since source files are never deleted. Instead, a file is moved
 * out of the top-level input listing the moment it's fully processed, so each poll's
 * {@link #listInputObjects} call only ever returns genuinely-pending work, no matter
 * how many files have been processed over the service's lifetime.
 */
public interface FileStorageService {

    /** Ensures the input/output locations (and the "done" sub-location) exist. Called once at startup. */
    void initialize() throws Exception;

    /**
     * Lists identifiers (object keys / filenames) currently sitting in the input location.
     * Non-recursive by design: files already relocated via {@link #moveToDone} live in a
     * sub-location and are therefore never returned here, so this list is always just the
     * current pending backlog - it doesn't grow unbounded as more files get processed.
     */
    List<String> listInputObjects() throws Exception;

    /** Streams the named input file straight to the given local destination path. */
    void downloadFromInput(String key, Path destination) throws Exception;

    /** Streams the given local file to the output location under the given name. */
    void uploadToProcessed(String key, Path source) throws Exception;

    /**
     * Relocates a fully-processed source file, in place, out of the top-level input
     * listing (e.g. into an input-side "done" sub-directory) - without deleting it.
     * <p>
     * This is the idempotency mechanism: called once, right after a file's encrypted
     * output has been uploaded successfully. It's a metadata-only rename (no data is
     * re-transferred), so it's cheap regardless of file size. Because it only happens
     * on the success path, a file that crashes mid-processing simply stays visible in
     * {@link #listInputObjects} and is naturally retried on the next poll - at worst
     * re-processing the one file that was in flight, never losing or duplicating one.
     */
    void moveToDone(String key) throws Exception;

    /**
     * Whether a file with this name already exists in the processed/output location.
     * No longer called by the main pipeline (see {@link #moveToDone} above) - kept on
     * the interface for tooling/manual reconciliation (e.g. an ops script sanity-checking
     * that every "done" source file really does have a corresponding output object).
     */
    boolean existsInProcessed(String key) throws Exception;

    /**
     * Removes the source file from the input location.
     * <p>
     * NOT called by the default processing pipeline - processed input files are moved
     * into "done" (see {@link #moveToDone}) rather than deleted, so they're retained for
     * audit/retry purposes. Kept on the interface for callers (e.g. an ops script or a
     * future retention job) that explicitly need to purge old "done" files after some
     * retention window.
     */
    void deleteFromInput(String key) throws Exception;
}
