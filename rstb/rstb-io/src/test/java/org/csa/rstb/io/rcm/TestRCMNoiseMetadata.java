package org.csa.rstb.io.rcm;

import com.bc.ceres.test.LongTestRunner;
import eu.esa.sar.commons.test.TestData;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

/**
 * Noise level files are delivered per polarisation (noiseLevels_HH.xml, ...); the reader must keep
 * them apart so thermal-noise removal can pick the right polarisation.
 */
@RunWith(LongTestRunner.class)
public class TestRCMNoiseMetadata {

    private static final File SC50 = new File(TestData.inputSAR +
            "RCM/EODMS/GRD/RCM1_OK1024567_PK1025865_1_SC50MA_20191205_225938_HH_HV_GRD/manifest.safe");

    @Test
    public void noiseFilesAreKeptPerPolarisation() throws Exception {
        assumeTrue(SC50.exists());
        final Product p = ProductIO.readProduct(SC50);
        try {
            final MetadataElement orig = AbstractMetadata.getOriginalProductMetadata(p);
            final MetadataElement cal = orig.getElement("calibration");
            final MetadataElement hh = cal.getElement("noiseLevels_HH");
            final MetadataElement hv = cal.getElement("noiseLevels_HV");
            assertNotNull(hh);
            assertNotNull(hv);
            assertNull("no merged noiseLevels element", cal.getElement("noiseLevels"));
            assertNull("RCM has no product-level noiseLevels.xml", orig.getElement("noiseLevels"));

            // values from metadata/calibration/noiseLevels_POL.xml of this product
            assertNoiseFile(hh, -28.15288340167191);
            assertNoiseFile(hv, -28.64202191893802);
        } finally {
            p.dispose();
        }
    }

    /** 3 reference vectors (Beta Nought first), 8 beams x 3 per-beam vectors, 8 azimuth scalings. */
    private static void assertNoiseFile(final MetadataElement noise, final double firstBetaValue) {
        final MetadataElement[] refs = noise.getElements();
        int nRef = 0, nPerBeam = 0, nAz = 0;
        MetadataElement firstRef = null;
        for (MetadataElement e : refs) {
            switch (e.getName()) {
                case "referenceNoiseLevel":
                    if (firstRef == null) {
                        firstRef = e;
                    }
                    nRef++;
                    break;
                case "perBeamReferenceNoiseLevel":
                    nPerBeam++;
                    break;
                case "azimuthNoiseLevelScaling":
                    nAz++;
                    break;
                default:
                    break;
            }
        }
        assertEquals(3, nRef);
        assertEquals(24, nPerBeam);
        assertEquals(8, nAz);
        assertNotNull(firstRef);
        assertEquals("Beta Nought", firstRef.getAttributeString("sarCalibrationType"));
        assertEquals("23", firstRef.getAttributeString("stepSize").trim());
        assertEquals("782", firstRef.getAttributeString("numberOfValues").trim());
        final MetadataElement child = firstRef.getElement("noiseLevelValues");
        final String values = child != null ? child.getAttributeString("noiseLevelValues")
                : firstRef.getAttributeString("noiseLevelValues");
        final String[] tok = values.trim().split("\\s+");
        assertEquals(782, tok.length);
        assertEquals(firstBetaValue, Double.parseDouble(tok[0]), 1e-9);
    }
}
