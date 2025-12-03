package eu.esa.snap.cimr.cimr;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;


public class CimrDescriptorSetTest {


    @Test
    public void getGeometryByName_returnsDescriptorWhenPresent() {
        CimrBandDescriptor geom1 = descriptor("LAT", CimrDescriptorKind.GEOMETRY);
        CimrBandDescriptor geom2 = descriptor("LON", CimrDescriptorKind.GEOMETRY);

        List<CimrBandDescriptor> measurements = Collections.emptyList();
        List<CimrBandDescriptor> geometries   = Arrays.asList(geom1, geom2);
        List<CimrBandDescriptor> tiepoints    = Collections.emptyList();

        CimrDescriptorSet set = new CimrDescriptorSet(measurements, geometries, tiepoints);

        CimrBandDescriptor result = set.getGeometryByName("LON");

        assertSame("Expected to get the matching geometry descriptor", geom2, result);
    }

    @Test
    public void getGeometryByName_returnsNullWhenNameNotFound() {
        CimrBandDescriptor geom1 = descriptor("LAT", CimrDescriptorKind.GEOMETRY);

        CimrDescriptorSet set = new CimrDescriptorSet(
                Collections.emptyList(),
                Collections.singletonList(geom1),
                Collections.emptyList()
        );

        CimrBandDescriptor result = set.getGeometryByName("LON");

        assertNull("Expected null when geometry name is not found", result);
    }

    @Test
    public void getGeometryByName_returnsNullWhenNoGeometries() {
        CimrDescriptorSet set = new CimrDescriptorSet(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );

        CimrBandDescriptor result = set.getGeometryByName("ANY");

        assertNull("Expected null when geometries list is empty", result);
    }

    @Test
    public void getters_returnListsPassedToConstructor() {
        List<CimrBandDescriptor> measurements = Collections.singletonList(descriptor("MEAS", CimrDescriptorKind.VARIABLE));
        List<CimrBandDescriptor> geometries   = Collections.singletonList(descriptor("GEOM",CimrDescriptorKind.GEOMETRY));
        List<CimrBandDescriptor> tiepoints    = Collections.singletonList(descriptor("TP",CimrDescriptorKind.TIEPOINT_VARIABLE));

        CimrDescriptorSet set = new CimrDescriptorSet(measurements, geometries, tiepoints);

        assertSame(measurements, set.getMeasurements());
        assertSame(geometries,   set.getGeometries());
        assertSame(tiepoints,    set.getTiepointVariables());
    }

    @Test
    public void getGeometryByName_returnsFirstMatchWhenMultipleWithSameName() {
        CimrBandDescriptor geom1 = descriptor("LAT", CimrDescriptorKind.GEOMETRY);
        CimrBandDescriptor geom2 = descriptor("LAT", CimrDescriptorKind.GEOMETRY);

        CimrDescriptorSet set = new CimrDescriptorSet(
                Collections.emptyList(),
                Arrays.asList(geom1, geom2),
                Collections.emptyList()
        );

        CimrBandDescriptor result = set.getGeometryByName("LAT");

        assertSame("Expected first matching descriptor to be returned", geom1, result);
    }

    @Test
    public void getMeasurementByName_returnsDescriptorWhenPresent() {
        CimrBandDescriptor var1 = descriptor("LAT", CimrDescriptorKind.VARIABLE);
        CimrBandDescriptor var2 = descriptor("LON", CimrDescriptorKind.VARIABLE);

        List<CimrBandDescriptor> geometries = Collections.emptyList();
        List<CimrBandDescriptor> measurements   = Arrays.asList(var1, var2);
        List<CimrBandDescriptor> tiepoints    = Collections.emptyList();

        CimrDescriptorSet set = new CimrDescriptorSet(measurements, geometries, tiepoints);

        CimrBandDescriptor result = set.getMeasurementByName("LON");

        assertSame("Expected to get the matching geometry descriptor", var2, result);
    }

    @Test
    public void getMeasurementByName_returnsNull() {
        CimrBandDescriptor var1 = descriptor("LAT", CimrDescriptorKind.VARIABLE);

        List<CimrBandDescriptor> geometries = Collections.emptyList();
        List<CimrBandDescriptor> measurements   = Arrays.asList(var1);
        List<CimrBandDescriptor> tiepoints    = Collections.emptyList();

        CimrDescriptorSet set = new CimrDescriptorSet(measurements, geometries, tiepoints);

        CimrBandDescriptor result = set.getMeasurementByName("LON");

        assertNull(result);
    }

    @Test
    public void getTPByName_returnsDescriptorWhenPresent() {
        CimrBandDescriptor tp1 = descriptor("LAT", CimrDescriptorKind.TIEPOINT_VARIABLE);
        CimrBandDescriptor tp2 = descriptor("LON", CimrDescriptorKind.TIEPOINT_VARIABLE);

        List<CimrBandDescriptor> geometries = Collections.emptyList();
        List<CimrBandDescriptor> tiepoints   = Arrays.asList(tp1, tp2);
        List<CimrBandDescriptor> measurements    = Collections.emptyList();

        CimrDescriptorSet set = new CimrDescriptorSet(measurements, geometries, tiepoints);

        CimrBandDescriptor result = set.getTpVariableByName("LON");

        assertSame("Expected to get the matching geometry descriptor", tp2, result);
    }

    @Test
    public void getTPByName_returnsNull() {
        CimrBandDescriptor tp1 = descriptor("LAT", CimrDescriptorKind.TIEPOINT_VARIABLE);

        List<CimrBandDescriptor> geometries = Collections.emptyList();
        List<CimrBandDescriptor> tiepoints   = Arrays.asList(tp1);
        List<CimrBandDescriptor> measurements    = Collections.emptyList();

        CimrDescriptorSet set = new CimrDescriptorSet(measurements, geometries, tiepoints);

        CimrBandDescriptor result = set.getTpVariableByName("LON");

        assertNull(result);
    }


    private static CimrBandDescriptor descriptor(String name, CimrDescriptorKind kind) {
        return new CimrBandDescriptor(
                name,
                "C_BAND_bt",
                CimrFrequencyBand.C_BAND,
                new String[] {"C_BAND_latitude", "C_BAND_longitude"},
                new String[] {""},
                "/dummy/group",
                0,
                kind,
                new String[]{"n_scans", "n_samples_C_BAND"},
                "double",
                "",
                ""
        );
    }
}