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

import eu.esa.sar.cloud.utils.CredentialsManager;
import org.apache.commons.io.IOUtils;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Access to BIOMASS auxiliary products (notably AUX_ORB___ precise orbit files) hosted on the
 * ESA MAAP STAC catalogue. Credentials are obtained from the Product Library configuration
 * under the repository key "ESA MAAP"; the stored password is treated as the long-lasting
 * (90-day) offline refresh token issued by https://portal.maap.eo.esa.int/.
 *
 * Mirrors {@link DataSpaces} for the Copernicus Dataspace flow.
 */
public class MaapBiomassOrbitAccess {

    public static final String MAAP_REPO = "ESA MAAP";
    public static final String COLLECTION_AUX = "BiomassAux";
    public static final String PRODUCT_TYPE_AUX_ORB = "AUX_ORB___";

    private static final String tokenEndpoint =
            "https://iam.maap.eo.esa.int/realms/esa-maap/protocol/openid-connect/token";
    private static final String stacBaseUrl =
            "https://catalog.maap.eo.esa.int/catalogue/";
    private static final String oidcClientId = "offline-token";
    private static final String oidcClientSecret = "p1eL7uonXs6MDxtGbgKdPVRAmnGxHpVE";

    private final String token;

    public MaapBiomassOrbitAccess() {
        this(CredentialsManager.instance().getProductLibraryCredentials(MAAP_REPO));
    }

    public MaapBiomassOrbitAccess(final UsernamePasswordCredentials credentials) {
        if (credentials != null && credentials.getPassword() != null && !credentials.getPassword().isEmpty()) {
            this.token = exchangeOfflineToken(credentials.getPassword());
        } else {
            this.token = null;
        }
    }

    public boolean hasToken() {
        return token != null;
    }

    /**
     * Search the STAC catalogue for items in the BIOMASS auxiliary collection covering
     * {@code timeInstant} with the given product type. Returns {@code null} if no match.
     * Catalogue search is anonymous and does not require {@link #hasToken()}.
     */
    public Result searchByTime(final String productType, final String timeInstantISO) throws Exception {
        final String filter = "product:type='" + productType + "'";
        final String query = stacBaseUrl + "collections/" + COLLECTION_AUX + "/items"
                + "?datetime=" + timeInstantISO
                + "&filter-lang=cql2-text"
                + "&filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)
                + "&limit=1";

        final HttpURLConnection conn = (HttpURLConnection) new URI(query).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/geo+json");

        final StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }

        final JSONObject json = (JSONObject) new JSONParser().parse(sb.toString());
        final JSONArray features = (JSONArray) json.get("features");
        if (features == null || features.isEmpty()) {
            return null;
        }
        final JSONObject feature = (JSONObject) features.get(0);
        final String id = (String) feature.get("id");
        final JSONObject assets = (JSONObject) feature.get("assets");
        String href = null;
        if (assets != null) {
            // Look for any asset with a usable href; AUX_ORB products typically expose a single EOF asset.
            for (Object key : assets.keySet()) {
                final JSONObject asset = (JSONObject) assets.get(key);
                final String h = (String) asset.get("href");
                if (h != null && (h.toLowerCase().endsWith(".eof") || h.toLowerCase().endsWith(".zip"))) {
                    href = h;
                    break;
                }
                if (href == null && h != null) href = h;
            }
        }
        return new Result(id, href);
    }

    public File download(final Result result, final File outputFolder) throws Exception {
        if (token == null) {
            throw new IOException("ESA MAAP credentials not configured. Add a Product Library entry "
                    + "under repository name \"" + MAAP_REPO + "\" with the offline token from "
                    + "https://portal.maap.eo.esa.int/ini/services/auth/token/90dToken.php as the password.");
        }
        if (result == null || result.href == null) {
            throw new IOException("No MAAP asset URL to download");
        }

        outputFolder.mkdirs();
        final String filename = result.id.endsWith(".EOF") || result.id.endsWith(".zip")
                ? result.id : result.id + ".EOF";
        final File outputFile = new File(outputFolder, filename);
        if (outputFile.exists() && outputFile.length() > 0) {
            return outputFile;
        }
        downloadAsset(result.href, outputFile);
        return outputFile;
    }

    private void downloadAsset(final String assetUrl, final File outputFile) throws Exception {
        final CloseableHttpClient httpClient = HttpClients.createDefault();
        final HttpGet httpGet = new HttpGet(new URL(assetUrl).toString());
        httpGet.setHeader("Authorization", token);

        try (CloseableHttpResponse response = httpClient.execute(httpGet);
             InputStream inputStream = response.getEntity().getContent();
             FileOutputStream out = new FileOutputStream(outputFile)) {
            IOUtils.copy(inputStream, out);
            EntityUtils.consume(response.getEntity());
        }
    }

    private static String exchangeOfflineToken(final String offlineToken) {
        try {
            final URL url = new URL(tokenEndpoint);
            final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            final String params = "client_id=" + oidcClientId
                    + "&client_secret=" + oidcClientSecret
                    + "&grant_type=refresh_token"
                    + "&scope=offline_access+openid"
                    + "&refresh_token=" + URLEncoder.encode(offlineToken, StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.getBytes(StandardCharsets.UTF_8));
            }

            final int code = conn.getResponseCode();
            final InputStream stream = code == HttpURLConnection.HTTP_OK ? conn.getInputStream() : conn.getErrorStream();
            final StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("MAAP token exchange failed (" + code + "): " + sb);
            }
            final JSONObject json = (JSONObject) new JSONParser().parse(sb.toString());
            return "Bearer " + json.get("access_token");
        } catch (Exception e) {
            return null;
        }
    }

    public static class Result {
        public final String id;
        public final String href;

        public Result(final String id, final String href) {
            this.id = id;
            this.href = href;
        }
    }
}
