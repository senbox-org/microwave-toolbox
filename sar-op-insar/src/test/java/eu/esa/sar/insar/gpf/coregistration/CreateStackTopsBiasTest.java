package eu.esa.sar.insar.gpf.coregistration;

import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for the TOPS bias helpers in {@link CreateStackOp}: ESD overall-shift extraction
 * and the ESD->GSLC sign mapping. Deterministic, no I/O.
 */
public class CreateStackTopsBiasTest {

    /** Build a product whose abstracted metadata carries an ESD Overall_Range_Azimuth_Shift. */
    private static Product productWithEsdShift(double rg, double az) {
        final Product p = new Product("esd", "type", 2, 2);
        final MetadataElement abs = AbstractMetadata.addAbstractedMetadataHeader(p.getMetadataRoot());
        final MetadataElement esd = new MetadataElement("ESD Measurement");
        final MetadataElement pair = new MetadataElement("mst_slv");
        final MetadataElement overall = new MetadataElement("Overall_Range_Azimuth_Shift");
        final MetadataElement sw = new MetadataElement("IW1");
        sw.setAttributeDouble("rangeShift", rg);
        sw.setAttributeDouble("azimuthShift", az);
        overall.addElement(sw);
        pair.addElement(overall);
        esd.addElement(pair);
        abs.addElement(esd);
        return p;
    }

    @Test
    public void testReadEsdOverallShift_extractsRangeAndAzimuth() {
        final Product p = productWithEsdShift(0.37, -10.5);
        final double[] s = CreateStackOp.readEsdOverallShift(p);
        assertEquals(0.37, s[0], 1e-9);
        assertEquals(-10.5, s[1], 1e-9);
    }

    @Test
    public void testEsdShiftToGslcOffset_signConvention() {
        // First increment: identity mapping (ESD residual == GSLC offset). The integration
        // coherence A/B confirms direction; flip here if coherence drops with the bias.
        final double[] off = CreateStackOp.esdShiftToGslcOffset(new double[]{0.37, -10.5});
        assertEquals(0.37, off[0], 1e-9);
        assertEquals(-10.5, off[1], 1e-9);
    }
}
