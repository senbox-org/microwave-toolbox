package eu.esa.sar.insar.gpf.ui;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TestMultiReferenceInSARUI {

    @STTM("SNAP-3823")
    @Test
    public void test_getDate() {
        final String bandName = "secondary_08Aug2016";
        final String date = MultiMasterInSAROpUI.extractDate(bandName);
        assertEquals("08Aug2016", date);
    }
}
