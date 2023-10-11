package eu.esa.sar.orbits.io.sentinel1;

import org.json.simple.JSONObject;
import org.junit.Test;

import java.io.File;


public class DataSpacesTest {

    private static final String USER = "luis@skywatch.com";
    private static final String PASSWORD = "ESA_snap_2023";

    private static final String API_ENDPOINT = "https://catalogue.dataspace.copernicus.eu/resto/api/collections/Sentinel1/search.json?productType=AUX_RESORB&startDate=2023-09-01&endDate=2023-09-30";

    @Test
    public void testDataSpaces() throws Exception {

        final DataSpaces dataSpaces = new DataSpaces(USER, PASSWORD);

        JSONObject response = dataSpaces.query(API_ENDPOINT);

        dataSpaces.download(response, new File("/tmp"));

    }
}
