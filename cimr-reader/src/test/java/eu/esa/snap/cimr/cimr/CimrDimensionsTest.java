package eu.esa.snap.cimr.cimr;

import org.junit.Test;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;


public class CimrDimensionsTest {


    @Test
    public void testFromCollectsAllDimensions() {
        NetcdfFile ncFile = new NetcdfFile() {
            @Override
            public List<Dimension> getDimensions() {
                List<Dimension> dims = new ArrayList<>();
                dims.add(new Dimension("n_scans", 100));
                dims.add(new Dimension("n_samples_C_BAND", 200));
                dims.add(new Dimension("n_feeds_C_BAND", 3));
                return dims;
            }
        };

        CimrDimensions dims = CimrDimensions.from(ncFile);
        assertNotNull(dims);

        assertEquals(100, dims.get("n_scans"));
        assertEquals(200, dims.get("n_samples_C_BAND"));
        assertEquals(3, dims.get("n_feeds_C_BAND"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetThrowsOnUnknownDimension() {
        NetcdfFile ncFile = new NetcdfFile() {
            @Override
            public List<Dimension> getDimensions() {
                List<Dimension> dims = new ArrayList<>();
                dims.add(new Dimension("n_scans", 100));
                return dims;
            }
        };

        CimrDimensions dims = CimrDimensions.from(ncFile);
        dims.get("does_not_exist");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetThrowsWhenNoDimensionsPresent() {
        NetcdfFile ncFile = new NetcdfFile() {
            @Override
            public List<Dimension> getDimensions() {
                return new ArrayList<>();
            }
        };

        CimrDimensions dims = CimrDimensions.from(ncFile);
        dims.get("n_scans");
    }
}