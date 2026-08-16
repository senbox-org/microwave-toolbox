package eu.esa.sar.insar.gpf.coregistration;

import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.Product;
import org.junit.Test;

import java.io.File;

import static org.junit.Assume.assumeTrue;

/** Throwaway diagnostic: print the exact ERS offset-field coefficients (file-gated). */
public class TestPrintErsField {
    @Test
    public void printField() throws Exception {
        final File m = new File("E:/Output/ers/ERS-1_SAR_SLC-ORBIT_21159_DATE__1-AUG-1995_21_16_39_Orb.dim");
        final File s = new File("E:/Output/ers/ERS-2_SAR_SLC-ORBIT_1486_DATE__2-AUG-1995_21_16_42_Orb.dim");
        assumeTrue(m.exists() && s.exists());
        try (Product mp = ProductIO.readProduct(m); Product sp = ProductIO.readProduct(s)) {
            final CreateStackOp.SlcBiasEstimate est = CreateStackOp.estimateSlcBiasByBlocks(mp, sp);
            System.out.println("FIELD-RG: " + (est.rangePoly == null ? "null"
                    : CreateStackOp.joinCoefficients(est.rangePoly)));
            System.out.println("FIELD-AZ: " + (est.azimuthPoly == null ? "null"
                    : CreateStackOp.joinCoefficients(est.azimuthPoly)));
            System.out.println("MEDIANS: " + est.dRange + " / " + est.dAzimuth);
        }
    }
}
