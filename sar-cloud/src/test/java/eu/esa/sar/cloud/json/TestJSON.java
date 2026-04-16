/*
 * Copyright (C) 2021 SkyWatch Space Applications Inc. https://www.skywatch.com
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
package eu.esa.sar.cloud.json;

import org.json.simple.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.*;

public class TestJSON {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private JSONObject sampleJson;

    @Before
    public void setUp() {
        sampleJson = new JSONObject();
        sampleJson.put("name", "test");
        sampleJson.put("value", 42L);
        sampleJson.put("enabled", true);
    }

    // --- parse ---

    @Test
    public void testParse() throws Exception {
        String jsonStr = "{\"key\":\"value\"}";
        Object result = JSON.parse(jsonStr);
        assertNotNull(result);
        assertTrue(result instanceof JSONObject);
        assertEquals("value", ((JSONObject) result).get("key"));
    }

    @Test
    public void testParseEmptyObject() throws Exception {
        Object result = JSON.parse("{}");
        assertNotNull(result);
        assertTrue(result instanceof JSONObject);
    }

    @Test(expected = Exception.class)
    public void testParseInvalidJson() throws Exception {
        JSON.parse("not valid json");
    }

    // --- loadJSON ---

    @Test
    public void testLoadJSON() throws Exception {
        File file = tempFolder.newFile("test.json");
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("{\"name\":\"test\",\"count\":10}");
        }

        Object result = JSON.loadJSON(file);
        assertNotNull(result);
        assertTrue(result instanceof JSONObject);
        JSONObject obj = (JSONObject) result;
        assertEquals("test", obj.get("name"));
        assertEquals(10L, obj.get("count"));
    }

    @Test(expected = Exception.class)
    public void testLoadJSONNonExistentFile() throws Exception {
        JSON.loadJSON(new File("/nonexistent/path/file.json"));
    }

    // --- write and prettyPrint ---

    @Test
    public void testWriteAndReadBack() throws Exception {
        File file = new File(tempFolder.getRoot(), "output.json");

        JSON.write(sampleJson, file);
        assertTrue(file.exists());

        Object readBack = JSON.loadJSON(file);
        assertNotNull(readBack);
        assertTrue(readBack instanceof JSONObject);
    }

    @Test
    public void testWriteCreatesParentDirectories() throws Exception {
        File file = new File(tempFolder.getRoot(), "sub/dir/output.json");

        JSON.write(sampleJson, file);
        assertTrue(file.exists());
        assertTrue(file.getParentFile().exists());
    }

    @Test
    public void testPrettyPrint() throws Exception {
        String prettyStr = JSON.prettyPrint(sampleJson);
        assertNotNull(prettyStr);
        assertTrue(prettyStr.contains("\n"));
    }

    // --- equals ---

    @Test
    public void testEqualsIdenticalObjects() throws Exception {
        JSONObject obj1 = new JSONObject();
        obj1.put("a", "1");
        obj1.put("b", "2");

        JSONObject obj2 = new JSONObject();
        obj2.put("a", "1");
        obj2.put("b", "2");

        assertTrue(JSON.equals(obj1, obj2));
    }

    @Test
    public void testEqualsDifferentObjects() throws Exception {
        JSONObject obj1 = new JSONObject();
        obj1.put("a", "1");

        JSONObject obj2 = new JSONObject();
        obj2.put("a", "2");

        assertFalse(JSON.equals(obj1, obj2));
    }

    @Test
    public void testEqualsWithString() throws Exception {
        JSONObject obj1 = new JSONObject();
        obj1.put("a", "1");
        obj1.put("b", "2");

        assertTrue(JSON.equals(obj1, "{\"a\":\"1\",\"b\":\"2\"}"));
    }

    @Test
    public void testEqualsWithStringDifferent() throws Exception {
        JSONObject obj1 = new JSONObject();
        obj1.put("a", "1");

        assertFalse(JSON.equals(obj1, "{\"a\":\"999\"}"));
    }

    @Test
    public void testEqualsDifferentKeyOrder() throws Exception {
        JSONObject obj1 = new JSONObject();
        obj1.put("a", "1");
        obj1.put("b", "2");

        // Jackson tree comparison is order-independent for objects
        assertTrue(JSON.equals(obj1, "{\"b\":\"2\",\"a\":\"1\"}"));
    }

    // --- getBoolean ---

    @Test
    public void testGetBooleanFromBoolean() {
        assertTrue(JSON.getBoolean(Boolean.TRUE));
        assertFalse(JSON.getBoolean(Boolean.FALSE));
    }

    @Test
    public void testGetBooleanFromString() {
        assertTrue(JSON.getBoolean("true"));
        assertFalse(JSON.getBoolean("false"));
    }

    // --- getInt ---

    @Test
    public void testGetIntFromLong() {
        assertEquals(42, JSON.getInt(42L));
    }

    @Test
    public void testGetIntFromInteger() {
        assertEquals(42, JSON.getInt(42));
    }

    @Test
    public void testGetIntFromDouble() {
        assertEquals(42, JSON.getInt(42.9));
    }

    @Test
    public void testGetIntFromFloat() {
        assertEquals(42, JSON.getInt(42.7f));
    }

    @Test
    public void testGetIntFromString() {
        assertEquals(42, JSON.getInt("42"));
    }

    @Test(expected = NumberFormatException.class)
    public void testGetIntFromInvalidString() {
        JSON.getInt("not_a_number");
    }

    // --- getLong ---

    @Test
    public void testGetLongFromLong() {
        assertEquals(100L, JSON.getLong(100L));
    }

    @Test
    public void testGetLongFromInteger() {
        assertEquals(100L, JSON.getLong(100));
    }

    @Test
    public void testGetLongFromDouble() {
        assertEquals(100L, JSON.getLong(100.9));
    }

    @Test
    public void testGetLongFromFloat() {
        assertEquals(100L, JSON.getLong(100.7f));
    }

    @Test
    public void testGetLongFromString() {
        assertEquals(100L, JSON.getLong("100"));
    }

    @Test(expected = NumberFormatException.class)
    public void testGetLongFromInvalidString() {
        JSON.getLong("not_a_number");
    }

    // --- getDouble ---

    @Test
    public void testGetDoubleFromDouble() {
        assertEquals(3.14, JSON.getDouble(3.14), 0.001);
    }

    @Test
    public void testGetDoubleFromFloat() {
        assertEquals(3.14f, JSON.getDouble(3.14f), 0.01);
    }

    @Test
    public void testGetDoubleFromLong() {
        assertEquals(42.0, JSON.getDouble(42L), 0.001);
    }

    @Test
    public void testGetDoubleFromInteger() {
        assertEquals(42.0, JSON.getDouble(42), 0.001);
    }

    @Test
    public void testGetDoubleFromString() {
        assertEquals(3.14, JSON.getDouble("3.14"), 0.001);
    }

    @Test
    public void testGetDoubleFromNull() {
        assertEquals(Double.NEGATIVE_INFINITY, JSON.getDouble(null), 0.0);
    }

    @Test(expected = NumberFormatException.class)
    public void testGetDoubleFromInvalidString() {
        JSON.getDouble("not_a_number");
    }

    // --- edge cases for numeric conversions ---

    @Test
    public void testGetIntFromZero() {
        assertEquals(0, JSON.getInt(0L));
        assertEquals(0, JSON.getInt(0));
        assertEquals(0, JSON.getInt(0.0));
        assertEquals(0, JSON.getInt("0"));
    }

    @Test
    public void testGetIntFromNegative() {
        assertEquals(-5, JSON.getInt(-5L));
        assertEquals(-5, JSON.getInt(-5));
        assertEquals(-5, JSON.getInt(-5.0));
        assertEquals(-5, JSON.getInt("-5"));
    }

    @Test
    public void testGetLongFromLargeValue() {
        long largeValue = Long.MAX_VALUE;
        assertEquals(largeValue, JSON.getLong(largeValue));
    }

    @Test
    public void testGetLongFromNegative() {
        assertEquals(-100L, JSON.getLong(-100L));
        assertEquals(-100L, JSON.getLong(-100));
        assertEquals(-100L, JSON.getLong("-100"));
    }
}
