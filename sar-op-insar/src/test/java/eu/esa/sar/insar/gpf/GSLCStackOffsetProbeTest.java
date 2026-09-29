package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assume.assumeTrue;

/**
 * Does the geocoded stack actually APPLY the integer pixel offset it records?
 * <p>
 * The two GSLC fixtures share one lattice but their origins differ by a whole number of pixels.
 * CreateStack writes that offset into {@code Orbit_Offsets/init_offset_X|Y}. This reads the
 * stack's secondary band back and checks which shift of the original secondary product it
 * actually contains — the recorded offset, or none at all.
 */
public class GSLCStackOffsetProbeTest {

    private static final File DIR = new File(System.getProperty("gslc.diagDir", "E:/Output/gslcdiag"));

    @Test
    public void probeAppliedStackOffset() throws Exception {
        final File fa = new File(DIR, "mg.dim");
        final File fb = new File(DIR, "sg_lock.dim");
        assumeTrue("GSLC fixtures not present", fa.exists() && fb.exists());

        final Product pa = ProductIO.readProduct(fa);   // S1A 23Jun
        final Product pb = ProductIO.readProduct(fb);   // S1D 30Jun

        final Map<String, Object> cs = new HashMap<>();
        cs.put("extent", "Master");
        cs.put("resamplingType", "NONE");
        cs.put("autoCoregisterGSLC", false);
        cs.put("skipBiasEstimation", true);
        final Product stack = GPF.createProduct("CreateStack", cs, new Product[]{pa, pb});

        System.out.println("OFFPROBE stack bands = " + String.join(",", stack.getBandNames()));
        final MetadataElement oo = AbstractMetadata.getAbstractedMetadata(stack).getElement("Orbit_Offsets");
        if (oo != null) {
            for (final MetadataElement e : oo.getElements()) {
                System.out.println("OFFPROBE recorded " + e.getName()
                        + "  X=" + e.getAttributeInt("init_offset_X", 999)
                        + "  Y=" + e.getAttributeInt("init_offset_Y", 999));
            }
        }

        // the stack band belonging to 23Jun (the secondary, since CreateStack made 30Jun reference)
        Band stackSec = null;
        for (final Band b : stack.getBands()) {
            if (b.getName().startsWith("i_") && b.getName().contains("23Jun2026")) { stackSec = b; break; }
        }
        assert stackSec != null;
        System.out.println("OFFPROBE stack secondary band = " + stackSec.getName());

        final Band origSec = pa.getBand("i_IW3_VV");
        final int x0 = 2592, y0 = 900, N = 64;
        final float[] fromStack = new float[N * N];
        stackSec.readPixels(x0, y0, N, N, fromStack);

        // Which shift of the original reproduces the stack content? The recorded offset must —
        // and the unshifted copy must NOT (that was the bug: offset recorded, never applied).
        final org.esa.snap.core.datamodel.MetadataElement offsets =
                AbstractMetadata.getAbstractedMetadata(stack).getElement("Orbit_Offsets");
        org.junit.Assert.assertNotNull("Orbit_Offsets missing", offsets);
        final int offX = offsets.getElements()[0].getAttributeInt("init_offset_X");
        final int offY = offsets.getElements()[0].getAttributeInt("init_offset_Y");
        org.junit.Assert.assertTrue("fixture should have a non-zero offset, got (" + offX + "," + offY + ")",
                offX != 0 || offY != 0);

        System.out.println("OFFPROBE  shift      maxAbsDiff");
        double dAtRecorded = Double.NaN, dAtZero = Double.NaN;
        for (final int[] s : new int[][]{{0, 0}, {offX, offY}}) {
            final int sx = x0 + s[0], sy = y0 + s[1];
            if (sx < 0 || sy < 0 || sx + N > origSec.getRasterWidth() || sy + N > origSec.getRasterHeight()) continue;
            final float[] orig = new float[N * N];
            origSec.readPixels(sx, sy, N, N, orig);
            double maxd = 0;
            for (int i = 0; i < orig.length; i++) maxd = Math.max(maxd, Math.abs(orig[i] - fromStack[i]));
            System.out.printf("OFFPROBE  (%+d,%+d)   %12.6f%n", s[0], s[1], maxd);
            if (s[0] == offX && s[1] == offY) dAtRecorded = maxd;
            else dAtZero = maxd;
        }
        org.junit.Assert.assertEquals("stack secondary must be bit-identical to the source " +
                "shifted by the RECORDED offset (" + offX + "," + offY + ")", 0.0, dAtRecorded, 0.0);
        org.junit.Assert.assertTrue("stack secondary must NOT equal the unshifted source " +
                "(the recorded offset would have been silently dropped)", dAtZero > 0.0);

        stack.dispose();
        pa.dispose();
        pb.dispose();
    }
}
