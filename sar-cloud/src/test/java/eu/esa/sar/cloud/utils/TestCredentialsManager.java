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

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;

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
    
}
