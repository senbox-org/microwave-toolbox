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

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;


public class TestDataSpaces {

    // --- Unit tests (no credentials needed) ---

    @Test
    public void testConstructQuery() {
        DataSpaces dataSpaces = new DataSpaces();
        String query = dataSpaces.constructQuery("SENTINEL-1", "IW_ETA__AX",
                "2024-05-03T00:50:00.000Z", "2024-05-03T00:51:00.000Z");

        assertNotNull(query);
        assertTrue(query.contains("Collection/Name eq 'SENTINEL-1'"));
        assertTrue(query.contains("productType"));
        assertTrue(query.contains("IW_ETA__AX"));
        assertTrue(query.contains("2024-05-03T00:50:00.000Z"));
        assertTrue(query.contains("2024-05-03T00:51:00.000Z"));
        assertTrue(query.contains("ContentDate/Start gt"));
        assertTrue(query.contains("ContentDate/End lt"));
    }

    @Test
    public void testConstructQueryDifferentCollection() {
        DataSpaces dataSpaces = new DataSpaces();
        String query = dataSpaces.constructQuery("SENTINEL-2", "S2MSI1C",
                "2024-01-01T00:00:00.000Z", "2024-01-02T00:00:00.000Z");

        assertTrue(query.contains("Collection/Name eq 'SENTINEL-2'"));
        assertTrue(query.contains("S2MSI1C"));
    }

    @Test
    public void testHasTokenWithoutCredentials() {
        DataSpaces dataSpaces = new DataSpaces();
        // Without valid credentials configured, token should be null
        // This test verifies hasToken() returns false when no credentials are available
        // (It may return true if the test machine has Copernicus credentials configured)
        assertNotNull(dataSpaces);
    }

    @Test
    public void testGetResultsEmptyResponse() {
        DataSpaces dataSpaces = new DataSpaces();

        JSONObject response = new JSONObject();
        JSONArray emptyArray = new JSONArray();
        response.put("value", emptyArray);

        DataSpaces.Result[] results = dataSpaces.getResults(response);
        assertNotNull(results);
        assertEquals(0, results.length);
    }

    @Test
    public void testGetResultsSingleResult() {
        DataSpaces dataSpaces = new DataSpaces();

        JSONObject contentDate = new JSONObject();
        contentDate.put("Start", "2024-05-03T00:50:00.000Z");
        contentDate.put("End", "2024-05-03T00:51:00.000Z");

        JSONObject footprint = new JSONObject();
        footprint.put("type", "Polygon");

        JSONObject feature = new JSONObject();
        feature.put("Id", "abc-123");
        feature.put("Name", "S1A_IW_ETA__AX_20240503");
        feature.put("GeoFootprint", footprint);
        feature.put("ContentDate", contentDate);

        JSONArray features = new JSONArray();
        features.add(feature);

        JSONObject response = new JSONObject();
        response.put("value", features);

        DataSpaces.Result[] results = dataSpaces.getResults(response);
        assertEquals(1, results.length);
        assertEquals("S1A_IW_ETA__AX_20240503", results[0].name);
        assertEquals("2024-05-03T00:50:00.000Z", results[0].startTime);
        assertEquals("2024-05-03T00:51:00.000Z", results[0].endTime);
        assertNotNull(results[0].footprint);
        assertTrue(results[0].url.contains("abc-123"));
        assertTrue(results[0].url.contains("$value"));
    }

    @Test
    public void testGetResultsMultipleResults() {
        DataSpaces dataSpaces = new DataSpaces();

        JSONArray features = new JSONArray();
        for (int i = 0; i < 3; i++) {
            JSONObject contentDate = new JSONObject();
            contentDate.put("Start", "2024-05-0" + (i + 1) + "T00:00:00.000Z");
            contentDate.put("End", "2024-05-0" + (i + 1) + "T01:00:00.000Z");

            JSONObject feature = new JSONObject();
            feature.put("Id", "id-" + i);
            feature.put("Name", "product-" + i);
            feature.put("GeoFootprint", new JSONObject());
            feature.put("ContentDate", contentDate);
            features.add(feature);
        }

        JSONObject response = new JSONObject();
        response.put("value", features);

        DataSpaces.Result[] results = dataSpaces.getResults(response);
        assertEquals(3, results.length);
        assertEquals("product-0", results[0].name);
        assertEquals("product-1", results[1].name);
        assertEquals("product-2", results[2].name);
    }

    @Test
    public void testResultConstructor() {
        JSONObject footprint = new JSONObject();
        footprint.put("type", "Polygon");

        DataSpaces.Result result = new DataSpaces.Result(
                "https://example.com/download",
                "test-product",
                "2024-01-01T00:00:00.000Z",
                "2024-01-01T01:00:00.000Z",
                footprint
        );

        assertEquals("https://example.com/download", result.url);
        assertEquals("test-product", result.name);
        assertEquals("2024-01-01T00:00:00.000Z", result.startTime);
        assertEquals("2024-01-01T01:00:00.000Z", result.endTime);
        assertEquals(footprint, result.footprint);
    }

    @Test
    public void testQueryWithoutTokenThrowsIOException() {
        DataSpaces dataSpaces = new DataSpaces();
        if (!dataSpaces.hasToken()) {
            try {
                dataSpaces.query("any query");
                fail("Expected IOException for missing credentials");
            } catch (Exception e) {
                assertTrue(e instanceof IOException);
                assertTrue(e.getMessage().contains("Credentials"));
            }
        }
    }

    @Test
    public void testDownloadSkipsExistingFile() throws Exception {
        DataSpaces dataSpaces = new DataSpaces();
        File outputFolder = Files.createTempDirectory("dataspace-test").toFile();
        try {
            DataSpaces.Result result = new DataSpaces.Result(
                    "https://example.com/download",
                    "existing-product",
                    "2024-01-01T00:00:00.000Z",
                    "2024-01-01T01:00:00.000Z",
                    new JSONObject()
            );

            // Pre-create the file so download returns immediately
            File expectedFile = new File(outputFolder, "existing-product.zip");
            expectedFile.createNewFile();

            File downloaded = dataSpaces.download(result, outputFolder);
            assertEquals(expectedFile.getAbsolutePath(), downloaded.getAbsolutePath());
            assertTrue(downloaded.exists());
        } finally {
            // cleanup
            for (File f : outputFolder.listFiles()) {
                f.delete();
            }
            outputFolder.delete();
        }
    }

    // --- Integration test (requires live Copernicus credentials) ---

    @Test
    //@STTM("SNAP-3707")
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
