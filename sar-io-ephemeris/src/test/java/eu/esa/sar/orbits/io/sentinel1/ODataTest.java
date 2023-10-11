package eu.esa.sar.orbits.io.sentinel1;

import org.junit.Test;

public class ODataTest {

    private static final String USER = "luis@skywatch.com";
    private static final String PASSWORD = "ESA_snap_2023";

    private static String api_endpoint = "https://catalogue.dataspace.copernicus.eu/odata/v1/Products?$expand=Attributes&$filter=contains(Name,%27AX____POE__AX%27)%20and%20startswith(Name,%27S6%27)&$count=True";
    private static String odataRoot = "https://catalogue.dataspace.copernicus.eu/odata/v1/";

    @Test
    public void testOData() throws Exception {

        final DataSpaces dataSpaces = new DataSpaces(USER, PASSWORD);
        dataSpaces.connect(odataRoot);
    }
}
