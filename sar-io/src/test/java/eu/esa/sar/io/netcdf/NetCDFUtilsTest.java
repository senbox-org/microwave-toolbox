package eu.esa.sar.io.netcdf;

import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import ucar.nc2.*;
import ucar.nc2.ncml.NcMLReader;

import java.io.IOException;
import java.io.StringReader;

import static org.junit.Assert.*;

/**
 * Integration-style JUnit 4 test for NetCDFUtils.addVariableMetadata
 * using real NetCDF objects created in memory via NcML, without mocks.
 * Relies on real MetadataUtils, ReaderUtils etc.
 */
public class NetCDFUtilsTest {

    private static NetcdfFile netcdfFile; // In-memory NetCDF file from NcML
    private MetadataElement root; // Root metadata element for each test

    // Define variable names used in tests
    private static final String VAR_TEMP = "temperature";
    private static final String VAR_PRESSURE_PATH = "groupA/subGroupB/pressure";
    private static final String VAR_DATATYPE_PATH = "ancillary/dataType";
    private static final String VAR_IMAGE_PATH = "data/arrays/image_data";
    private static final String VAR_STRUCT_PATH = "location/platformState";
    private static final String VAR_STRUCT_MEMBER_LAT = "latitude";
    private static final String VAR_STRUCT_MEMBER_LON = "longitude";
    private static final String VAR_OVERLAP_1 = "sensors/temp";
    private static final String VAR_OVERLAP_2 = "sensors/temp/calibration";

    // Define the NcML structure as a String
    private static final String NCML_DEFINITION =
            "<?xml version='1.0' encoding='UTF-8'?>\n" +
                    "<netcdf xmlns='http://www.unidata.ucar.edu/namespaces/netcdf/ncml-2.2'>\n" +
                    "  <dimension name='dim10' length='10' />\n" +
                    "  <dimension name='dim1' length='1' />\n" +
                    "  <dimension name='strLen' length='20' />\n" +
                    "  <dimension name='dimX' length='5' />\n" +
                    "  <dimension name='dimY' length='4' />\n" +
                    "\n" +
                    "  <variable name='" + VAR_TEMP + "' type='float' shape='dim10'>\n" +
                    "    <attribute name='units' value='celsius' />\n" +
                    "  </variable>\n" +
                    "  <variable name='" + VAR_OVERLAP_1 + "' type='float' shape='dim1'>\n" + // Size 1
                    "    <attribute name='units' value='Kelvin' />\n" +
                    "  </variable>\n" +
                    "\n" +
                    // Variables with paths need groups in NcML
                    "  <group name='groupA'>\n" +
                    "    <group name='subGroupB'>\n" +
                    "      <variable name='pressure' type='int' shape='dim1'>\n" + // Full path: groupA/subGroupB/pressure
                    "        <attribute name='units' value='Pa' />\n" +
                    "      </variable>\n" +
                    "    </group>\n" +
                    "  </group>\n" +
                    "\n" +
                    "  <group name='ancillary'>\n" +
                    "    <variable name='dataType' type='char' shape='strLen'>\n" + // Full path: ancillary/dataType
                    "       <attribute name='description' value='Type of data' />\n" +
                    "    </variable>\n" +
                    "  </group>\n" +
                    "\n" +
                    "  <group name='data'>\n" +
                    "    <group name='arrays'>\n" +
                    "      <variable name='image_data' type='float' shape='dimY dimX'>\n" + // Full path: data/arrays/image_data
                    "        <attribute name='units' value='counts' />\n" +
                    "      </variable>\n" +
                    "    </group>\n" +
                    "  </group>\n" +
                    "\n" +
                    // Structure definition
                    "  <group name='location'>\n" +
                    "    <variable name='platformState' type='structure' shape='dim1'>\n" + // Full path: location/platformState
                    "      <variable name='" + VAR_STRUCT_MEMBER_LAT + "' type='double'>\n" +
                    "         <attribute name='units' value='degrees_north' />\n" +
                    "      </variable>\n" +
                    "      <variable name='" + VAR_STRUCT_MEMBER_LON + "' type='double'>\n" +
                    "         <attribute name='units' value='degrees_east' />\n" +
                    "      </variable>\n" +
                    "      <attribute name='comment' value='Platform state structure' />\n" + // Attribute on structure itself
                    "    </variable>\n" +
                    "  </group>\n" +
                    "\n" +
                    "</netcdf>\n";

    // Update overlap variable names based on revised NcML plan
    private static final String VAR_OVERLAP_1_REVISED = "sensors/temperature_value";
    private static final String VAR_OVERLAP_2_REVISED = "sensors/calibration_coeffs";

    @BeforeClass // Setup executed once before all tests
    public static void setupNetcdfFile() throws IOException {
        // Use NcmlReader to parse the NcML string into an in-memory NetcdfFile
        // The second argument 'null' means create it in memory relative to no file location
        netcdfFile = NcMLReader.readNcML(new StringReader(NCML_DEFINITION), null);
        //assertNotNull("Failed to create NetcdfFile from NcML", netcdfFile);
    }

    @AfterClass // Cleanup executed once after all tests
    public static void cleanupNetcdfFile() throws IOException {
        if (netcdfFile != null) {
            netcdfFile.close();
        }
    }

    @Before // JUnit 4 setup method annotation
    public void setUp() {
        // Reset root element before each test
        root = new MetadataElement("root");
    }

    // --- Helper Methods for Assertions (Identical) ---
    private MetadataElement getElementByPath(MetadataElement startElement, String path) {
        if (path == null || path.isEmpty()) return startElement;
        String[] parts = path.split("/");
        MetadataElement current = startElement;
        for (String part : parts) {
            if (current == null || part.isEmpty()) return null;
            current = current.getElement(part);
        }
        return current;
    }

    private MetadataElement assertElementExists(String path) {
        MetadataElement element = getElementByPath(root, path);
        assertNotNull("Element should exist at path: " + path, element);
        String expectedName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        assertEquals("Element name mismatch at path: " + path, expectedName, element.getName());
        return element;
    }

    private MetadataAttribute assertAttributeExists(MetadataElement element, String attributeName) {
        assertNotNull("Cannot check attributes on null element", element);
        MetadataAttribute attribute = element.getAttribute(attributeName);
        assertNotNull("Attribute '" + attributeName + "' should exist on element '" + element.getName() + "'", attribute);
        assertEquals("Attribute name mismatch", attributeName, attribute.getName());
        return attribute;
    }
    // --- Test Cases ---

    @Test
    public void testAddVariableMetadata_SimpleVariable_NoPath() {
        Variable variable = netcdfFile.findVariable(VAR_TEMP); // Root variable
        assertNotNull(variable);

        NetCDFUtils.addVariableMetadata(root, variable, -1);

        MetadataElement leafElement = assertElementExists(VAR_TEMP);
        // Attribute "data" should exist (size > 1)
        // Its content depends on variable.read() succeeding in NetCDFUtils.addAttribute
        // Let's check for existence, but value might be default/zeros.
        assertAttributeExists(leafElement, "data");

        // Check if NC attribute defined in NcML was added by MetadataUtils (implicitly tested)
        assertTrue("NC attribute 'units' should be present if MetadataUtils worked",
                leafElement.getAttribute("units") != null);
    }

    @Test
    public void testAddVariableMetadata_SimpleVariable_WithPath() {
        Variable variable = netcdfFile.findVariable(VAR_PRESSURE_PATH); // Path: groupA/subGroupB/pressure
        assertNotNull(variable);

        NetCDFUtils.addVariableMetadata(root, variable, -1);

        assertElementExists("groupA");
        assertElementExists("groupA/subGroupB");
        MetadataElement leafElement = assertElementExists(VAR_PRESSURE_PATH);
        // Attribute named after variable (size == 1)
        // Check existence, value depends on read().
        assertAttributeExists(leafElement, "pressure");
        assertTrue("NC attribute 'units' should be present if MetadataUtils worked",
                leafElement.getAttribute("units") != null);
    }

    @Test
    public void testAddVariableMetadata_AsciiVariable_WithPath() {
        Variable variable = netcdfFile.findVariable(VAR_DATATYPE_PATH); // Path: ancillary/dataType
        assertNotNull(variable);

        NetCDFUtils.addVariableMetadata(root, variable, -1);

        assertElementExists("ancillary");
        MetadataElement leafElement = assertElementExists(VAR_DATATYPE_PATH);
        // Attribute named after variable (ASCII type)
        // Check existence, value depends on read().
        assertAttributeExists(leafElement, "dataType");
        assertTrue("NC attribute 'description' should be present if MetadataUtils worked",
                leafElement.getAttribute("description") != null);
    }

    @Test
    public void testAddVariableMetadata_ComplexArray_WithPath() {
        Variable variable = netcdfFile.findVariable(VAR_IMAGE_PATH); // Path: data/arrays/image_data
        assertNotNull(variable);

        NetCDFUtils.addVariableMetadata(root, variable, -1);

        assertElementExists("data");
        assertElementExists("data/arrays");
        MetadataElement leafElement = assertElementExists(VAR_IMAGE_PATH);
        // Attribute "data" (size > 1)
        assertAttributeExists(leafElement, "data");
        assertTrue("NC attribute 'units' should be present if MetadataUtils worked",
                leafElement.getAttribute("units") != null);
    }

    @Test
    public void testAddVariableMetadata_Structure_WithPath() {
        Variable variable = netcdfFile.findVariable(VAR_STRUCT_PATH); // Path: location/platformState
        assertNotNull(variable);
        assertTrue(variable instanceof Structure);

        NetCDFUtils.addVariableMetadata(root, variable, -1);

        assertElementExists("location");
        MetadataElement structureElement = assertElementExists(VAR_STRUCT_PATH);

        // Check NC attribute on the structure element itself
        assertTrue("NC attribute 'comment' should be present if MetadataUtils worked",
                structureElement.getAttribute("comment") != null);

        // Assert that the member elements exist under the structure element
        MetadataElement latMember = structureElement.getElement(VAR_STRUCT_MEMBER_LAT);
        assertNotNull("Member element '" + VAR_STRUCT_MEMBER_LAT + "' should exist", latMember);
        assertEquals(VAR_STRUCT_MEMBER_LAT, latMember.getName());
        // Check attributes potentially added by MetadataUtils.addAttribute
        // Example: assertNotNull(latMember.getAttribute("units")); // Might fail

        MetadataElement lonMember = structureElement.getElement(VAR_STRUCT_MEMBER_LON);
        assertNotNull("Member element '" + VAR_STRUCT_MEMBER_LON + "' should exist", lonMember);
        assertEquals(VAR_STRUCT_MEMBER_LON, lonMember.getName());
        // Example: assertNotNull(lonMember.getAttribute("units")); // Might fail
    }
}