/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.commons.io;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageInputStreamImpl;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Enumeration;
import java.util.zip.ZipEntry;

/**
 * Random-access {@link ImageInputStream} over a STORED (uncompressed) zip entry, reading the
 * entry's byte range of the outer zip file directly instead of streaming the entry.
 * <p>
 * Why this exists: ESA SAFE product zips store their large measurement GeoTIFFs UNCOMPRESSED,
 * and those TIFFs keep their IFD (image metadata) at the END of the file. Reading them through
 * a sequential zip {@code InputStream} wrapped in a Memory/FileCacheImageInputStream means the
 * TIFF reader's very first seek to the IFD pulls the ENTIRE entry (1.2-1.4 GB per Sentinel-1
 * IW SLC band) through the cache — measured at ~45 s per band access on data that the disk can
 * read at 210 MB/s, and ~106 s to open-and-verify one IW SLC zip in the reader tests. A STORED
 * entry is a contiguous byte range of the zip, so random access is possible and the IFD read
 * becomes a millisecond-scale tail read.
 * <p>
 * Entry offsets come from commons-compress ({@link ZipArchiveEntry#getDataOffset()}), which
 * handles ZIP64 correctly — SAFE zips exceed 4 GB, so hand-parsing local headers is not safe.
 * <p>
 * NOT thread-safe (same contract as every {@link ImageInputStreamImpl}); callers serialise
 * access through the owning ImageIO reader, as the existing cache-stream path already does.
 */
public final class StoredZipImageInputStream extends ImageInputStreamImpl {

    private final RandomAccessFile raf;
    private final long dataOffset;
    private final long dataLength;
    /** Absolute file position of the RAF, tracked to skip redundant seeks during the sequential
     *  reads that dominate strip/tile decode (-1 = unknown, force a seek). A seek() syscall on
     *  every read() made the multi-band full-open path measurably slower than the buffered
     *  streaming path it replaces. */
    private long rafPos = -1;

    private void seekTo(final long absPos) throws IOException {
        if (rafPos != absPos) {
            raf.seek(absPos);
            rafPos = absPos;
        }
    }

    /**
     * Create a random-access stream for {@code entryPath} inside {@code zipFile}, or return
     * {@code null} when the entry cannot be served this way (entry not found, DEFLATED, or
     * offset unavailable) — the caller then falls back to the buffered streaming path.
     * Lookup tries the exact entry name first, then a suffix match (VirtualDir-relative paths
     * may or may not carry the zip's root folder), then the bare file name (unique within a
     * SAFE product's measurement set).
     */
    public static ImageInputStream create(final File zipFile, final String entryPath) {
        if (zipFile == null || entryPath == null || !zipFile.isFile()) {
            return null;
        }
        final String normPath = entryPath.replace('\\', '/');
        try (ZipFile zf = ZipFile.builder().setFile(zipFile).get()) {
            ZipArchiveEntry entry = zf.getEntry(normPath);
            if (entry == null) {
                final String fileName = normPath.substring(normPath.lastIndexOf('/') + 1);
                for (final Enumeration<ZipArchiveEntry> en = zf.getEntries(); en.hasMoreElements(); ) {
                    final ZipArchiveEntry e = en.nextElement();
                    final String name = e.getName();
                    if (name.endsWith("/" + normPath) || name.equals(fileName) || name.endsWith("/" + fileName)) {
                        entry = e;
                        break;
                    }
                }
            }
            if (entry == null || entry.isDirectory() || entry.getMethod() != ZipEntry.STORED) {
                return null;
            }
            final long offset = entry.getDataOffset();
            final long size = entry.getSize();
            if (offset < 0 || size < 0) {
                return null;
            }
            return new StoredZipImageInputStream(zipFile, offset, size);
        } catch (IOException e) {
            return null;
        }
    }

    private StoredZipImageInputStream(final File zipFile, final long dataOffset, final long dataLength)
            throws IOException {
        this.raf = new RandomAccessFile(zipFile, "r");
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
    }

    @Override
    public int read() throws IOException {
        checkClosed();
        bitOffset = 0;
        if (streamPos >= dataLength) {
            return -1;
        }
        seekTo(dataOffset + streamPos);
        final int b = raf.read();
        if (b >= 0) {
            streamPos++;
            rafPos++;
        } else {
            rafPos = -1;
        }
        return b;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        checkClosed();
        bitOffset = 0;
        if (len == 0) {
            return 0;
        }
        if (streamPos >= dataLength) {
            return -1;
        }
        final int n = (int) Math.min(len, dataLength - streamPos);
        seekTo(dataOffset + streamPos);
        final int r = raf.read(b, off, n);
        if (r > 0) {
            streamPos += r;
            rafPos += r;
        } else {
            rafPos = -1;
        }
        return r;
    }

    @Override
    public long length() {
        return dataLength;
    }

    @Override
    public boolean isCached() {
        return false;
    }

    @Override
    public void close() throws IOException {
        super.close();
        raf.close();
    }
}
