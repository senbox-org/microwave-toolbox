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
import org.esa.snap.runtime.Config;
import org.esa.snap.runtime.EngineConfig;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * Created by lveci on 2/22/2017.
 */
public class CredentialsManager {

    private final Preferences credentialsPreferences = Config.instance("Credentials").load().preferences();

    private static final String PREFIX = "credential.";
    private static final String USER = ".user";
    private static final String PASSWORD = ".password";

    private static CredentialsManager instance;

    private StandardPBEStringEncryptor encryptor;

    private CredentialsManager() {
        encryptor = new StandardPBEStringEncryptor();
        encryptor.setPassword("Mzg1YWFkNjY0MjA2MGY1ZTIyMThjYjFj");
    }

    public static CredentialsManager instance() {
        if (instance == null) {
            instance = new CredentialsManager();
        }
        return instance;
    }

    public void put(final String host, final String user, final String password) throws Exception {
        credentialsPreferences.put(PREFIX + host + USER, user);
        credentialsPreferences.put(PREFIX + host + PASSWORD, encryptor.encrypt(password));
        credentialsPreferences.flush();
    }

    public UsernamePasswordCredentials get(final String host) {
        final String user = credentialsPreferences.get(PREFIX + host + USER, null);
        final String encryptedPassword = credentialsPreferences.get(PREFIX + host + PASSWORD, null);
        String password = encryptor.decrypt(encryptedPassword);

        return user == null || password == null ? null : new UsernamePasswordCredentials(user, password);
    }

    private static Path getProductLibraryConfigFilePath() {
        return Paths.get(EngineConfig.instance().userDir().toString() + "/config/Preferences/product-library.properties");
    }

    public UsernamePasswordCredentials getProductLibraryCredentials(final String repo) {

        try {
            Properties properties = new Properties();
            try (InputStream inputStream = Files.newInputStream(getProductLibraryConfigFilePath())) {
                properties.load(inputStream);
            }

            String key = findCopernicusKey(properties, repo);
            if(key == null) {
                return null;
            }
            String username = properties.getProperty(key + ".username");
            String password = properties.getProperty(key + ".password");
            password = decrypt(password, "Copernicus DataSpace");
            return new UsernamePasswordCredentials(username, password);
        } catch (Exception e) {
            return null;
        }
    }

    private static String findCopernicusKey(Properties properties, String repoKey) {
        for (Map.Entry<Object, Object> e : properties.entrySet()) {
            String key = e.getKey().toString();
            if (key.contains(repoKey)) {
                return key.substring(0, key.lastIndexOf("."));
            }
        }
        return null;
    }

    public static String decrypt(String textToDecrypt, String secretKey)
            throws UnsupportedEncodingException, NoSuchAlgorithmException, BadPaddingException, IllegalBlockSizeException, NoSuchPaddingException, InvalidKeyException {

        if (textToDecrypt != null && secretKey != null) {
            SecretKeySpec e = createKey(secretKey);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5PADDING");
            cipher.init(2, e);
            return new String(cipher.doFinal(Base64.getDecoder().decode(textToDecrypt)));
        } else {
            return null;
        }
    }

    private static SecretKeySpec createKey(String secretKey) throws UnsupportedEncodingException, NoSuchAlgorithmException {
        byte[] e = secretKey.getBytes("UTF-8");
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        e = sha.digest(e);
        e = Arrays.copyOf(e, 16);
        return new SecretKeySpec(e, "AES");
    }
}
