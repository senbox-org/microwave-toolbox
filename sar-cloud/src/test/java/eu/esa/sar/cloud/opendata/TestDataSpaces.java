/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.cloud.opendata;

import org.json.simple.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;


public class TestDataSpaces {

    @Before
    public void setUp() throws Exception {
        final DataSpaces dataSpaces = new DataSpaces();
        assumeTrue("DataSpaces credentials not found", dataSpaces.hasToken());
    }

    @Test
    //@STTM("SNAP-3707")
    public void testDataSpaces() throws Exception {

        final DataSpaces dataSpaces = new DataSpaces();

        String query = dataSpaces.constructQuery("SENTINEL-1", "IW_ETA__AX",
                "2024-05-03T00:50:00.000Z", "2024-05-03T00:50:00.000Z");
        JSONObject response = dataSpaces.query(query);

        DataSpaces.Result[] results = dataSpaces.getResults(response);
        assertTrue(results.length != 0);

        File outputFolder = Files.createTempDirectory("etad").toFile();
        File file = dataSpaces.download(results[0], outputFolder);
        assertTrue(file.exists());
    }
}
