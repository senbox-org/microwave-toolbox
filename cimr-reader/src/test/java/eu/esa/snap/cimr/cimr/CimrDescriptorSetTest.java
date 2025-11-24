package eu.esa.snap.cimr.cimr;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;


public class CimrDescriptorSetTest {


    @Test
    public void getGeometryByName_returnsDescriptorWhenPresent() {
        CimrBandDescriptor geom1 = descriptor("LAT");
        CimrBandDescriptor geom2 = descriptor("LON");

        List<CimrBandDescriptor> measurements = Collections.emptyList();
        List<CimrBandDescriptor> geometries   = Arrays.asList(geom1, geom2);
        List<CimrBandDescriptor> tiepoints    = Collections.emptyList();

        CimrDescriptorSet set = new CimrDescriptorSet(measurements, geometries, tiepoints);

        CimrBandDescriptor result = set.getGeometryByName("LON");

        assertSame("Expected to get the matching geometry descriptor", geom2, result);
    }

    @Test
    public void getGeometryByName_returnsNullWhenNameNotFound() {
        CimrBandDescriptor geom1 = descriptor("LAT");

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
        List<CimrBandDescriptor> measurements = Collections.singletonList(descriptor("MEAS"));
        List<CimrBandDescriptor> geometries   = Collections.singletonList(descriptor("GEOM"));
        List<CimrBandDescriptor> tiepoints    = Collections.singletonList(descriptor("TP"));

        CimrDescriptorSet set = new CimrDescriptorSet(measurements, geometries, tiepoints);

        assertSame(measurements, set.getMeasurements());
        assertSame(geometries,   set.getGeometries());
        assertSame(tiepoints,    set.getTiepointVariables());
    }

    @Test
    public void getGeometryByName_returnsFirstMatchWhenMultipleWithSameName() {
        CimrBandDescriptor geom1 = descriptor("LAT");
        CimrBandDescriptor geom2 = descriptor("LAT");

        CimrDescriptorSet set = new CimrDescriptorSet(
                Collections.emptyList(),
                Arrays.asList(geom1, geom2),
                Collections.emptyList()
        );

        CimrBandDescriptor result = set.getGeometryByName("LAT");

        assertSame("Expected first matching descriptor to be returned", geom1, result);
    }


    private static CimrBandDescriptor descriptor(String name) {
        return new CimrBandDescriptor(
                name,
                "C_BAND_bt",
                CimrFrequencyBand.C_BAND,
                new String[] {"C_BAND_latitude", "C_BAND_longitude"},
                "/dummy/group",
                0,
                CimrDescriptorKind.GEOMETRY,
                new String[]{"n_scans", "n_samples_C_BAND"},
                "double"
        );
    }
}