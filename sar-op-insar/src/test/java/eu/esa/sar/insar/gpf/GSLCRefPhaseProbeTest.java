package eu.esa.sar.insar.gpf;

import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.dataio.ProductIO;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.eo.Constants;
import org.jlinda.core.Ellipsoid;
import org.jlinda.core.Orbit;
import org.jlinda.core.Point;
import org.jlinda.core.SLCImage;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assume.assumeTrue;

/**
 * Probe for the GSLC reference-phase computation in {@code InterferogramOp}.
 * <p>
 * Replicates exactly what {@code setupGSLCReferencePhase} / {@code computeGslcReferencePhase} do —
 * build an {@link SLCImage} + {@link Orbit} for the reference and the secondary from the stack
 * metadata, then solve the one-way range time to a ground point with {@link Orbit#xyz2t} — and
 * prints the resulting ranges and phases along a row of nodes.
 * <p>
 * Expected for a healthy geometry: {@code R_ref} and {@code R_sec} both ~9.3e5 m, their difference
 * a smooth function of position (tens of metres), and the phase stepping by only a few radians
 * between nodes 10 px apart. Anything else localises the fault.
 */
public class GSLCRefPhaseProbeTest {

    private static final File DIR = new File(System.getProperty("gslc.diagDir", "E:/Output/gslcdiag"));

    @Test
    public void probeReferencePhaseNodes() throws Exception {
        final File a = new File(DIR, "mg.dim");
        final File b = new File(DIR, "sg_lock.dim");
        assumeTrue("GSLC fixtures not present", a.exists() && b.exists());

        final Product pa = ProductIO.readProduct(a);
        final Product pb = ProductIO.readProduct(b);

        final Map<String, Object> cs = new HashMap<>();
        cs.put("extent", "Master");
        cs.put("resamplingType", "NONE");
        cs.put("autoCoregisterGSLC", false);
        cs.put("skipBiasEstimation", true);
        final Product stack = GPF.createProduct("CreateStack", cs, new Product[]{pa, pb});

        final MetadataElement refAbs = AbstractMetadata.getAbstractedMetadata(stack);
        final SLCImage refSLC = new SLCImage(refAbs, stack);
        final Orbit refOrbit = new Orbit(refAbs, 3);

        final MetadataElement secRoot = AbstractMetadata.getSecondaryMetadata(stack.getMetadataRoot());
        final MetadataElement secAbs = secRoot.getElements()[0];
        final SLCImage secSLC = new SLCImage(secAbs, stack);
        final Orbit secOrbit = new Orbit(secAbs, 3);

        System.out.println("PROBE ref  product = " + refAbs.getAttributeString("PRODUCT", "?"));
        System.out.println("PROBE sec  product = " + secAbs.getAttributeString("PRODUCT", "?"));
        System.out.println("PROBE ref  wavelength = " + refSLC.getRadarWavelength()
                + "   sec wavelength = " + secSLC.getRadarWavelength());
        System.out.println("PROBE stack raster = " + stack.getSceneRasterWidth() + " x "
                + stack.getSceneRasterHeight());

        // Same DEM the operator uses, so the only remaining difference from the in-operator run
        // is concurrency.
        final org.esa.snap.core.dataop.dem.ElevationModel dem =
                org.esa.snap.dem.dataio.DEMFactory.createElevationModel("Copernicus 30m Global DEM",
                        org.esa.snap.core.dataop.resamp.ResamplingFactory.BILINEAR_INTERPOLATION_NAME);
        final double demNoData = dem.getDescriptor().getNoDataValue();

        final double phaseFactor = -4.0 * Math.PI / secSLC.getRadarWavelength();
        final GeoPos geo = new GeoPos();
        double prev = Double.NaN;
        System.out.println("PROBE   px      lat        lon      height(m)     R_ref(m)        R_sec(m)     dR(m)      phase(rad)   dPhase");
        for (int k = 0; k < 12; k++) {
            final int px = 2592 + k * 10;
            final int py = 900;
            stack.getSceneGeoCoding().getGeoPos(new PixelPos(px + 0.5, py + 0.5), geo);
            double height = 0.0;
            try {
                final double e = dem.getElevation(geo);
                if (!Double.isNaN(e) && e != demNoData) height = e;
            } catch (Exception ignore) {
                height = 0.0;
            }
            final Point xyz = Ellipsoid.ell2xyz(Math.toRadians(geo.lat), Math.toRadians(geo.lon), height);
            final double tRef = refOrbit.xyz2t(xyz, refSLC).x;
            final double tSec = secOrbit.xyz2t(xyz, secSLC).x;
            final double rRef = Constants.lightSpeed * tRef;
            final double rSec = Constants.lightSpeed * tSec;
            final double phase = -(phaseFactor * Constants.lightSpeed * (tSec - tRef));
            System.out.printf("PROBE %5d  %9.5f  %10.5f  %9.2f  %14.4f  %14.4f  %9.4f  %13.4f  %s%n",
                    px, geo.lat, geo.lon, height, rRef, rSec, rSec - rRef, phase,
                    Double.isNaN(prev) ? "-" : String.format("%+.4f", phase - prev));
            prev = phase;
        }

        stack.dispose();
        pa.dispose();
        pb.dispose();
    }
}
