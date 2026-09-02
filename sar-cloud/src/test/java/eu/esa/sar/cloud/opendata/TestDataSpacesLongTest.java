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
package eu.esa.sar.cloud.opendata;

import com.bc.ceres.test.LongTestRunner;
import org.json.simple.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Live Copernicus Data Space integration test, split out of {@link TestDataSpaces} (which keeps
 * the fast, offline unit tests): this one performs a real query AND downloads a full ETAD
 * product (~45 s + network), so it is gated per the long-test convention
 * ({@code -Denable.long.tests=true}) instead of taxing every default {@code mvn test} run
 * with a network-and-credentials dependency.
 */
@RunWith(LongTestRunner.class)
public class TestDataSpacesLongTest {

    @Test
    public void testDataSpaces() throws Exception {
        final DataSpaces dataSpaces = new DataSpaces();
        assumeTrue("DataSpaces credentials not found", dataSpaces.hasToken());

        String query = dataSpaces.constructQuery("SENTINEL-1", "IW_ETA__AX",
                "2024-05-03T00:50:00.000Z", "2024-05-03T00:51:00.000Z");
        JSONObject response = dataSpaces.query(query);

        DataSpaces.Result[] results = dataSpaces.getResults(response);
        assertTrue(results.length != 0);

        File outputFolder = Files.createTempDirectory("etad").toFile();
        File file = dataSpaces.download(results[0], outputFolder);
        assertTrue(file.exists());
    }
}
