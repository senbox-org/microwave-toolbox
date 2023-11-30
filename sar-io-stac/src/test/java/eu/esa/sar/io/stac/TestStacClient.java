package eu.esa.sar.io.stac;

import eu.esa.sar.io.stac.internal.Modifier;
import eu.esa.sar.io.stac.internal.StacCatalog;
import eu.esa.sar.io.stac.internal.StacCollection;
import eu.esa.sar.io.stac.internal.StacItem;
import org.junit.Test;

import java.io.File;
import java.net.MalformedURLException;

public class TestStacClient {

    @Test
    public void testStac() throws MalformedURLException {
        Modifier modifier = new Modifier();
        StacClient client = new StacClient("https://planetarycomputer.microsoft.com/api/stac/v1", modifier.planetaryComputer());
        StacCatalog catalog = client.getCatalog();
        System.out.println(catalog.getTitle());

        StacItem [] results = client.search(new String[]{"sentinel-2-l2a", "landsat-c2-l2"}, new double[]{-124.2751,45.5469,-123.9613,45.7458}, "2020-01-01/2022-11-05" );
        System.out.println(results.length);
        System.out.println(results[35].getId());
        System.out.println(results[35].getAsset("B08").getURL());
        for (String s : results[35].listAssetIds()){
            System.out.println(results[35].getAsset(s).getRole());
            client.downloadAsset(results[35].getAsset(s), new File("/tmp"));
        }
    }
}
