package eu.esa.sar.teststacks.corner_reflectors;

import au.com.bytecode.opencsv.CSVReader;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.PinDescriptor;
import org.esa.snap.core.datamodel.Placemark;
import org.esa.snap.core.datamodel.PlacemarkDescriptor;
import org.esa.snap.core.datamodel.Product;
import org.junit.Assert;

import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.List;

public class BaseCRTest {

    private final PlacemarkDescriptor pinDescriptor = PinDescriptor.getInstance();

    protected void addPin(Product product, String id, double lat, double lon) {
        Placemark pin = Placemark.createPointPlacemark(pinDescriptor, id, id, "",
                null, new GeoPos(lat, lon), product.getSceneGeoCoding());
        product.getPinGroup().add(pin);
    }

    protected List<String[]> readCSVFile(String fileName) throws IOException {
        URL url = this.getClass().getResource(fileName);
        Assert.assertNotNull(url);
        try (FileReader reader = new FileReader(url.getPath())) {
            final CSVReader cvsReader = new CSVReader(reader);
            return cvsReader.readAll();
        }
    }
}
