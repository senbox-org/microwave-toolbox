package eu.esa.snap.cimr.grid;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorKind;
import eu.esa.snap.cimr.dddb.descriptor.CimrFrequencyBand;
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrGridBuilderTest {

    private static final double doubleErr = 1e-6;


    @Test
    public void testBuild_usesNearestMapping() {
        GeometryBandToGridMapper mapper = new GeometryBandToGridMapper();
        CimrGridBuilder builder = new CimrGridBuilder(mapper);

        CimrBand swath = createDummySwath();
        PlateCarreeProjection proj = new PlateCarreeProjection(
                1, 1,
                0.0, 1.0,
                1.0, 1.0
        );
        CimrGrid grid =  new CimrGrid(proj, 1, 1);

        CimrGridBandDataSource target = builder.build(swath, grid);

        assertEquals(40.0, target.getSample(0, 0), doubleErr);
        assertEquals(1, target.getWidth());
        assertEquals(1, target.getHeight());
    }

    @Test
    @STTM("SNAP-4262")
    public void testBuild_usesDescriptorRasterDataTypeForTargetStorage() {
        CimrGridBuilder builder = new CimrGridBuilder(new GeometryBandToGridMapper());
        CimrBandDescriptor descriptor = new CimrBandDescriptor(
                "status", "status", CimrFrequencyBand.C_BAND,
                new String[] {"lat", "lon"}, new String[] {}, "/Data", 0,
                CimrDescriptorKind.VARIABLE, new String[] {"n_scans", "n_samples_C_BAND"},
                "int", ProductData.TYPE_INT32, "", "");
        CimrGrid grid = new CimrGrid(new PlateCarreeProjection(1, 1, 0.0, 1.0, 1.0, 1.0), 1, 1);

        CimrGridBandDataSource target = builder.build(descriptor, createDummySwath(), grid);

        assertEquals(ProductData.TYPE_INT32, target.getRasterDataType());
        assertEquals(40.0, target.getSample(0, 0), doubleErr);
    }

    private CimrBand createDummySwath() {
        return new CimrBand() {
            @Override
            public int getScanCount() {
                return 1;
            }

            @Override
            public int getSampleCount() {
                return 2;
            }

            @Override
            public double getValue(int scanIndex, int sampleIndex) {
                if (sampleIndex == 0) {
                    return 42.0;
                } else {
                    return 40.0;
                }
            }

            @Override
            public GeoPos getGeoPos(int scanIndex, int sampleIndex) {
                return new GeoPos(0.5f, 0.5f);
            }
        };
    }
}