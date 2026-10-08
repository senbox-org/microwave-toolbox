package eu.esa.snap.cimr.grid;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;

import static org.junit.Assert.assertEquals;


public class TiepointInterpolatorTest {


    private static final double EPS = 1e-6;


    @Test
    @STTM("SNAP-4262")
    public void position_mapsSampleToTiepointInterval() {
        TiepointInterpolator.Position start = TiepointInterpolator.position(0, 4, 2);
        TiepointInterpolator.Position middle = TiepointInterpolator.position(2, 4, 2);
        TiepointInterpolator.Position end = TiepointInterpolator.position(3, 4, 2);

        assertEquals(0, start.getLowerIndex());
        assertEquals(1, start.getUpperIndex());
        assertEquals(0.0, start.getFraction(), EPS);

        assertEquals(0, middle.getLowerIndex());
        assertEquals(1, middle.getUpperIndex());
        assertEquals(2.0 / 3.0, middle.getFraction(), EPS);

        assertEquals(1, end.getLowerIndex());
        assertEquals(1, end.getUpperIndex());
        assertEquals(0.0, end.getFraction(), EPS);
    }

    @Test
    @STTM("SNAP-4262")
    public void interpolate_returnsLinearValue() {
        assertEquals(6.666666, TiepointInterpolator.interpolate(0.0, 10.0, 2.0 / 3.0), EPS);
    }

    @Test
    @STTM("SNAP-4262")
    public void interpolate_returnsLinearGeoPos() {
        GeoPos pos = TiepointInterpolator.interpolate(new GeoPos(50f, 0f), new GeoPos(60f, 10f), 0.25);

        assertEquals(52.5, pos.getLat(), EPS);
        assertEquals(2.5, pos.getLon(), EPS);
    }

    @Test(expected = IllegalArgumentException.class)
    @STTM("SNAP-4262")
    public void position_failsWhenSampleIndexOutOfRange() {
        TiepointInterpolator.position(4, 4, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    @STTM("SNAP-4262")
    public void position_failsWhenSampleCountTooSmall() {
        TiepointInterpolator.position(0, 1, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    @STTM("SNAP-4262")
    public void position_failsWhenNoTiepoints() {
        TiepointInterpolator.position(0, 2, 0);
    }
}