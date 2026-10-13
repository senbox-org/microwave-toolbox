package eu.esa.snap.cimr.grid;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class GridCoverageDataSourceTest {


    @Test
    @STTM("SNAP-4262")
    public void getSample_returnsOneForCoveredPixelsAndZeroForEmptyPixels() {
        CimrGridBandDataSource source = CimrGridBandDataSource.createEmpty(3, 1);
        source.setValidSample(0, 0, 12.0);
        source.setInvalidSample(1, 0);

        GridCoverageDataSource coverage = new GridCoverageDataSource(source);

        assertEquals(1.0, coverage.getSample(0, 0), 1e-12);
        assertEquals(1.0, coverage.getSample(1, 0), 1e-12);
        assertEquals(0.0, coverage.getSample(2, 0), 1e-12);
    }

    @Test
    @STTM("SNAP-4262")
    public void coverageKeepsSourceCoverageButRendersEveryCoveragePixelAsValid() {
        CimrGridBandDataSource source = CimrGridBandDataSource.createEmpty(2, 1);
        source.setInvalidSample(0, 0);

        GridCoverageDataSource coverage = new GridCoverageDataSource(source);

        assertTrue(coverage.isCovered(0, 0));
        assertFalse(coverage.isCovered(1, 0));
        assertTrue(coverage.isValid(0, 0));
        assertTrue(coverage.isValid(1, 0));
        assertEquals(GridSampleState.VALID, coverage.getSampleState(1, 0));
    }

    @Test(expected = UnsupportedOperationException.class)
    @STTM("SNAP-4262")
    public void setSample_throwsBecauseCoverageIsReadOnly() {
        GridCoverageDataSource coverage = new GridCoverageDataSource(CimrGridBandDataSource.createEmpty(1, 1));

        coverage.setSample(0, 0, 1.0);
    }
}
