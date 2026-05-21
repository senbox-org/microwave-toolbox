/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.io.binary;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A reader for reading binary files.
 * <p>
 * Not thread-safe: holds a reusable scratch byte buffer and the underlying
 * {@link ImageInputStream}'s position. Construct one reader per logical read pass.
 */
public final class BinaryFileReader {

    private static final String EM_EXPECTED_X_FOUND_Y_BYTES = "Expected bytes to read %d, but only found %d";
    private static final String EM_READING_X_TYPE = "Reading '%s'-Type";
    private static final String EM_NOT_PARSABLE_X_STRING = "Not able to parse %s string";

    private final ImageInputStream stream;

    /**
     * Reusable scratch buffer for small fixed-width reads (An/In/Fn/En). The CEOS record
     * format is field-oriented: hundreds of small reads per record. Allocating a fresh
     * {@code byte[]} per field caused noticeable GC pressure on metadata-heavy product
     * opens. We grow this buffer on demand and reuse it for every read.
     */
    private byte[] scratch = new byte[256];

    public BinaryFileReader(final ImageInputStream stream) {
        this.stream = stream;
    }

    public void close() throws IOException {
        stream.close();
    }

    public void setByteOrder(ByteOrder order) {
        stream.setByteOrder(order);
    }

    public void seek(final long pos) throws IOException {
        stream.seek(pos);
    }

    public void skipBytes(final long numBytes) throws IOException {
        stream.skipBytes(numBytes);
    }

    public int readB1() throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        try {
            return stream.readByte() & 0xFF;
        } catch (IOException e) {
            final String message = String.format(EM_READING_X_TYPE, "B1");
            throw new IllegalBinaryFormatException(message, streamPosition, e);
        }
    }

    public int readUB1() throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        try {
            return stream.readUnsignedByte();
        } catch (IOException e) {
            final String message = String.format(EM_READING_X_TYPE, "B2");
            throw new IllegalBinaryFormatException(message, streamPosition, e);
        }
    }

    public short readB2() throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        try {
            return stream.readShort();
        } catch (IOException e) {
            final String message = String.format(EM_READING_X_TYPE, "B2");
            throw new IllegalBinaryFormatException(message, streamPosition, e);
        }
    }

    public int readUB2() throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        try {
            return stream.readUnsignedShort();
        } catch (IOException e) {
            final String message = String.format(EM_READING_X_TYPE, "B2");
            throw new IllegalBinaryFormatException(message, streamPosition, e);
        }
    }

    public int readB4() throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        try {
            return stream.readInt();
        } catch (IOException e) {
            final String message = String.format(EM_READING_X_TYPE, "B4");
            throw new IllegalBinaryFormatException(message, streamPosition, e);
        }
    }

    public long readB8() throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        try {
            return stream.readLong();
        } catch (IOException e) {
            final String message = String.format(EM_READING_X_TYPE, "B8");
            throw new IllegalBinaryFormatException(message, streamPosition, e);
        }
    }

    public void read(final byte[] array) throws IOException {
        stream.readFully(array, 0, array.length);
    }

    public void read(final char[] array) throws IOException {
        stream.readFully(array, 0, array.length);
    }

    public void read(final short[] array) throws IOException {
        stream.readFully(array, 0, array.length);
    }

    public void read(final int[] array) throws IOException {
        stream.readFully(array, 0, array.length);
    }

    public void read(final long[] array) throws IOException {
        stream.readFully(array, 0, array.length);
    }

    public void read(final float[] array) throws IOException {
        stream.readFully(array, 0, array.length);
    }

    public void read(final double[] array) throws IOException {
        stream.readFully(array, 0, array.length);
    }

    public long readIn(final int n) throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        final String longStr = readAn(n).trim();
        if (longStr.isEmpty()) return 0;
        return parseLong(longStr, streamPosition);
    }

    private static long parseLong(String integerStr, long streamPosition) throws IllegalBinaryFormatException {
        long number;
        try {
            number = Long.parseLong(integerStr);
        } catch (NumberFormatException e) {

            final String newStr = createIntegerString(integerStr,
                    new char[]{'.', '-'}, ' ').trim();
            try {
                if (newStr.isEmpty() || newStr.equals(".") || newStr.equals("-")) return 0;
                number = Long.parseLong(newStr);
            } catch (NumberFormatException e2) {
                final String message = String.format(EM_NOT_PARSABLE_X_STRING + " \"" + integerStr + '"',
                        "integer");
                throw new IllegalBinaryFormatException(message, streamPosition, e);
            }
        }
        return number;
    }

    public double readFn(final int n) throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        String doubleString = readAn(n).trim();
        if (doubleString.isEmpty()) return 0;
        // Fortran-style "D" exponent → "E". Use the char form (no regex compile per call).
        if (doubleString.indexOf('D') >= 0) {
            doubleString = doubleString.replace('D', 'E');
        }
        try {
            return Double.parseDouble(doubleString);
        } catch (NumberFormatException e) {
            final String message = String.format(EM_NOT_PARSABLE_X_STRING, "double");
            throw new IllegalBinaryFormatException(message, streamPosition, e);
        }
    }

    public void readFn(final int n, final double[] numbers) throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        for (int i = 0; i < numbers.length; i++) {
            try {
                numbers[i] = Double.parseDouble(readAn(n).trim());
            } catch (IllegalBinaryFormatException e) {
                final String message = String.format(EM_READING_X_TYPE, "Gn[]");
                throw new IllegalBinaryFormatException(message, streamPosition, e);
            }
        }
    }

    /**
     * Read an "En" (engineering-notation ASCII) field of width {@code n} and return its
     * value as a double. Previously this method also did a buggy double-interpretation
     * of the bytes as IEEE-754 binary; that path is gone — CEOS {@code En} fields are
     * always ASCII strings of the form {@code "1.234E+05"} or {@code "1.234D+05"}.
     */
    public double readEn(final int n) throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        String s = readAn(n).trim();
        if (s.isEmpty()) return 0;
        if (s.indexOf('D') >= 0) {
            s = s.replace('D', 'E');
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            final String message = String.format(EM_NOT_PARSABLE_X_STRING, "En double");
            throw new IllegalBinaryFormatException(message, streamPosition, e);
        }
    }

    public String readAn(final int n) throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        final byte[] buf = scratchOfAtLeast(n);
        final int bytesRead;
        try {
            bytesRead = stream.read(buf, 0, n);
        } catch (IOException e) {
            final String message = String.format(EM_READING_X_TYPE, "An");
            throw new IllegalBinaryFormatException(message, streamPosition, e);
        }
        if (bytesRead != n) {
            final String message = String.format(EM_EXPECTED_X_FOUND_Y_BYTES, n, bytesRead);
            throw new IllegalBinaryFormatException(message, streamPosition);
        }
        // Fold null-byte sanitisation (CEOS pads short fields with NUL in some records)
        // into the byte loop so we only allocate one String. US_ASCII decode is both
        // faster than the platform default and correct for the CEOS character set.
        for (int i = 0; i < n; i++) {
            if (buf[i] == 0) buf[i] = ' ';
        }
        return new String(buf, 0, n, StandardCharsets.US_ASCII);
    }

    public int[] readInArray(final int arraySize, final int intValLength)
            throws IOException, IllegalBinaryFormatException {
        final long streamPosition = stream.getStreamPosition();
        final int[] ints = new int[arraySize];
        for (int i = 0; i < ints.length; i++) {
            final String integerString = readAn(intValLength).trim();
            if (integerString.length() > 0) {
                ints[i] = (int) parseLong(integerString, streamPosition + i * intValLength);
            }
        }
        return ints;
    }

    public long getCurrentPos() throws IOException {
        return stream.getStreamPosition();
    }

    public long getLength() throws IOException {
        return stream.length();
    }

    /**
     * Return the shared scratch buffer, growing it if necessary so it can hold at least
     * {@code n} bytes. The buffer is reused across reads on the same {@code BinaryFileReader}
     * instance — caller must consume the bytes before the next read.
     */
    private byte[] scratchOfAtLeast(final int n) {
        if (scratch.length < n) {
            // Double until it fits, but at most one allocation per growth.
            int newLen = scratch.length;
            while (newLen < n) newLen <<= 1;
            scratch = new byte[newLen];
        }
        return scratch;
    }

    private static String createIntegerString(String name, char[] validChars, char replaceChar) {
        char[] sortedValidChars;
        if (validChars == null) {
            sortedValidChars = new char[0];
        } else {
            sortedValidChars = validChars.clone();
        }
        Arrays.sort(sortedValidChars);
        StringBuilder validName = new StringBuilder(name.length());
        boolean pad = false;
        for (int i = 0; i < name.length(); i++) {
            final char ch = name.charAt(i);
            if (!pad && Character.isDigit(ch)) {
                validName.append(ch);
            } else if (!pad && Arrays.binarySearch(sortedValidChars, ch) >= 0) {
                validName.append(ch);
            } else {
                pad = true;
                validName.append(replaceChar);
            }
        }
        return validName.toString();
    }
}
