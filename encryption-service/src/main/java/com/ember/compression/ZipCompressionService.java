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
 *
 * Used so the pipeline produces: original -> .zip -> .zip.pgp
 * <p>
 * Streams straight from a source file on disk to a destination file on disk
 * (InputStream#transferTo copies in fixed-size chunks internally) instead of
 * buffering the whole file in a byte[]/ByteArrayOutputStream - the old approach
 * meant a 100k-row CSV was held fully in heap just to be zipped.
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
}
