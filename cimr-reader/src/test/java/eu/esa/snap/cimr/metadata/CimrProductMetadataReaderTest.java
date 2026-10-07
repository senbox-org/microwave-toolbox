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
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;


public class CimrProductMetadataReaderTest {


    @Test
    @STTM("SNAP-4262")
    public void read_usesProductNameAttributeWhenPresent() throws Exception {
        NetcdfFile ncFile = emptyNcFile();
        when(ncFile.findGlobalAttribute("product_name")).thenReturn(new Attribute("product_name", " CIMR_TEST_PRODUCT "));

        CimrProductMetadata metadata = CimrProductMetadataReader.read(ncFile, "C:\\data\\fallback.nc", "CIMR_L1B");

        assertEquals("CIMR_TEST_PRODUCT", metadata.getProductName());
        assertEquals("CIMR_L1B", metadata.getProductType());
        assertEquals("CIMR_Metadata", metadata.getMetadataElement().getName());
    }

    @Test
    @STTM("SNAP-4262")
    public void read_usesFileNameWithoutExtensionWhenProductNameAttributeIsMissing() throws Exception {
        NetcdfFile ncFile = emptyNcFile();
        when(ncFile.findGlobalAttribute("product_name")).thenReturn(null);

        CimrProductMetadata metadata = CimrProductMetadataReader.read(ncFile,
                "C:\\data\\W_PT-DME-Lisbon-SAT-CIMR-1B_C_DME_20260921T093512.nc",
                "CIMR_L1B");

        assertEquals("W_PT-DME-Lisbon-SAT-CIMR-1B_C_DME_20260921T093512", metadata.getProductName());
        assertEquals("CIMR_L1B", metadata.getProductType());
    }

    @Test
    @STTM("SNAP-4262")
    public void read_usesFileNameWithoutExtensionWhenProductNameAttributeIsBlank() throws Exception {
        NetcdfFile ncFile = emptyNcFile();
        when(ncFile.findGlobalAttribute("product_name")).thenReturn(new Attribute("product_name", " "));

        CimrProductMetadata metadata = CimrProductMetadataReader.read(ncFile, "C:\\data\\test-product.nc", "CIMR_L1B");

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
        Variable creationTime = scalarVariable("creation_time_utc", DataType.DOUBLE, new double[]{42.0},
                List.of(new Attribute("units", "seconds since 2028-01-01 00:00:00.00")));
        Variable semiMajorAxis = scalarVariable("semi_major_axis", DataType.DOUBLE, new double[]{7200000.0},
                List.of(new Attribute("units", "m")));

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
        assertArrayEquals(new double[]{1.0, 2.0, 3.0, 4.0},
                (double[]) acqTimeMetadata.getAttribute("value").getData().getElems(), 1e-8);
        verify(acqTime).read();
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
            return rank == 1 ? new int[]{((double[]) data).length} : new int[]{1, ((double[]) data).length};
        }
        if (data instanceof byte[]) {
            return rank == 1 ? new int[]{((byte[]) data).length} : new int[]{1, ((byte[]) data).length};
        }
        return new int[]{1};
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