package eu.esa.snap.cimr.dddb;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class CimrVariableFamilyTest {


    @Test
    @STTM("SNAP-4262")
    public void setExpandFeeds_updatesExpandFeeds() {
        CimrVariableFamily family = new CimrVariableFamily();

        assertTrue(family.isExpandFeeds());

        family.setExpandFeeds(false);
        assertFalse(family.isExpandFeeds());

        family.setExpandFeeds(true);
        assertTrue(family.isExpandFeeds());
    }
}
