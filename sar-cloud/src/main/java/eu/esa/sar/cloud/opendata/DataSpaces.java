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

import eu.esa.sar.cloud.utils.CredentialsManager;
import org.apache.commons.io.IOUtils;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
//import org.esa.snap.core.dataop.downloadable.SSLUtil;
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
import java.util.ArrayList;
import java.util.List;

public class DataSpaces {

    private final String token;

    private final static String COPERNICUS_REPO = "Copernicus DataSpace";
    private final static String baseUrl = "https://catalogue.dataspace.copernicus.eu/odata/v1/Products?$filter=";

    public DataSpaces(final String username, final String password) {
        this.token = getAccessToken(username, password);
    }

    public DataSpaces() {
        UsernamePasswordCredentials credentials = CredentialsManager.instance().getProductLibraryCredentials(COPERNICUS_REPO);
        if(credentials != null) {
            this.token = getAccessToken(credentials.getUserName(), credentials.getPassword());
        } else {
            this.token = null;
        }
    }

    public boolean hasToken() {
        return token != null;
    }

    public String constructQuery(final String collection, final String productType,
                                 final String startDate, final String endDate) {
        String query = "Collection/Name eq '"+collection+"'";
        query += " and Attributes/OData.CSC.StringAttribute/any(att:att/Name eq 'productType' and att/OData.CSC.StringAttribute/Value eq '"+productType+"')";
        // Temporal OVERLAP, not containment.
        //
        // This previously read "Start gt startDate and End lt endDate", i.e. the auxiliary product had
        // to fit ENTIRELY INSIDE the requested window. That holds only when the caller's window is at
        // least as long as the product it is looking for. For a BURST-SUBSET source it is not: three
        // IW bursts span ~9 s (window 22:50:59-22:51:08 measured on S1A IW3 bursts 4-6) while the
        // parent ETAD product spans the full ~30 s frame, so nothing matched and S1-ETAD-Correction
        // failed with "ETAD product not found" -- indistinguishable from genuinely missing data.
        //
        // Overlap is the correct relation for "find the auxiliary product covering my data": it still
        // matches every case containment did (containment implies overlap), and additionally matches a
        // parent frame when the source is a subset of it.
        query += " and ContentDate/Start lt "+endDate+" and ContentDate/End gt "+startDate;
        return query;
    }

    public JSONObject query(String query) throws Exception {
        if (token == null) {
            throw new IOException("Credentials for Copernicus DataSpace not found in the credentials store.");
        }

        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = new URI(baseUrl + encodedQuery);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", token);

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String line;
        StringBuilder response = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        //System.out.println(response);
        JSONParser parser = new JSONParser();
        return (JSONObject) parser.parse(response.toString());
    }

    public Result[] getResults(JSONObject json) {
        List<Result> results = new ArrayList<>();
        JSONArray features = (JSONArray) json.get("value");
        for (Object o : features) {
            JSONObject feature = (JSONObject) o;
            String id = (String) feature.get("Id");
            String name = (String) feature.get("Name");
            JSONObject footprint = (JSONObject) feature.get("GeoFootprint");
            JSONObject contentDate = (JSONObject) feature.get("ContentDate");
            String startTime = (String) contentDate.get("Start");
            String endTime = (String) contentDate.get("End");

            //SystemUtils.LOG.info("Found product: "+name);

            String fileUrl = "https://download.dataspace.copernicus.eu/odata/v1/Products("+id+")/$value";
            results.add(new Result(fileUrl, name, startTime, endTime, footprint));
        }
        return results.toArray(new Result[0]);
    }

    public File download(final Result result, final File outputFolder) throws Exception {
        outputFolder.mkdirs();
        File outputFile = new File(outputFolder, result.name+".zip");
        if(outputFile.exists()) {
            return outputFile;
        }
        downloadFile(result.url, outputFile);
        return outputFile;
    }

    private void downloadFile(String fileUrl, final File outputFile) throws Exception {
        //final SSLUtil ssl = new SSLUtil();
        //ssl.disableSSLCertificateCheck();

        URL url = new URL(fileUrl);

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpGet httpGet = new HttpGet(url.toString());
        httpGet.setHeader("Authorization", token);

        try (CloseableHttpResponse response = httpClient.execute(httpGet);
             InputStream inputStream = response.getEntity().getContent();
             FileOutputStream fileOutputStream = new FileOutputStream(outputFile)) {

            IOUtils.copy(inputStream, fileOutputStream);

            EntityUtils.consume(response.getEntity());
            //SystemUtils.LOG.info("File "+outputFile.getAbsolutePath()+ " downloaded successfully!");

        } catch (IOException e) {
            e.printStackTrace();
        }

        //ssl.enableSSLCertificateCheck();
    }

    private static String getAccessToken(final String username, final String password) {
        String urlString = "https://identity.dataspace.copernicus.eu/auth/realms/CDSE/protocol/openid-connect/token";
        String clientId = "cdse-public";
        String grantType = "password";

        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setDoOutput(true);

            String params = "client_id=" + clientId + "&username=" + username + "&password=" + password + "&grant_type=" + grantType;
            OutputStream os = conn.getOutputStream();
            os.write(params.getBytes());
            os.flush();
            os.close();

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                JSONParser parser = new JSONParser();
                JSONObject tokenjson = (JSONObject) parser.parse(response.toString());
                String token = "Bearer " + tokenjson.get("access_token");

                return token;
            } else {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                String inputLine;
                StringBuffer response = new StringBuffer();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                throw new Exception("Access token creation failed. Response from the server was: " + response);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static class Result {
        String url;
        String name;
        String startTime;
        String endTime;
        JSONObject footprint;

        public Result(String url, String name, String startTime, String endTime, JSONObject footprint) {
            this.url = url;
            this.name = name;
            this.startTime = startTime;
            this.endTime = endTime;
            this.footprint = footprint;
        }

        public String getName() {
            return name;
        }

        /** ContentDate/Start, ISO-8601 with trailing 'Z' (e.g. 2026-06-24T22:49:32.492Z). */
        public String getStartTime() {
            return startTime;
        }

        /** ContentDate/End, same format as {@link #getStartTime()}. */
        public String getEndTime() {
            return endTime;
        }
    }
}
