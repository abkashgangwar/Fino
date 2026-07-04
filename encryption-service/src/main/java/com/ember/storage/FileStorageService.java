package com.ember.storage;

import java.util.List;

/**
 * Abstraction over the input/output file transport used by the processing pipeline.
 * <p>
 * Backed by {@code SftpObjectService} (directory-based SFTP transport), which is the
 * only implementation on purpose - this keeps FileProcessingService decoupled from
 * the transport mechanism itself and makes it straightforward to mock in tests
 * (e.g. {@code @InjectMock FileStorageService}) without dragging in a real SFTP
 * connection.
 */
public interface FileStorageService {

    /** Ensures the input/output locations exist (buckets or directories). Called once at startup. */
    void initialize() throws Exception;

    /** Lists identifiers (object keys / filenames) currently sitting in the input location. */
    List<String> listInputObjects() throws Exception;

    /** Downloads the named input file fully into memory. */
    byte[] downloadFromInput(String key) throws Exception;

    /** Uploads encrypted bytes to the output location under the given name. */
    void uploadToProcessed(String key, byte[] data) throws Exception;

    /** Removes the source file from the input location after it has been successfully processed. */
    void deleteFromInput(String key) throws Exception;
}
