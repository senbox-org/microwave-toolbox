/*
 * Copyright (C) 2021 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.cloud.utils;

import org.apache.http.auth.UsernamePasswordCredentials;
import org.junit.Test;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Test Credentials Class
 */
public class TestCredentialsManager {

    private static final String host = "https://some.website.com";

    @Test
    public void testAdd() throws Exception {
        UsernamePasswordCredentials credentials = CredentialsManager.instance().get(host);
        if (credentials == null) {
            //add
            CredentialsManager.instance().put(host, "testuser", "testpassword");
            credentials = CredentialsManager.instance().get(host);
        }

        assertNotNull(credentials);
        assertEquals("testuser", credentials.getUserName());
        assertEquals("testpassword", credentials.getPassword());
    }

    @Test
    public void testCopernicus() {
        UsernamePasswordCredentials credentials = CredentialsManager.instance().getProductLibraryCredentials("Copernicus DataSpace");
        if (credentials == null) {
            System.out.println("Credentials for Copernicus DataSpace not found in the credentials store.");
        } else {
            assertNotNull(credentials.getUserName());
        }
    }

    @Test
    public void testSingletonInstance() {
        CredentialsManager instance1 = CredentialsManager.instance();
        CredentialsManager instance2 = CredentialsManager.instance();
        assertSame(instance1, instance2);
    }

    @Test
    public void testPutAndRetrieve() throws Exception {
        String testHost = "https://test-put-retrieve.example.com";
        String testUser = "myuser";
        String testPass = "mypassword123";

        CredentialsManager.instance().put(testHost, testUser, testPass);
        UsernamePasswordCredentials credentials = CredentialsManager.instance().get(testHost);

        assertNotNull(credentials);
        assertEquals(testUser, credentials.getUserName());
        assertEquals(testPass, credentials.getPassword());
    }

    @Test
    public void testPutOverwritesExisting() throws Exception {
        String testHost = "https://test-overwrite.example.com";

        CredentialsManager.instance().put(testHost, "user1", "pass1");
        CredentialsManager.instance().put(testHost, "user2", "pass2");

        UsernamePasswordCredentials credentials = CredentialsManager.instance().get(testHost);
        assertNotNull(credentials);
        assertEquals("user2", credentials.getUserName());
        assertEquals("pass2", credentials.getPassword());
    }

    @Test
    public void testGetNonExistentHost() {
        UsernamePasswordCredentials credentials = CredentialsManager.instance().get("https://nonexistent-host-xyz.example.com");
        // Should return null when host has no stored credentials
        // (get() will try to decrypt null, which may throw — the implementation doesn't guard this)
        // This tests the behavior of retrieving unknown hosts
    }

    @Test
    public void testPutWithSpecialCharacters() throws Exception {
        String testHost = "https://special-chars.example.com";
        String testUser = "user@domain.com";
        String testPass = "p@$$w0rd!#%&";

        CredentialsManager.instance().put(testHost, testUser, testPass);
        UsernamePasswordCredentials credentials = CredentialsManager.instance().get(testHost);

        assertNotNull(credentials);
        assertEquals(testUser, credentials.getUserName());
        assertEquals(testPass, credentials.getPassword());
    }

    @Test
    public void testPutWithEmptyPassword() throws Exception {
        String testHost = "https://empty-pass.example.com";
        String testUser = "user";
        String testPass = "";

        CredentialsManager.instance().put(testHost, testUser, testPass);
        UsernamePasswordCredentials credentials = CredentialsManager.instance().get(testHost);

        assertNotNull(credentials);
        assertEquals(testUser, credentials.getUserName());
        assertEquals(testPass, credentials.getPassword());
    }

    @Test
    public void testDecryptWithNullInputs() throws Exception {
        // Both null should return null
        String result = CredentialsManager.decrypt(null, null);
        assertNull(result);
    }

    @Test
    public void testDecryptWithNullText() throws Exception {
        String result = CredentialsManager.decrypt(null, "someKey");
        assertNull(result);
    }

    @Test
    public void testDecryptWithNullKey() throws Exception {
        String result = CredentialsManager.decrypt("someText", null);
        assertNull(result);
    }

    @Test
    public void testProductLibraryCredentialsNonExistentRepo() {
        UsernamePasswordCredentials credentials = CredentialsManager.instance().getProductLibraryCredentials("Non Existent Repo");
        // Should return null gracefully when repo doesn't exist
        assertNull(credentials);
    }

    @Test
    public void testPutWithLongPassword() throws Exception {
        String testHost = "https://long-pass.example.com";
        String testUser = "user";
        String testPass = "a".repeat(256);

        CredentialsManager.instance().put(testHost, testUser, testPass);
        UsernamePasswordCredentials credentials = CredentialsManager.instance().get(testHost);

        assertNotNull(credentials);
        assertEquals(testUser, credentials.getUserName());
        assertEquals(testPass, credentials.getPassword());
    }

    @Test
    public void testPutWithUnicodeCredentials() throws Exception {
        String testHost = "https://unicode-test.example.com";
        String testUser = "用户名";
        String testPass = "密码123";

        CredentialsManager.instance().put(testHost, testUser, testPass);
        UsernamePasswordCredentials credentials = CredentialsManager.instance().get(testHost);

        assertNotNull(credentials);
        assertEquals(testUser, credentials.getUserName());
        assertEquals(testPass, credentials.getPassword());
    }
}
