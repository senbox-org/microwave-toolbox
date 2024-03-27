package eu.esa.sar.io.nisar.subreaders;

import com.bc.ceres.core.ProgressMonitor;
import eu.esa.sar.io.netcdf.NetCDFReader;
import eu.esa.sar.io.netcdf.NetcdfConstants;
import eu.esa.sar.io.nisar.util.NisarXConstants;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class NisarSubReader {

    protected final Map<Band, Variable> bandMap = new HashMap<>();
    protected final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    protected NetcdfFile netcdfFile = null;
    protected Product product = null;
    protected boolean isComplex = true;

    public void close() throws IOException {
        if (netcdfFile != null) {
            netcdfFile.close();
            netcdfFile = null;
        }
    }

    public abstract Product readProduct(final ProductReader reader, final NetcdfFile netcdfFile, final File inputFile);

    public abstract void readBandRasterDataImpl(int sourceOffsetX, int sourceOffsetY, int sourceWidth, int sourceHeight,
                                int sourceStepX, int sourceStepY, Band destBand, int destOffsetX,
                                int destOffsetY, int destWidth, int destHeight, ProductData destBuffer,
                                ProgressMonitor pm) throws IOException;


    protected static String getPolarization(final Product product, NetcdfFile netcdfFile) {

        final MetadataElement globalElem = AbstractMetadata.getOriginalProductMetadata(product).getElement(
                NetcdfConstants.GLOBAL_ATTRIBUTES_NAME);

        try {
            if (globalElem != null) {
                final String polStr = netcdfFile.getRootGroup().findVariable(NisarXConstants.MDS1_TX_RX_POLAR).
                        readScalarString();

                if (!polStr.isEmpty())
                    return polStr;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
        return null;
    }

    protected static void createUniqueBandName(final Product product, final Band band, final String origName) {

        int cnt = 1;
        band.setName(origName);
        while (product.getBand(band.getName()) != null) {
            band.setName(origName + cnt);
            ++cnt;
        }
    }

    protected Group findGroup(final String groupName, final List<Group> groups) {

        for (Group group : groups) {
            final String name = group.getName();
            if (group.getName().equals(groupName)) {
                return group;
            }
        }
        return null;
    }

    protected String getSampleType() {

        try {
            if (NisarXConstants.SLC.equalsIgnoreCase(netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.SPH_DESCRIPTOR).readScalarString())) {

                isComplex = true;
                return NisarXConstants.COMPLEX;
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        isComplex = false;
        return NisarXConstants.DETECTED;
    }

    protected double timeUTCtoSecs(String myDate) {

        ProductData.UTC localDateTime = null;
        try {
            localDateTime = ProductData.UTC.parse(myDate, standardDateFormat);
        } catch (ParseException e) {
            SystemUtils.LOG.severe(e.getMessage());
        }
        return localDateTime.getMJD() * 24.0 * 3600.0;
    }

    protected void addOrbitStateVectors(final MetadataElement absRoot) {

        try {
            final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);

            final int numPoints = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.NUMBER_OF_STATE_VECTORS).readScalarInt();

            char[] stateVectorTime = (char[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.STATE_VECTOR_TIME).read().getStorage();

            int utcDimension = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.STATE_VECTOR_TIME).getDimension(2).getLength();

            final double[] satellitePositionX = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_X_POS).read().getStorage();

            final double[] satellitePositionY = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Y_POS).read().getStorage();

            final double[] satellitePositionZ = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Z_POS).read().getStorage();

            final double[] satelliteVelocityX = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_X_VEL).read().getStorage();

            final double[] satelliteVelocityY = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Y_VEL).read().getStorage();

            final double[] satelliteVelocityZ = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.ORBIT_VECTOR_N_Z_VEL).read().getStorage();

            int start = 0;
            String utc = new String(Arrays.copyOfRange(stateVectorTime, 0, utcDimension - 1));
            ProductData.UTC stateVectorUTC = ProductData.UTC.parse(utc, standardDateFormat);

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, stateVectorUTC);

            for (int i = 0; i < numPoints; i++) {
                utc = new String(Arrays.copyOfRange(stateVectorTime, start, start + utcDimension - 1));
                ProductData.UTC vectorUTC = ProductData.UTC.parse(utc, standardDateFormat);

                final MetadataElement orbitVectorElem = new MetadataElement(AbstractMetadata.orbit_vector + (i + 1));
                orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time, vectorUTC);

                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos, satellitePositionX[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos, satellitePositionY[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos, satellitePositionZ[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel, satelliteVelocityX[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel, satelliteVelocityY[i]);
                orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel, satelliteVelocityZ[i]);

                orbitVectorListElem.addElement(orbitVectorElem);
                start += utcDimension;
            }
        } catch (IOException | ParseException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }

    protected MetadataElement getBandElement(final Band band) {

        final MetadataElement root = AbstractMetadata.getOriginalProductMetadata(product);
        final Variable variable = bandMap.get(band);
        final String varName = variable.getShortName();
        MetadataElement bandElem = null;
        for (MetadataElement elem : root.getElements()) {
            if (elem.getName().equalsIgnoreCase(varName)) {
                bandElem = elem;
                break;
            }
        }
        return bandElem;
    }

    protected void addGeoCodingToProduct() throws IOException {

        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setTiePointGeoCoding(product);
        }

        if (product.getSceneGeoCoding() == null) {
            NetCDFReader.setPixelGeoCoding(product);
        }

    }

    protected void addDopplerCentroidCoefficients() {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);
        final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + ".1");
        dopplerCentroidCoefficientsElem.addElement(dopplerListElem);

        final ProductData.UTC utcTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time,
                AbstractMetadata.NO_METADATA_UTC);

        dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);

        AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");

        AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, 0.0);

        try {

            int dimensionColumn = netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.DC_ESTIMATE_COEFFS).getDimension(1).getLength();

            double[] coefValueS = (double[]) netcdfFile.getRootGroup().findVariable(
                    NisarXConstants.DC_ESTIMATE_COEFFS).read().getStorage();

            for (int i = 0; i < dimensionColumn; i++) {
                final double coefValue = coefValueS[i];

                final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + (i + 1));
                dopplerListElem.addElement(coefElem);

                AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,
                        ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");

                AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, coefValue);
            }
        } catch (IOException e) {
            SystemUtils.LOG.severe(e.getMessage());

        }
    }
}
