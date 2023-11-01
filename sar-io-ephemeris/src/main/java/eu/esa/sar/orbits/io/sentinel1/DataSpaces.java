package eu.esa.sar.orbits.io.sentinel1;

import org.apache.olingo.client.api.ODataClient;
import org.apache.olingo.client.api.communication.request.retrieve.EdmMetadataRequest;
import org.apache.olingo.client.api.communication.response.ODataRetrieveResponse;
import org.apache.olingo.client.core.ODataClientFactory;
import org.apache.olingo.commons.api.edm.Edm;
import org.apache.olingo.commons.api.format.ContentType;
import org.esa.snap.core.dataop.downloadable.SSLUtil;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.stream.Collectors;

public class DataSpaces {

    private final String username, password;
    private final String token;

    public DataSpaces(final String username, final String password) {
        this.username = username;
        this.password = password;
        this.token = getAccessToken(username, password);
    }

    public JSONObject query(String api_endpoint) throws Exception {
        URL url = new URL(api_endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", token);

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String line;
        StringBuilder response = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        System.out.println(response);
        JSONParser parser = new JSONParser();
        return (JSONObject) parser.parse(response.toString());
    }

    public void download(JSONObject stacJson, final File outputFolder) throws Exception {
        // Extract and download files
        JSONArray features = (JSONArray) stacJson.get("features");
        for (Object o : features) {
            JSONObject feature = (JSONObject) o;
            JSONObject properties = (JSONObject) feature.get("properties");
            JSONObject services = (JSONObject) properties.get("services");
            JSONObject download = (JSONObject) services.get("download");

            downloadFile(download.get("url").toString(), outputFolder);
        }
    }

    private void downloadFile(String fileUrl, final File outputFolder) throws Exception {
        final SSLUtil ssl = new SSLUtil();
        ssl.disableSSLCertificateCheck();

        URL url = new URL(fileUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        //connection.setAuthenticator(new HTTPDownloader.SeHttpAuthenticator(USER, PASSWORD));
        connection.setRequestProperty("Authorization", token);

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            System.out.println("Error Code: " + responseCode);
            InputStream errorStream = connection.getErrorStream();
            String errorMessage = new BufferedReader(new InputStreamReader(errorStream))
                    .lines().collect(Collectors.joining("\n"));
            System.out.println("Error Message: " + errorMessage);
            return;
        }

        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             FileOutputStream out = new FileOutputStream(outputFolder)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }

        ssl.enableSSLCertificateCheck();
    }

    public void connect(final String odataRoot) {
        ODataClient client = ODataClientFactory.getClient();

        EdmMetadataRequest request
                = client.getRetrieveRequestFactory().getMetadataRequest(odataRoot);
        request.setFormat(ContentType.APPLICATION_JSON);

        // Set the authentication token
        String auth = username + ":" + password;
        //String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
        //request.addCustomHeader("Authorization", "Basic " + encodedAuth);
        request.addCustomHeader("Authorization", token);

        ODataRetrieveResponse<Edm> response = request.execute();

        Edm edm = response.getBody();
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
}
