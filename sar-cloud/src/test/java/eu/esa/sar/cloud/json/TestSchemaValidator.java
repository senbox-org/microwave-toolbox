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

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import org.json.simple.JSONObject;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class TestSchemaValidator {

    private SchemaValidator validator;

    private static final String VALID_SCHEMA = "{\n" +
            "  \"$schema\": \"http://json-schema.org/draft-07/schema#\",\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"name\": { \"type\": \"string\" },\n" +
            "    \"age\": { \"type\": \"integer\", \"minimum\": 0 }\n" +
            "  },\n" +
            "  \"required\": [\"name\", \"age\"]\n" +
            "}";

    @Before
    public void setUp() {
        validator = new SchemaValidator();
    }

    // --- validate with classpath schema ---

    @Test
    public void testValidateValidJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", "John");
        json.put("age", 30);

        // Should not throw
        validator.validate("test-schema.json", json);
    }

    @Test(expected = Exception.class)
    public void testValidateInvalidJsonMissingRequired() throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", "John");
        // missing required "age" field

        validator.validate("test-schema.json", json);
    }

    @Test(expected = Exception.class)
    public void testValidateInvalidJsonWrongType() throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", "John");
        json.put("age", "not_a_number");

        validator.validate("test-schema.json", json);
    }

    @Test
    public void testValidateSilentMode() throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", "John");
        // missing required "age" — silent mode should NOT throw

        validator.validate("test-schema.json", json, true);
    }

    @Test
    public void testValidateWithOptionalField() throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", "John");
        json.put("age", 30);
        json.put("email", "john@example.com");

        validator.validate("test-schema.json", json);
    }

    // --- getJsonNodeFromStringContent ---

    @Test
    public void testGetJsonNodeFromStringContent() throws Exception {
        String content = "{\"key\":\"value\",\"num\":42}";
        JsonNode node = validator.getJsonNodeFromStringContent(content);

        assertNotNull(node);
        assertEquals("value", node.get("key").asText());
        assertEquals(42, node.get("num").asInt());
    }

    @Test
    public void testGetJsonNodeFromStringContentEmptyObject() throws Exception {
        JsonNode node = validator.getJsonNodeFromStringContent("{}");
        assertNotNull(node);
        assertTrue(node.isObject());
        assertEquals(0, node.size());
    }

    @Test(expected = Exception.class)
    public void testGetJsonNodeFromStringContentInvalid() throws Exception {
        validator.getJsonNodeFromStringContent("not valid json");
    }

    // --- getJsonNodeFromClasspath ---

    @Test
    public void testGetJsonNodeFromClasspath() throws Exception {
        JsonNode node = validator.getJsonNodeFromClasspath("test-schema.json");
        assertNotNull(node);
        assertTrue(node.has("$schema"));
        assertTrue(node.has("properties"));
        assertTrue(node.has("required"));
    }

    // --- getJsonSchemaFromStringContent ---

    @Test
    public void testGetJsonSchemaFromStringContent() {
        JsonSchema schema = validator.getJsonSchemaFromStringContent(VALID_SCHEMA);
        assertNotNull(schema);
    }

    // --- getJsonSchemaFromClasspath ---

    @Test
    public void testGetJsonSchemaFromClasspath() {
        JsonSchema schema = validator.getJsonSchemaFromClasspath("test-schema.json");
        assertNotNull(schema);
    }

    // --- getJsonSchemaFromJsonNode ---

    @Test
    public void testGetJsonSchemaFromJsonNode() throws Exception {
        JsonNode node = validator.getJsonNodeFromStringContent(VALID_SCHEMA);
        JsonSchema schema = validator.getJsonSchemaFromJsonNode(node);
        assertNotNull(schema);
    }

    // --- validate with negative age (violates minimum constraint) ---

    @Test(expected = Exception.class)
    public void testValidateNegativeAge() throws Exception {
        JSONObject json = new JSONObject();
        json.put("name", "John");
        json.put("age", -1);

        validator.validate("test-schema.json", json);
    }

    // --- validate empty object ---

    @Test(expected = Exception.class)
    public void testValidateEmptyObject() throws Exception {
        JSONObject json = new JSONObject();
        validator.validate("test-schema.json", json);
    }
}
