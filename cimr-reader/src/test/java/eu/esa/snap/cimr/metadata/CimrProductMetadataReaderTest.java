package eu.esa.snap.cimr.metadata;

import com.bc.ceres.annotation.STTM;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.nc2.Attribute;
import ucar.nc2.AttributeContainerMutable;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.util.Collections;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class CimrProductMetadataReaderTest {


    @Test
    @STTM("SNAP-4262")
    public void read_usesProductNameAttributeWhenPresent() throws Exception {
        NetcdfFile ncFile = emptyNcFile();
        when(ncFile.findGlobalAttribute("product_name")).thenReturn(new Attribute("product_name", " CIMR_TEST_PRODUCT "));

        CimrProductMetadata metadata = CimrProductMetadataReader.read(ncFile, "fallback.nc", "CIMR_L1B");

        assertEquals("CIMR_TEST_PRODUCT", metadata.getProductName());
        assertEquals("CIMR_L1B", metadata.getProductType());
        assertEquals("CIMR_Metadata", metadata.getMetadataElement().getName());
    }

    @Test
    @STTM("SNAP-4262")
    public void read_usesFileNameWithoutExtensionWhenProductNameAttributeIsMissing() throws Exception {
        NetcdfFile ncFile = emptyNcFile();
        when(ncFile.findGlobalAttribute("product_name")).thenReturn(null);

        CimrProductMetadata metadata = CimrProductMetadataReader.read(ncFile, "W_PT-DME-Lisbon-SAT-CIMR-1B_C_DME_20260921T093512.nc", "CIMR_L1B");

        assertEquals("W_PT-DME-Lisbon-SAT-CIMR-1B_C_DME_20260921T093512", metadata.getProductName());
        assertEquals("CIMR_L1B", metadata.getProductType());
    }

    @Test
    @STTM("SNAP-4262")
    public void read_usesFileNameWithoutExtensionWhenProductNameAttributeIsBlank() throws Exception {
        NetcdfFile ncFile = emptyNcFile();
        when(ncFile.findGlobalAttribute("product_name")).thenReturn(new Attribute("product_name", " "));

        CimrProductMetadata metadata = CimrProductMetadataReader.read(ncFile, "test-product.nc", "CIMR_L1B");

        assertEquals("test-product", metadata.getProductName());
        assertEquals("CIMR_L1B", metadata.getProductType());
    }

    @Test
    @STTM("SNAP-4262")
    public void read_addsGlobalAttributes() throws Exception {
        NetcdfFile ncFile = ncFileWithRootGroups(Collections.emptyList());
        when(ncFile.getGlobalAttributes()).thenReturn(List.of(
                new Attribute("_NCProperties", "version=2"),
                new Attribute("format_version", "1.1"),
                new Attribute("processor_version", "GPP_2.0.0"),
                new Attribute("Data_Measurement_Data_L_Band_central_frequency", 1.4135)
        ));

        MetadataElement globalAttributes = CimrProductMetadataReader.read(ncFile, "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Global_Attributes");

        assertEquals("version=2", globalAttributes.getAttributeString("_NCProperties"));
        assertEquals("1.1", globalAttributes.getAttributeString("format_version"));
        assertEquals("GPP_2.0.0", globalAttributes.getAttributeString("processor_version"));
        assertEquals(1.4135, globalAttributes.getAttributeDouble("Data_Measurement_Data_L_Band_central_frequency"), 1e-8);
    }

    @Test
    @STTM("SNAP-4262")
    public void read_mirrorsGroupAttributesAndScalarVariables() throws Exception {
        Variable creationTime = scalarVariable("creation_time_utc", DataType.DOUBLE, new double[]{42.0}, List.of(new Attribute("units", "seconds since 2028-01-01 00:00:00.00")));
        Variable semiMajorAxis = scalarVariable("semi_major_axis", DataType.DOUBLE, new double[]{7200000.0}, List.of(new Attribute("units", "m")));

        Group processing = group("Processing", "/Status/Processing", Collections.emptyList(), List.of(creationTime), Collections.emptyList());
        Group satellite = group("Satellite", "/Status/Satellite", List.of(new Attribute("group_attribute", "satellite metadata")), List.of(semiMajorAxis), Collections.emptyList());
        Group status = group("Status", "/Status", Collections.emptyList(), Collections.emptyList(), List.of(processing, satellite));

        MetadataElement statusElement = CimrProductMetadataReader.read(ncFileWithRootGroups(List.of(status)), "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Groups")
                .getElement("Status");

        MetadataElement processingVariable = statusElement
                .getElement("Processing")
                .getElement("Variables")
                .getElement("creation_time_utc");
        verify(creationTime, never()).read();
        assertEquals(42.0, processingVariable.getAttributeDouble("value"), 1e-8);
        verify(creationTime).read();
        assertEquals("seconds since 2028-01-01 00:00:00.00", processingVariable.getAttributeString("units"));

        MetadataElement satelliteElement = statusElement.getElement("Satellite");
        assertEquals("satellite metadata", satelliteElement.getElement("Attributes").getAttributeString("group_attribute"));
        MetadataElement semiMajorAxisVariable = satelliteElement
                .getElement("Variables")
                .getElement("semi_major_axis");
        verify(semiMajorAxis, never()).read();
        assertEquals(7200000.0, semiMajorAxisVariable.getAttributeDouble("value"), 1e-8);
        verify(semiMajorAxis).read();
        assertEquals("m", semiMajorAxisVariable.getAttributeString("units"));
    }

    @Test
    @STTM("SNAP-4262")
    public void read_ignoresNonMetadataNonScalarVariablesAndEmptyGroups() throws Exception {
        Variable brightnessTemperature = variable("brightness_temperature_h", 1, DataType.DOUBLE, new double[]{1.0, 2.0}, Collections.emptyList());
        Group lBand = group("L_Band", "/Data/Measurement_Data/L_Band", Collections.emptyList(), List.of(brightnessTemperature), Collections.emptyList());
        Group measurement = group("Measurement_Data", "/Data/Measurement_Data", Collections.emptyList(), Collections.emptyList(), List.of(lBand));
        Group data = group("Data", "/Data", Collections.emptyList(), Collections.emptyList(), List.of(measurement));

        MetadataElement dataElement = CimrProductMetadataReader.read(ncFileWithRootGroups(List.of(data)), "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Groups")
                .getElement("Data");

        assertNull(dataElement);
        verify(brightnessTemperature, never()).read();
    }

    @Test
    @STTM("SNAP-4262")
    public void read_addsNonScalarAuxMetadataVariablesLazily() throws Exception {
        Variable acqTime = variable("acq_time_utc", 2, DataType.DOUBLE, new double[]{1.0, 2.0, 3.0, 4.0}, Collections.emptyList());
        Group lBand = group("L_Band", "/Data/Measurement_Data/L_Band", Collections.emptyList(), List.of(acqTime), Collections.emptyList());
        Group measurement = group("Measurement_Data", "/Data/Measurement_Data", Collections.emptyList(), Collections.emptyList(), List.of(lBand));
        Group data = group("Data", "/Data", Collections.emptyList(), Collections.emptyList(), List.of(measurement));

        MetadataElement acqTimeMetadata = CimrProductMetadataReader.read(ncFileWithRootGroups(List.of(data)), "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Groups")
                .getElement("Data")
                .getElement("Measurement_Data")
                .getElement("L_Band")
                .getElement("Variables")
                .getElement("acq_time_utc");

        verify(acqTime, never()).read();
        assertArrayEquals(new double[]{1.0, 2.0, 3.0, 4.0}, (double[]) acqTimeMetadata.getAttribute("value").getData().getElems(), 1e-8);
        verify(acqTime).read();
    }

    @Test
    @STTM("SNAP-4262")
    public void read_addsComponentAuxMetadataVariablesLazily() throws Exception {
        Variable matrix = variable("boresight2AntennaPlane", 4, DataType.DOUBLE, new double[]{1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0}, Collections.emptyList());
        Variable thermistors = variable("thermistor_counts", 3, DataType.INT, new int[]{1, 2, 3, 4}, Collections.emptyList());

        Group navBand = group("L_Band", "/Data/Navigation_Data/L_Band", Collections.emptyList(), List.of(matrix), Collections.emptyList());
        Group navigation = group("Navigation_Data", "/Data/Navigation_Data", Collections.emptyList(), Collections.emptyList(), List.of(navBand));
        Group calBand = group("L_Band", "/Data/Calibration_Data/L_Band", Collections.emptyList(), List.of(thermistors), Collections.emptyList());
        Group calibration = group("Calibration_Data", "/Data/Calibration_Data", Collections.emptyList(), Collections.emptyList(), List.of(calBand));
        Group data = group("Data", "/Data", Collections.emptyList(), Collections.emptyList(), List.of(navigation, calibration));

        MetadataElement dataElement = CimrProductMetadataReader.read(ncFileWithRootGroups(List.of(data)), "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Groups")
                .getElement("Data");
        MetadataElement matrixMetadata = dataElement
                .getElement("Navigation_Data")
                .getElement("L_Band")
                .getElement("Variables")
                .getElement("boresight2AntennaPlane");
        MetadataElement thermistorMetadata = dataElement
                .getElement("Calibration_Data")
                .getElement("L_Band")
                .getElement("Variables")
                .getElement("thermistor_counts");

        verify(matrix, never()).read();
        verify(thermistors, never()).read();

        assertArrayEquals(new double[]{1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0}, (double[]) matrixMetadata.getAttribute("value").getData().getElems(), 1e-8);
        assertArrayEquals(new int[]{1, 2, 3, 4}, (int[]) thermistorMetadata.getAttribute("value").getData().getElems());

        verify(matrix).read();
        verify(thermistors).read();
    }

    @Test
    @STTM("SNAP-4262")
    public void read_excludesQualityAndProcessingFlagVariables() throws Exception {
        Variable durationOfProduct = scalarVariable("duration_of_product", DataType.DOUBLE, new double[]{360.0}, Collections.emptyList());
        Variable overallQualityFlag = scalarVariable("overall_quality_flag", DataType.BYTE, new byte[]{1}, Collections.emptyList());
        Variable processingFlags = scalarVariable("processing_flags", DataType.BYTE, new byte[]{1}, Collections.emptyList());
        Variable scanQualityFlag = scalarVariable("scan_quality_flag", DataType.BYTE, new byte[]{1}, Collections.emptyList());

        Group quality = group("Quality", "/Quality", Collections.emptyList(), List.of(durationOfProduct, overallQualityFlag), Collections.emptyList());
        Group processingFlagsGroup = group("Processing_Flags", "/Data/Processing_Flags", Collections.emptyList(), List.of(processingFlags), Collections.emptyList());
        Group qualityInformation = group("Quality_Information", "/Data/Quality_Information", Collections.emptyList(), List.of(scanQualityFlag), Collections.emptyList());
        Group data = group("Data", "/Data", Collections.emptyList(), Collections.emptyList(), List.of(processingFlagsGroup, qualityInformation));

        MetadataElement groups = CimrProductMetadataReader.read(ncFileWithRootGroups(List.of(quality, data)), "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Groups");

        MetadataElement qualityVariables = groups.getElement("Quality").getElement("Variables");
        assertEquals(360.0, qualityVariables.getElement("duration_of_product").getAttributeDouble("value"), 1e-8);
        assertNull(qualityVariables.getElement("overall_quality_flag"));
        assertNull(groups.getElement("Data"));
    }

    @Test
    @STTM("SNAP-4262")
    public void read_preservesNumericAttributeTypes() throws Exception {
        NetcdfFile ncFile = ncFileWithRootGroups(Collections.emptyList());
        when(ncFile.getGlobalAttributes()).thenReturn(List.of(new Attribute("orbit_number", 17)));

        MetadataElement globalAttributes = CimrProductMetadataReader.read(ncFile, "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Global_Attributes");

        assertEquals(ProductData.TYPE_INT32, globalAttributes.getAttribute("orbit_number").getDataType());
        assertEquals(17, globalAttributes.getAttributeInt("orbit_number"));
    }

    @Test
    @STTM("SNAP-4262")
    public void read_handlesAttributesWithoutValues() throws Exception {
        NetcdfFile ncFile = ncFileWithRootGroups(Collections.emptyList());
        Attribute emptyAttribute = mock(Attribute.class);
        when(emptyAttribute.getShortName()).thenReturn("empty_attribute");
        when(emptyAttribute.isString()).thenReturn(false);
        when(emptyAttribute.getValues()).thenReturn(null);
        when(emptyAttribute.toString()).thenReturn("empty_attribute = <empty>");
        when(ncFile.getGlobalAttributes()).thenReturn(List.of(emptyAttribute));

        MetadataElement globalAttributes = CimrProductMetadataReader.read(ncFile, "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Global_Attributes");

        assertEquals("empty_attribute = <empty>", globalAttributes.getAttributeString("empty_attribute"));
    }

    @Test
    @STTM("SNAP-4262")
    public void read_addsStatusSatelliteVectorMetadataVariablesLazily() throws Exception {
        List<Variable> variables = List.of(
                variable("x_position", 1, DataType.DOUBLE, new double[]{1.0}, Collections.emptyList()),
                variable("y_position", 1, DataType.DOUBLE, new double[]{2.0}, Collections.emptyList()),
                variable("z_position", 1, DataType.DOUBLE, new double[]{3.0}, Collections.emptyList()),
                variable("x_velocity", 1, DataType.DOUBLE, new double[]{4.0}, Collections.emptyList()),
                variable("y_velocity", 1, DataType.DOUBLE, new double[]{5.0}, Collections.emptyList()),
                variable("z_velocity", 1, DataType.DOUBLE, new double[]{6.0}, Collections.emptyList()),
                variable("q0", 1, DataType.DOUBLE, new double[]{7.0}, Collections.emptyList()),
                variable("q1", 1, DataType.DOUBLE, new double[]{8.0}, Collections.emptyList()),
                variable("q2", 1, DataType.DOUBLE, new double[]{9.0}, Collections.emptyList()),
                variable("q3", 1, DataType.DOUBLE, new double[]{10.0}, Collections.emptyList()),
                variable("ignored_vector", 1, DataType.DOUBLE, new double[]{11.0}, Collections.emptyList())
        );
        Group orbit = group("Orbit", "/Status/Satellite/Orbit", Collections.emptyList(), variables, Collections.emptyList());
        Group satellite = group("Satellite", "/Status/Satellite", Collections.emptyList(), Collections.emptyList(), List.of(orbit));
        Group status = group("Status", "/Status", Collections.emptyList(), Collections.emptyList(), List.of(satellite));

        MetadataElement metadataVariables = CimrProductMetadataReader.read(ncFileWithRootGroups(List.of(status)), "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Groups")
                .getElement("Status")
                .getElement("Satellite")
                .getElement("Orbit")
                .getElement("Variables");

        assertNotNull(metadataVariables.getElement("x_position"));
        assertNotNull(metadataVariables.getElement("y_position"));
        assertNotNull(metadataVariables.getElement("z_position"));
        assertNotNull(metadataVariables.getElement("x_velocity"));
        assertNotNull(metadataVariables.getElement("y_velocity"));
        assertNotNull(metadataVariables.getElement("z_velocity"));
        assertNotNull(metadataVariables.getElement("q0"));
        assertNotNull(metadataVariables.getElement("q1"));
        assertNotNull(metadataVariables.getElement("q2"));
        assertNotNull(metadataVariables.getElement("q3"));
        assertNull(metadataVariables.getElement("ignored_vector"));
        verify(variables.get(0), never()).read();
        assertArrayEquals(new double[]{1.0}, (double[]) metadataVariables.getElement("x_position").getAttribute("value").getData().getElems(), 1e-8);
        verify(variables.get(0)).read();
    }

    @Test
    @STTM("SNAP-4262")
    public void read_addsStatusInstrumentMetadataVariablesLazily() throws Exception {
        Variable instrumentMode = variable("instrument_mode", 1, DataType.INT, new int[]{1, 2}, Collections.emptyList());
        Variable modeStart = variable("mode_start_time_utc_observation", 1, DataType.DOUBLE, new double[]{3.0}, Collections.emptyList());
        Variable modeStop = variable("mode_stop_time_utc_observation", 1, DataType.DOUBLE, new double[]{4.0}, Collections.emptyList());
        Variable ignored = variable("mode_duration", 1, DataType.DOUBLE, new double[]{5.0}, Collections.emptyList());
        Group instrument = group("Instrument", "/Status/Instrument", Collections.emptyList(), List.of(instrumentMode, modeStart, modeStop, ignored), Collections.emptyList());
        Group status = group("Status", "/Status", Collections.emptyList(), Collections.emptyList(), List.of(instrument));

        MetadataElement variables = CimrProductMetadataReader.read(ncFileWithRootGroups(List.of(status)), "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Groups")
                .getElement("Status")
                .getElement("Instrument")
                .getElement("Variables");

        assertNotNull(variables.getElement("instrument_mode"));
        assertNotNull(variables.getElement("mode_start_time_utc_observation"));
        assertNotNull(variables.getElement("mode_stop_time_utc_observation"));
        assertNull(variables.getElement("mode_duration"));
    }

    @Test
    @STTM("SNAP-4262")
    public void read_addsQualityGapMetadataVariablesLazily() throws Exception {
        Variable gapStart = variable("gap_start_time_utc", 1, DataType.DOUBLE, new double[]{1.0}, Collections.emptyList());
        Variable gapEnd = variable("gap_end_time_utc", 1, DataType.DOUBLE, new double[]{2.0}, Collections.emptyList());
        Variable ignored = variable("gap_duration", 1, DataType.DOUBLE, new double[]{3.0}, Collections.emptyList());
        Group quality = group("Quality", "/Quality", Collections.emptyList(), List.of(gapStart, gapEnd, ignored), Collections.emptyList());

        MetadataElement variables = CimrProductMetadataReader.read(ncFileWithRootGroups(List.of(quality)), "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Groups")
                .getElement("Quality")
                .getElement("Variables");

        assertNotNull(variables.getElement("gap_start_time_utc"));
        assertNotNull(variables.getElement("gap_end_time_utc"));
        assertNull(variables.getElement("gap_duration"));
    }

    @Test
    @STTM("SNAP-4262")
    public void read_handlesNullGroupPathAndUsesVariableShortNamePath() throws Exception {
        Variable scalar = scalarVariable("root_scalar", DataType.DOUBLE, new double[]{42.0}, Collections.emptyList());
        Group nameless = group("Nameless", null, Collections.emptyList(), List.of(scalar), Collections.emptyList());

        MetadataElement rootScalar = CimrProductMetadataReader.read(ncFileWithRootGroups(List.of(nameless)), "path", "CIMR_L1B")
                .getMetadataElement()
                .getElement("Groups")
                .getElement("Nameless")
                .getElement("Variables")
                .getElement("root_scalar");

        assertEquals(42.0, rootScalar.getAttributeDouble("value"), 1e-8);
        verify(scalar).read();
    }

    @Test
    @STTM("SNAP-4262")
    public void netcdfMetadataProviderReturnsNullForUnknownVariablePath() throws Exception {
        Class<?> providerClass = Class.forName("eu.esa.snap.cimr.metadata.CimrProductMetadataReader$NetcdfMetadataProvider");
        Constructor<?> constructor = providerClass.getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        Object provider = constructor.newInstance(new HashMap<String, Variable>());
        Method readElement = providerClass.getDeclaredMethod("readElement", String.class);
        readElement.setAccessible(true);

        assertNull(readElement.invoke(provider, "missing/path"));
    }


    private static NetcdfFile emptyNcFile() {
        return ncFileWithRootGroups(Collections.emptyList());
    }

    private static NetcdfFile ncFileWithRootGroups(List<Group> groups) {
        NetcdfFile ncFile = mock(NetcdfFile.class);
        Group root = group("", "", Collections.emptyList(), Collections.emptyList(), groups);
        when(ncFile.getRootGroup()).thenReturn(root);
        when(ncFile.getGlobalAttributes()).thenReturn(Collections.emptyList());
        return ncFile;
    }

    private static Variable scalarVariable(String shortName, DataType dataType, Object data, List<Attribute> attributes) throws Exception {
        return variable(shortName, 0, dataType, data, attributes);
    }

    private static Variable variable(String shortName, int rank, DataType dataType, Object data, List<Attribute> attributes) throws Exception {
        Variable variable = mock(Variable.class);
        when(variable.getShortName()).thenReturn(shortName);
        when(variable.getRank()).thenReturn(rank);
        when(variable.getDataType()).thenReturn(dataType);
        when(variable.getShape()).thenReturn(shape(rank, data));
        when(variable.getDimensionsString()).thenReturn(rank == 0 ? "" : "dim_0");
        when(variable.attributes()).thenReturn(attributeContainer(attributes));
        when(variable.read()).thenReturn(Array.factory(dataType, shape(rank, data), data));
        return variable;
    }

    private static int[] shape(int rank, Object data) {
        if (rank == 0) {
            return new int[0];
        }
        if (data instanceof double[]) {
            return shape(rank, ((double[]) data).length);
        }
        if (data instanceof byte[]) {
            return shape(rank, ((byte[]) data).length);
        }
        if (data instanceof int[]) {
            return shape(rank, ((int[]) data).length);
        }
        return new int[]{1};
    }

    private static int[] shape(int rank, int length) {
        int[] shape = new int[rank];
        for (int i = 0; i < rank - 1; i++) {
            shape[i] = 1;
        }
        shape[rank - 1] = length;
        return shape;
    }

    private static Group group(String shortName,
                               String fullName,
                               List<Attribute> attributes,
                               List<Variable> variables,
                               List<Group> groups) {
        Group group = mock(Group.class);
        when(group.getShortName()).thenReturn(shortName);
        when(group.getFullName()).thenReturn(fullName);
        when(group.attributes()).thenReturn(attributeContainer(attributes));
        when(group.getVariables()).thenReturn(variables);
        when(group.getGroups()).thenReturn(groups);
        return group;
    }

    private static AttributeContainerMutable attributeContainer(List<Attribute> attributes) {
        return new AttributeContainerMutable("attributes", attributes);
    }
}
