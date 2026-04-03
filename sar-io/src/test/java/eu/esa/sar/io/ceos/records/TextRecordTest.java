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
package eu.esa.sar.io.ceos.records;

import eu.esa.sar.io.binary.BinaryDBReader;
import eu.esa.sar.io.binary.BinaryFileReader;
import eu.esa.sar.io.binary.BinaryRecord;
import eu.esa.sar.io.ceos.CeosTestHelper;
import org.jdom2.Document;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * @noinspection OctalInteger
 */
public class TextRecordTest {

    private final static String mission = "ers";
    private final static String text_recordDefinitionFile = "text_record.xml";
    private final static Document textRecXML = BinaryDBReader.loadDefinitionFile(mission, text_recordDefinitionFile);
    private MemoryCacheImageOutputStream _ios;
    private String _prefix;
    private BinaryFileReader _reader;

    private static void writeRecordData(final ImageOutputStream ios) throws IOException {
        BaseRecordTest.writeRecordData(ios);

        // codeCharacter = "A" + 1 blank
        ios.writeBytes("A "); // A2
        ios.skipBytes(2); // reader.skipBytes(2);  // blank
        // productID = "PRODUCT:ABBBCCDE" + 24 blanks
        ios.writeBytes("PRODUCT:O1B2R_UB                        "); // A40
        // facility = "PROCESS:JAPAN-JAXA-EOC-ALOS-DPS  YYYYMMDDHHNNSS" + 13 blanks
        ios.writeBytes("PROCESS:JAPAN-JAXA-EOC-ALOS-DPS  20060410075225             "); //A60

        // Blank = 200 blanks
        CeosTestHelper.writeBlanks(ios, 200);
    }

    @Before
    public void setUp() throws Exception {
        final ByteArrayOutputStream os = new ByteArrayOutputStream(24);
        _ios = new MemoryCacheImageOutputStream(os);
        _prefix = "TextRecordTest_prefix";
        _ios.writeBytes(_prefix);
        writeRecordData(_ios);
        _ios.writeBytes("TextRecordTest_suffix"); // as suffix
        _reader = new BinaryFileReader(_ios);
    }

    @Test
    public void testInit_SimpleConstructor() throws IOException {
        _reader.seek(_prefix.length());
        final BinaryRecord textRecord = new BinaryRecord(_reader, -1, textRecXML, text_recordDefinitionFile);

        assertRecord(textRecord);
    }

    @Test
    public void testInit() throws IOException {
        final BinaryRecord textRecord = new BinaryRecord(_reader, _prefix.length(), textRecXML, text_recordDefinitionFile);

        assertRecord(textRecord);
    }

    private void assertRecord(final BinaryRecord record) throws IOException {
        BaseRecordTest.assertRecord(record);
        assertEquals(_prefix.length(), record.getStartPos());
        assertEquals(_prefix.length() + 360, _ios.getStreamPosition());

        assertEquals("A ", record.getAttributeString("Ascii code character"));
        assertEquals("PRODUCT:O1B2R_UB                        ", record.getAttributeString("Product type specifier"));
        assertEquals("PROCESS:JAPAN-JAXA-EOC-ALOS-DPS  20060410075225             ",
                record.getAttributeString("Location and datetime of product creation"));
    }
}
