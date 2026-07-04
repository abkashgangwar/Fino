package com.ember.compression;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Wraps a file's bytes into a standalone ZIP archive (a real .zip file,
 * distinct from the internal OpenPGP ZIP compression packet used inside
 * PgpEncryptionService).
 *
 * Used so the pipeline produces: original -> .zip -> .zip.pgp
 */
@ApplicationScoped
public class ZipCompressionService {

    /**
     * Compresses the given bytes into a ZIP archive containing a single entry.
     *
     * @param data      the raw file content (e.g. CSV bytes)
     * @param entryName the name to give the file *inside* the zip (e.g. "testing.csv")
     * @return the bytes of the resulting .zip archive
     */
    public byte[] zip(byte[] data, String entryName) throws IOException {
        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        try (ZipOutputStream zipOut = new ZipOutputStream(byteOut)) {
            ZipEntry entry = new ZipEntry(entryName);
            zipOut.putNextEntry(entry);
            zipOut.write(data);
            zipOut.closeEntry();
        }
        return byteOut.toByteArray();
    }
}
