package eu.esa.snap.cimr.grid;

import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorKind;
import eu.esa.snap.cimr.dddb.descriptor.CimrFrequencyBand;
import org.junit.Test;

import static org.junit.Assert.*;


public class CimrGridBuilderMapperSelectionTest {


    @Test
    public void build_usesMapNearest() {
        RecordingMapper mapper = new RecordingMapper();
        CimrGridBuilder builder = new CimrGridBuilder(mapper);
        CimrGrid grid = CimrGridFactory.createPlateCarreeFromBoundingBox(new CimrBoundingBox(-180.0, 180.0, -90.0, 90.0), 10.0);

        CimrGridBandDataSource result = builder.build(null, grid);

        assertTrue(mapper.mapNearestCalled);
        assertSame(grid, mapper.lastGrid);
        assertSame(result, mapper.lastTarget);
        assertNull(mapper.lastBand);
        assertNull(mapper.lastValidator);
    }

    @Test
    public void buildWithDescriptor_passesDescriptorValidatorToMapNearest() {
        RecordingMapper mapper = new RecordingMapper();
        CimrGridBuilder builder = new CimrGridBuilder(mapper);
        CimrGrid grid = CimrGridFactory.createPlateCarreeFromBoundingBox(new CimrBoundingBox(-180.0, 180.0, -90.0, 90.0), 10.0);

        builder.build(descriptorWithFillValue(), null, grid);

        assertTrue(mapper.mapNearestWithValidatorCalled);
        assertNotNull(mapper.lastValidator);
        assertFalse(mapper.lastValidator.isValid(-9999.0));
        assertTrue(mapper.lastValidator.isValid(10.0));
    }

    private static class RecordingMapper extends GeometryBandToGridMapper {
        boolean mapNearestCalled;
        boolean mapNearestWithValidatorCalled;
        CimrBand lastBand;
        CimrGrid lastGrid;
        GridBandDataSource lastTarget;
        GridSampleValidator lastValidator;

        @Override
        public void mapNearest(CimrBand band, CimrGrid grid, GridBandDataSource target) {
            mapNearestCalled = true;
            lastBand = band;
            lastGrid = grid;
            lastTarget = target;
        }

        @Override
        public void mapNearest(CimrBand band, CimrGrid grid, GridBandDataSource target, GridSampleValidator validator) {
            mapNearestWithValidatorCalled = true;
            lastBand = band;
            lastGrid = grid;
            lastTarget = target;
            lastValidator = validator;
        }
    }

    private static CimrBandDescriptor descriptorWithFillValue() {
        return new CimrBandDescriptor(
                "test",
                "test",
                CimrFrequencyBand.C_BAND,
                new String[0],
                new String[0],
                "/dummy",
                0,
                CimrDescriptorKind.VARIABLE,
                new String[0],
                "float",
                "",
                ""
        ).withFillValue(-9999.0);
    }
}