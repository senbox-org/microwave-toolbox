package eu.esa.snap.cimr;

import com.bc.ceres.annotation.STTM;
import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDescriptorSet;
import eu.esa.snap.cimr.cimr.CimrDescriptorKind;
import eu.esa.snap.cimr.cimr.CimrFrequencyBand;
import org.esa.snap.core.datamodel.ProductData;
import org.junit.Test;
import ucar.ma2.DataType;
import ucar.nc2.Attribute;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class CimrReaderContextFactoryTest {


    @Test
    @STTM("SNAP-4262")
    public void loadDescriptorSet_usesFormatVersionFromProduct() throws IOException {
        NetcdfFile ncFile = mock(NetcdfFile.class);
        when(ncFile.findGlobalAttribute("format_version")).thenReturn(new Attribute("format_version", "1.1"));

        CimrDescriptorSet descriptorSet = CimrReaderContextFactory.loadDescriptorSet(ncFile);

        assertEquals(200, descriptorSet.getMeasurements().size());
        assertNotNull(descriptorSet.getMeasurementByName("L_BAND_brightness_temperature_h_feed1"));
        assertNotNull(descriptorSet.getTpVariableByName("KA_BAND_footprint_major_axis_feed8"));
        assertNotNull(descriptorSet.getGeometryByName("C_BAND_longitude_feed4"));
    }

    @Test
    @STTM("SNAP-4262")
    public void loadDescriptorSet_fallsBackToDefaultDescriptorWhenFormatVersionIsMissing() throws IOException {
        NetcdfFile ncFile = mock(NetcdfFile.class);
        when(ncFile.findGlobalAttribute("format_version")).thenReturn(null);

        CimrDescriptorSet descriptorSet = CimrReaderContextFactory.loadDescriptorSet(ncFile);

        assertEquals(200, descriptorSet.getMeasurements().size());
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

    private static CimrBandDescriptor descriptor(String name, String valueVarName) {
        return new CimrBandDescriptor(
                name,
                valueVarName,
                CimrFrequencyBand.C_BAND,
                new String[0],
                new String[0],
                "/Data/Measurement_Data/C_BAND/",
                0,
                CimrDescriptorKind.VARIABLE,
                new String[0],
                "",
                "",
                ""
        );
    }
}
