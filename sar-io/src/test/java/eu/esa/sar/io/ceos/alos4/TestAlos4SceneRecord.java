/*
 * Copyright (C) 2026 by Mahmut Arikan
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
package eu.esa.sar.io.ceos.alos4;

import eu.esa.sar.io.binary.BinaryDBReader;
import eu.esa.sar.io.binary.BinaryFileReader;
import eu.esa.sar.io.binary.BinaryRecord;
import org.jdom2.Document;
import org.junit.Test;

import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Reads an ALOS-4 PALSAR-3 data set summary record with the ALOS-4 definition, without any data
 * file.
 * <p>
 * The record is 4680 bytes (the ALOS-2 one is 4096): PALSAR-3 stores each of the four Doppler blocks
 * as four 3 x F16.7 groups, one per ScanSAR scan, where PALSAR-2 stores one (JAXA FTR-240031A
 * Rev. A, Table 4.6-5). With the PALSAR-2 layout every field from byte 1462 is misplaced, and
 * {@link BinaryFileReader} reads a blank numeric field as 0 without a message, so the product
 * comes out with zero pixel spacings, zero incidence and the wrong pass, and the read still
 * succeeds.
 * <p>
 * The values below were read from a real PALSAR-3 L1.1 record (SM2 quad-pol, 2025-09-23,
 * descending) and are typed out here, together with the 0-based byte offsets the specification
 * gives them, rather than derived from the definition file under test.
 */
public class TestAlos4SceneRecord {

    private static final int RECORD_LENGTH = 4680;
    private static final String DEFINITION = "scene_record.xml";

    /** Fields of the observed record: 0-based byte offset, then the bytes as written. */
    private static final Object[][] OBSERVED = {
            {20, "ALOS40862510250923HBQPRD0809    "},   // scene identifier
            {444, "    6644"},                           // orbit number
            {500, "       0.2424443"},                   // radar wavelength [m]
            {710, "      49.1210934"},                   // range sampling rate [MHz]
            {1414, "       0.8326155"},                  // along-track Doppler centroid, scan 1
            {1822, "DESCEND "},                          // time direction along line
            {2282, "       2.9272011"},                  // line spacing (azimuth) [m]
            {2298, "       3.0515654"},                  // pixel spacing (range) [m]
            {2434, "      37.9073760"},                  // nominal off-nadir angle [deg]
            {2482, "-2.5130331060826E+00"},              // incidence angle polynomial, a0
            {2502, " 2.9976145625357E-03"},              // a1
            {2522, " 5.9127141806338E-06"},              // a2
            {2542, "-3.6096574867289E-09"},              // a3
            {2562, "-6.5751018580496E-12"},              // a4
            {2582, " 4.7445207923820E-15"},              // a5
    };

    private static BinaryRecord readRecord() throws IOException {
        final byte[] record = new byte[RECORD_LENGTH];
        Arrays.fill(record, (byte) ' ');
        // record number 2, subtypes 18 / type 10 / 18 / 20, record length 4680
        final byte[] header = {0, 0, 0, 2, 18, 10, 18, 20, 0, 0, 18, 72};
        System.arraycopy(header, 0, record, 0, header.length);
        for (Object[] field : OBSERVED) {
            final byte[] text = ((String) field[1]).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(text, 0, record, (Integer) field[0], text.length);
        }
        final Document definition = BinaryDBReader.loadDefinitionFile("alos4", DEFINITION);
        final BinaryFileReader reader = new BinaryFileReader(
                new MemoryCacheImageInputStream(new ByteArrayInputStream(record)));
        return new BinaryRecord(reader, 0, definition, DEFINITION);
    }

    @Test
    public void testRecordLengthIs4680() throws Exception {
        assertEquals(RECORD_LENGTH, readRecord().getRecordLength());
    }

    @Test
    public void testSpacingsAreReadFromTheirSpecifiedPositions() throws Exception {
        final BinaryRecord scene = readRecord();
        assertEquals(3.0515654, scene.getAttributeDouble("Pixel spacing"), 1e-9);
        assertEquals(2.9272011, scene.getAttributeDouble("Line spacing"), 1e-9);
    }

    @Test
    public void testPassIsReadFromTheTimeDirectionField() throws Exception {
        // CEOSProductDirectory.getPass() reads this when there is no map projection record, and
        // anything not starting with "DESC" is ASCENDING. A blank field here made every descending
        // ALOS-4 scene ascending.
        final String direction = readRecord().getAttributeString("Time direction indicator along line direction");
        assertTrue("was '" + direction + "'", direction.trim().startsWith("DESC"));
    }

    @Test
    public void testIncidenceAnglePolynomial() throws Exception {
        final BinaryRecord scene = readRecord();
        assertEquals(-2.5130331060826E+00, scene.getAttributeDouble("Incidence angle constant term"), 1e-12);
        assertEquals(2.9976145625357E-03, scene.getAttributeDouble("Incidence angle linear term"), 1e-15);
        assertEquals(5.9127141806338E-06, scene.getAttributeDouble("Incidence angle quadratic term"), 1e-18);
        assertEquals(-3.6096574867289E-09, scene.getAttributeDouble("Incidence angle cubic term"), 1e-21);
        assertEquals(-6.5751018580496E-12, scene.getAttributeDouble("Incidence angle fourth term"), 1e-24);
        assertEquals(4.7445207923820E-15, scene.getAttributeDouble("Incidence angle fifth term"), 1e-27);
    }

    @Test
    public void testNominalOffNadirAngle() throws Exception {
        assertEquals(37.907376, readRecord().getAttributeDouble("Nominal offnadir angle"), 1e-9);
    }

    @Test
    public void testFieldsBeforeTheDopplerBlocksAreUnchanged() throws Exception {
        // Bytes 0-1461 are laid out as for PALSAR-2 and must stay so.
        final BinaryRecord scene = readRecord();
        assertEquals(0.2424443, scene.getAttributeDouble("Radar wavelength"), 1e-9);
        assertEquals(49.1210934, scene.getAttributeDouble("Range sampling rate"), 1e-9);
    }

    @Test
    public void testRangeSpacingMatchesTheSamplingRate() throws Exception {
        // Independent witness for the pixel-spacing position: the slant-range spacing is c / (2 fs).
        // If a layout error put another 16-character float where the spacing is read, this and the
        // value above could not both hold.
        final BinaryRecord scene = readRecord();
        final double c = 299792458.0;
        final double fsHz = scene.getAttributeDouble("Range sampling rate") * 1e6;
        assertEquals(c / (2.0 * fsHz), scene.getAttributeDouble("Pixel spacing"), 1e-6);
    }
}
