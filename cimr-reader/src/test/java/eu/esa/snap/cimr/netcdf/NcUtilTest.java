package eu.esa.snap.cimr.netcdf;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;
import ucar.ma2.DataType;
import ucar.nc2.Attribute;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class NcUtilTest {


    @Test
    @STTM("SNAP-4262")
    public void getGlobalAttributeString_readsStringAttribute() {
        NetcdfFile ncFile = mock(NetcdfFile.class);
        when(ncFile.findGlobalAttribute("format_version")).thenReturn(new Attribute("format_version", "1.1"));

        assertEquals("1.1", NcUtil.getGlobalAttributeString(ncFile, "format_version"));
    }

    @Test
    @STTM("SNAP-4262")
    public void getGlobalAttributeString_returnsNullWhenAttributeIsMissing() {
        NetcdfFile ncFile = mock(NetcdfFile.class);
        when(ncFile.findGlobalAttribute("format_version")).thenReturn(null);

        assertNull(NcUtil.getGlobalAttributeString(ncFile, "format_version"));
    }

    @Test
    public void testFindGroupOrThrow_Found() {
        Group.Builder rootBuilder = Group.builder(null).setName("root");
        Group.Builder childBuilder = Group.builder(rootBuilder).setName("child");
        rootBuilder.addGroup(childBuilder);

        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(rootBuilder)
                .build();

        Group g = NcUtil.findGroupOrThrow(ncFile, "/child");
        assertNotNull(g);
        assertEquals("child", g.getShortName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindGroupOrThrow_NotFound() {
        Group.Builder rootBuilder = Group.builder(null).setName("root");
        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(rootBuilder)
                .build();

        NcUtil.findGroupOrThrow(ncFile, "/does/not/exist");
    }

    @Test
    public void testFindVarOrThrow_Found() {
        Group.Builder groupBuilder = Group.builder(null).setName("g");
        Variable.Builder<?> varBuilder = Variable.builder()
                .setName("v")
                .setDataType(DataType.DOUBLE);
        groupBuilder.addVariable(varBuilder);

        Group group = groupBuilder.build(null);

        Variable v = NcUtil.findVarOrThrow(group, "v");
        assertNotNull(v);
        assertEquals("v", v.getShortName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFindVarOrThrow_NotFound() {
        Group.Builder groupBuilder = Group.builder(null).setName("g");
        Group group = groupBuilder.build(null);

        NcUtil.findVarOrThrow(group, "missing");
    }
}