package com.ember.compression;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Wraps a file's bytes into a standalone ZIP archive (a real .zip file,
 * distinct from the internal OpenPGP ZIP compression packet used inside
 * PgpEncryptionService).
 * <p>
 * Used so the pipeline produces: original -> .zip -> .zip.pgp
 * <p>
 * Exposes two overloads so the same service backs both pipeline modes
 * (toggled via {@code app.processing.streaming-enabled} in
 * {@code FileProcessingService}):
 * <ul>
 *   <li>{@link #zip(Path, Path, String)} - file-based: streams from a source file
 *       on disk to a destination file on disk. Used by the non-streaming pipeline,
 *       where a large file (e.g. a 100k-row CSV) is staged through local temp files.</li>
 *   <li>{@link #zip(InputStream, OutputStream, String)} - pure stream-to-stream, no
 *       source/destination files, nothing buffered fully in memory. Used by the
 *       streaming pipeline, where {@code source} is typically the live SFTP download
 *       stream and {@code destination} is typically the PGP layer's literal-data
 *       stream sitting right above this one.</li>
 * </ul>
 */
@ApplicationScoped
public class ZipCompressionService {

    /**
     * Compresses the given source file into a ZIP archive containing a single entry,
     * writing the result to destZip.
     *
     * @param source    the raw file content to compress (e.g. a downloaded CSV)
     * @param destZip   where to write the resulting .zip archive
     * @param entryName the name to give the file *inside* the zip (e.g. "testing.csv")
     */
    public void zip(Path source, Path destZip, String entryName) throws IOException {
        try (InputStream in = Files.newInputStream(source);
             OutputStream fileOut = Files.newOutputStream(destZip);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {
            zipOut.putNextEntry(new ZipEntry(entryName));
            in.transferTo(zipOut);
            zipOut.closeEntry();
        }
    }

    /**
     * Compresses bytes read from {@code source} into a ZIP archive containing a single
     * entry, streaming the result straight into {@code destination} as it's produced.
     * <p>
     * Only {@link ZipOutputStream#finish()} is called here, never {@code close()} -
     * finish() flushes the deflater and writes the ZIP central directory without
     * touching {@code destination}'s lifecycle, whereas close() would additionally close
     * {@code destination}. That matters when {@code destination} is e.g. the PGP
     * literal-data stream, which still needs its own close() (owned by the caller) to
     * finalize the surrounding PGP packet once these zip bytes are done.
     *
     * @param source      the raw file content to compress (e.g. a live SFTP download stream)
     * @param destination where to stream the resulting ZIP archive bytes (left open on return)
     * @param entryName   the name to give the file *inside* the zip (e.g. "testing.csv")
     */
    public void zip(InputStream source, OutputStream destination, String entryName) throws IOException {
        ZipOutputStream zipOut = new ZipOutputStream(destination);
        zipOut.putNextEntry(new ZipEntry(entryName));
        source.transferTo(zipOut);
        zipOut.closeEntry();
        zipOut.finish(); // finalize the zip; deliberately leaves `destination` open
    }
}
