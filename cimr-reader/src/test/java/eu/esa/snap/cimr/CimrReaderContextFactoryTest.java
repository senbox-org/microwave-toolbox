package eu.esa.snap.cimr;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.cimr.dddb.CimrBandDefinition;
import eu.esa.snap.cimr.dddb.CimrDDDB;
import eu.esa.snap.cimr.dddb.CimrProductDescriptor;
import eu.esa.snap.cimr.dddb.CimrVariableFamily;
import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorSet;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorKind;
import eu.esa.snap.cimr.dddb.descriptor.CimrFrequencyBand;
import eu.esa.snap.cimr.grid.CimrGeometry;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;
import org.mockito.MockedStatic;
import ucar.ma2.ArrayFloat;
import ucar.ma2.DataType;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;


public class CimrReaderContextFactoryTest {


    @Test
    @STTM("SNAP-4262")
    public void loadDescriptorSet_usesFormatVersionFromProduct() throws IOException {
        NetcdfFile ncFile = mock(NetcdfFile.class);
        when(ncFile.findGlobalAttribute("format_version")).thenReturn(new Attribute("format_version", "1.1"));

        CimrDescriptorSet descriptorSet = CimrReaderContextFactory.loadDescriptorSet(ncFile);

        assertEquals(1275, descriptorSet.getMeasurements().size());
        assertNotNull(descriptorSet.getMeasurementByName("L_BAND_brightness_temperature_h_feed1"));
        assertNotNull(descriptorSet.getMeasurementByName("L_BAND_faraday_rot_angle_feed1"));
        assertNotNull(descriptorSet.getMeasurementByName("KU_BAND_tsu_feed8"));
        assertNotNull(descriptorSet.getMeasurementByName("C_BAND_instrument_status_feed4"));
        assertNotNull(descriptorSet.getMeasurementByName("KA_BAND_raw_counts_h_feed8"));
        assertNotNull(descriptorSet.getMeasurementByName("X_BAND_altitude_feed4"));
        assertNotNull(descriptorSet.getTpVariableByName("KA_BAND_footprint_major_axis_feed8"));
        assertNotNull(descriptorSet.getTpVariableByName("KU_BAND_direct_sun_angle_feed8"));
        assertNotNull(descriptorSet.getGeometryByName("C_BAND_longitude_feed4"));
    }

    @Test
    @STTM("SNAP-4262")
    public void loadDescriptorSet_fallsBackToDefaultDescriptorWhenFormatVersionIsMissing() throws IOException {
        NetcdfFile ncFile = mock(NetcdfFile.class);
        when(ncFile.findGlobalAttribute("format_version")).thenReturn(null);

        CimrDescriptorSet descriptorSet = CimrReaderContextFactory.loadDescriptorSet(ncFile);

        assertEquals(1275, descriptorSet.getMeasurements().size());
        assertNotNull(descriptorSet.getMeasurementByName("X_BAND_raw_brightness_temperature_h_feed4"));
    }

    @Test
    @STTM("SNAP-4262")
    public void withRasterDataTypes_usesNetcdfVariableDataType() {
        Group.Builder rootBuilder = Group.builder(null).setName("root");
        Group.Builder dataBuilder = Group.builder(rootBuilder).setName("Data");
        Group.Builder measurementBuilder = Group.builder(dataBuilder).setName("Measurement_Data");
        Group.Builder bandBuilder = Group.builder(measurementBuilder).setName("C_BAND");
        bandBuilder.addVariable(Variable.builder().setName("float_variable").setDataType(DataType.FLOAT));
        bandBuilder.addVariable(Variable.builder().setName("double_variable").setDataType(DataType.DOUBLE));
        measurementBuilder.addGroup(bandBuilder);
        dataBuilder.addGroup(measurementBuilder);
        rootBuilder.addGroup(dataBuilder);

        NetcdfFile ncFile = NetcdfFile.builder().setLocation("test").setRootGroup(rootBuilder).build();

        CimrBandDescriptor floatDescriptor = descriptor("float_band", "float_variable");
        CimrBandDescriptor doubleDescriptor = descriptor("double_band", "double_variable");
        CimrDescriptorSet descriptorSet = new CimrDescriptorSet(List.of(floatDescriptor, doubleDescriptor), List.of(), List.of());

        CimrDescriptorSet typedDescriptorSet = CimrReaderContextFactory.withRasterDataTypes(ncFile, descriptorSet);

        assertEquals(ProductData.TYPE_FLOAT32, typedDescriptorSet.getMeasurementByName("float_band").getRasterDataType());
        assertEquals(ProductData.TYPE_FLOAT64, typedDescriptorSet.getMeasurementByName("double_band").getRasterDataType());
    }

    @Test
    @STTM("SNAP-4262")
    public void create_buildsContextFromExpandedDescriptors() throws Exception {
        NetcdfFile ncFile = createMinimalL1bNetcdfFile();
        CimrDDDB dddb = mock(CimrDDDB.class);
        CimrProductDescriptor productDescriptor = productDescriptor();

        when(dddb.getProductDescriptor(eq(CimrL1BProductReader.PRODUCT_TYPE), eq("1.1"))).thenReturn(productDescriptor);
        when(dddb.getBandDefinitions(productDescriptor)).thenReturn(new CimrBandDefinition[]{bandDefinition()});
        when(dddb.getDescriptorFile(productDescriptor, "navigation.json")).thenReturn(new CimrVariableFamily[]{
                variableFamily("GEOMETRY", "latitude", "/Data/Navigation_Data/{band}/", new String[]{"n_scans", "{tiePointDimension}", "{feedDimension}"}, null),
                variableFamily("GEOMETRY", "longitude", "/Data/Navigation_Data/{band}/", new String[]{"n_scans", "{tiePointDimension}", "{feedDimension}"}, null)
        });
        when(dddb.getDescriptorFile(productDescriptor, "measurement.json")).thenReturn(new CimrVariableFamily[]{
                variableFamily("VARIABLE", "brightness_temperature_h", "/Data/Measurement_Data/{band}/", new String[]{"n_scans", "{sampleDimension}", "{feedDimension}"}, new String[]{"latitude", "longitude"})
        });

        try (MockedStatic<CimrDDDB> mockedDddb = mockStatic(CimrDDDB.class)) {
            mockedDddb.when(CimrDDDB::getInstance).thenReturn(dddb);

            CimrReaderContext context = CimrReaderContextFactory.create(ncFile);

            assertEquals("L_BAND", context.getAutoGrouping());
            assertEquals(1, context.getDescriptorSet().getMeasurements().size());
            assertEquals(2, context.getDescriptorSet().getGeometries().size());
            assertNotNull(context.getGlobalGrid());
            assertTrue(context.getGlobalGrid().getWidth() > 0);
            assertTrue(context.getGlobalGrid().getHeight() > 0);

            CimrBandDescriptor measurement = context.getDescriptorSet().getMeasurementByName("L_BAND_brightness_temperature_h_feed1");
            assertEquals(ProductData.TYPE_FLOAT32, measurement.getRasterDataType());
            CimrGeometry geometry = context.getOrCreateGeometry(measurement);
            assertEquals(2, geometry.getScanCount());
            assertEquals(2, geometry.getSampleCount());
        }
    }

    @Test
    @STTM("SNAP-4262")
    public void withRasterDataTypes_failsForUnsupportedRasterDataType() {
        Group.Builder rootBuilder = Group.builder(null).setName("root");
        Group.Builder dataBuilder = Group.builder(rootBuilder).setName("Data");
        Group.Builder measurementBuilder = Group.builder(dataBuilder).setName("Measurement_Data");
        Group.Builder bandBuilder = Group.builder(measurementBuilder).setName("C_BAND");
        bandBuilder.addVariable(Variable.builder().setName("string_variable").setDataType(DataType.STRING));
        measurementBuilder.addGroup(bandBuilder);
        dataBuilder.addGroup(measurementBuilder);
        rootBuilder.addGroup(dataBuilder);

        NetcdfFile ncFile = NetcdfFile.builder().setLocation("test").setRootGroup(rootBuilder).build();
        CimrDescriptorSet descriptorSet = new CimrDescriptorSet(List.of(descriptor("string_band", "string_variable")), List.of(), List.of());

        IllegalArgumentException actual = assertThrows(IllegalArgumentException.class, () -> CimrReaderContextFactory.withRasterDataTypes(ncFile, descriptorSet));

        assertTrue(actual.getMessage().contains("Unsupported raster data type"));
        assertTrue(actual.getMessage().contains("string_variable"));
    }

    private static CimrBandDescriptor descriptor(String name, String valueVarName) {
        return new CimrBandDescriptor(name, valueVarName, CimrFrequencyBand.C_BAND, new String[0], new String[0], "/Data/Measurement_Data/C_BAND/", 0, CimrDescriptorKind.VARIABLE, new String[0], "", "", "");
    }

    private static NetcdfFile createMinimalL1bNetcdfFile() {
        Dimension scanDim = new Dimension("n_scans", 2);
        Dimension sampleDim = new Dimension("n_samples_L_BAND", 2);
        Dimension tiePointDim = new Dimension("n_tie_points_L_BAND", 2);
        Dimension feedDim = new Dimension("n_feeds_L_BAND", 1);

        Group.Builder root = Group.builder(null).setName("root");
        root.addAttribute(new Attribute("format_version", "1.1"));
        root.addDimension(scanDim).addDimension(sampleDim).addDimension(tiePointDim).addDimension(feedDim);

        Group.Builder data = Group.builder(root).setName("Data");
        Group.Builder navigation = Group.builder(data).setName("Navigation_Data");
        Group.Builder navigationBand = Group.builder(navigation).setName("L_BAND");
        navigationBand.addVariable(Variable.builder()
                .setName("latitude")
                .setDataType(DataType.FLOAT)
                .setDimensionsByName("n_scans n_tie_points_L_BAND n_feeds_L_BAND")
                .setCachedData(data3d(2, 2, 1, new float[]{50f, 50f, 51f, 51f}), false));
        navigationBand.addVariable(Variable.builder()
                .setName("longitude")
                .setDataType(DataType.FLOAT)
                .setDimensionsByName("n_scans n_tie_points_L_BAND n_feeds_L_BAND")
                .setCachedData(data3d(2, 2, 1, new float[]{10f, 11f, 10f, 11f}), false));
        navigation.addGroup(navigationBand);

        Group.Builder measurement = Group.builder(data).setName("Measurement_Data");
        Group.Builder measurementBand = Group.builder(measurement).setName("L_BAND");
        measurementBand.addVariable(Variable.builder()
                .setName("brightness_temperature_h")
                .setDataType(DataType.FLOAT)
                .setDimensionsByName("n_scans n_samples_L_BAND n_feeds_L_BAND")
                .setCachedData(data3d(2, 2, 1, new float[]{1f, 2f, 3f, 4f}), false));
        measurement.addGroup(measurementBand);

        data.addGroup(navigation);
        data.addGroup(measurement);
        root.addGroup(data);

        return NetcdfFile.builder().setLocation("test").setRootGroup(root).build();
    }

    private static ArrayFloat.D3 data3d(int scans, int samples, int feeds, float[] values) {
        ArrayFloat.D3 data = new ArrayFloat.D3(scans, samples, feeds);
        int index = 0;
        for (int scan = 0; scan < scans; scan++) {
            for (int sample = 0; sample < samples; sample++) {
                for (int feed = 0; feed < feeds; feed++) {
                    data.set(scan, sample, feed, values[index++]);
                }
            }
        }
        return data;
    }

    private static CimrProductDescriptor productDescriptor() {
        CimrProductDescriptor productDescriptor = new CimrProductDescriptor();
        productDescriptor.setProductType(CimrL1BProductReader.PRODUCT_TYPE);
        productDescriptor.setVersion("1.1");
        productDescriptor.setBandsFile("bands.json");
        productDescriptor.setDescriptorFiles(new String[]{"navigation.json", "measurement.json"});
        productDescriptor.setAutoGrouping("L_BAND");
        return productDescriptor;
    }

    private static CimrBandDefinition bandDefinition() {
        CimrBandDefinition bandDefinition = new CimrBandDefinition();
        bandDefinition.setName("L_BAND");
        bandDefinition.setFeedCount(1);
        bandDefinition.setFeedDimension("n_feeds_L_BAND");
        bandDefinition.setSampleDimension("n_samples_L_BAND");
        bandDefinition.setTiePointDimension("n_tie_points_L_BAND");
        return bandDefinition;
    }

    private static CimrVariableFamily variableFamily(String kind, String valueVarName, String groupPath, String[] dimensions, String[] geometryVariables) {
        CimrVariableFamily family = new CimrVariableFamily();
        family.setKind(kind);
        family.setValueVarName(valueVarName);
        family.setGroupPath(groupPath);
        family.setDimensions(dimensions);
        family.setDataType("float");
        family.setUnit("");
        family.setDescription(valueVarName);
        family.setGeometryVariables(geometryVariables == null ? new String[0] : geometryVariables);
        family.setExpandFeeds(true);
        return family;
    }
}
