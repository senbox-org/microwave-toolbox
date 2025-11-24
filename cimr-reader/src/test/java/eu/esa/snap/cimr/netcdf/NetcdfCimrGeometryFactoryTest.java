package eu.esa.snap.cimr.netcdf;


import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDimensions;
import eu.esa.snap.cimr.cimr.CimrFrequencyBand;
import eu.esa.snap.cimr.cimr.CimrDescriptorKind;
import eu.esa.snap.cimr.grid.CimrGeometry;
import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;
import ucar.ma2.ArrayDouble;
import ucar.ma2.DataType;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Dimension;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;


import static org.junit.Assert.*;


public class NetcdfCimrGeometryFactoryTest {

    private static final double doubleErr = 1e-6;


    @Test
    public void testGetOrCreateGeometry_BuildsGeometryAndCaches() throws IOException, InvalidRangeException {
        int nScans = 2;
        int nTiepoints = 2;
        int nFeeds = 2;
        int nSamplesOut = 4;

        Dimension scanDim    = new Dimension("n_scans", nScans);
        Dimension tpDim      = new Dimension("n_tiepoints_C_BAND", nTiepoints);
        Dimension feedDim    = new Dimension("n_feeds_C_BAND", nFeeds);
        Dimension samplesDim = new Dimension("n_samples_C_BAND", nSamplesOut);

        Group.Builder rootBuilder = Group.builder(null).setName("root");
        rootBuilder.addDimension(scanDim)
                .addDimension(tpDim)
                .addDimension(feedDim)
                .addDimension(samplesDim);

        ArrayDouble.D3 latData = new ArrayDouble.D3(nScans, nTiepoints, nFeeds);
        ArrayDouble.D3 lonData = new ArrayDouble.D3(nScans, nTiepoints, nFeeds);
        for (int s = 0; s < nScans; s++) {
            for (int tp = 0; tp < nTiepoints; tp++) {
                latData.set(s, tp, 0, 10.0 * s);
                lonData.set(s, tp, 0, 100.0 * tp);
            }
        }

        Variable.Builder<?> latBuilder = Variable.builder()
                .setName("lat_c")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_tiepoints_C_BAND n_feeds_C_BAND")
                .setCachedData(latData, false);
        Variable.Builder<?> lonBuilder = Variable.builder()
                .setName("lon_c")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_tiepoints_C_BAND n_feeds_C_BAND")
                .setCachedData(lonData, false);

        rootBuilder.addVariable(latBuilder);
        rootBuilder.addVariable(lonBuilder);

        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(rootBuilder)
                .build();

        CimrDimensions dims = CimrDimensions.from(ncFile);
        String rootPath = "/";

        CimrBandDescriptor latDesc = new CimrBandDescriptor(
                "lat_c", "lat_c", CimrFrequencyBand.C_BAND,
                new String[]{},
                rootPath,
                0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_tiepoints_C_BAND", "n_feeds_C_BAND"},
                "double"
        );
        CimrBandDescriptor lonDesc = new CimrBandDescriptor(
                "lon_c", "lon_c", CimrFrequencyBand.C_BAND,
                new String[]{},
                rootPath,
                0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_tiepoints_C_BAND", "n_feeds_C_BAND"},
                "double"
        );

        CimrBandDescriptor varDesc = new CimrBandDescriptor(
                "altitude", "altitude", CimrFrequencyBand.C_BAND,
                new String[]{"lat_c", "lon_c"},
                rootPath,
                0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double"
        );

        NetcdfCimrGeometryFactory factory = new NetcdfCimrGeometryFactory(ncFile, Arrays.asList(latDesc, lonDesc), dims);

        CimrGeometry geom1 = factory.getOrCreateGeometry(varDesc);
        assertNotNull(geom1);
        assertEquals(nScans, geom1.getScanCount());
        assertEquals(nSamplesOut, geom1.getSampleCount());

        GeoPos g0_start = geom1.getGeoPos(0, 0, 0);
        GeoPos g0_end   = geom1.getGeoPos(0, nSamplesOut - 1, 0);
        assertEquals(0.0, g0_start.getLat(), doubleErr);
        assertEquals(0.0, g0_start.getLon(), doubleErr);
        assertEquals(0.0, g0_end.getLat(), doubleErr);
        assertEquals(100.0, g0_end.getLon(), doubleErr);

        GeoPos g1_start = geom1.getGeoPos(1, 0, 0);
        GeoPos g1_end   = geom1.getGeoPos(1, nSamplesOut - 1, 0);
        assertEquals(10.0, g1_start.getLat(), doubleErr);
        assertEquals(0.0, g1_start.getLon(), doubleErr);
        assertEquals(10.0, g1_end.getLat(), doubleErr);
        assertEquals(100.0, g1_end.getLon(), doubleErr);

        CimrGeometry geom2 = factory.getOrCreateGeometry(varDesc);
        assertSame(geom1, geom2);

        assertEquals(4, geom2.getSampleCount());
    }

    @Test
    public void testClearCacheCreatesNewInstance() throws IOException, InvalidRangeException {
        int nScans = 2;
        int nTiepoints = 2;
        int nFeeds = 2;
        int nSamplesOut = 4;

        Dimension scanDim    = new Dimension("n_scans", nScans);
        Dimension tpDim      = new Dimension("n_tiepoints_C_BAND", nTiepoints);
        Dimension feedDim    = new Dimension("n_feeds_C_BAND", nFeeds);
        Dimension samplesDim = new Dimension("n_samples_C_BAND", nSamplesOut);

        Group.Builder rootBuilder = Group.builder(null).setName("root");
        rootBuilder.addDimension(scanDim)
                .addDimension(tpDim)
                .addDimension(feedDim)
                .addDimension(samplesDim);

        ArrayDouble.D3 latData = new ArrayDouble.D3(nScans, nTiepoints, nFeeds);
        ArrayDouble.D3 lonData = new ArrayDouble.D3(nScans, nTiepoints, nFeeds);
        latData.set(0, 0, 0, 42.0);
        lonData.set(0, 0, 0, 7.0);

        Variable.Builder<?> latBuilder = Variable.builder()
                .setName("lat_c")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_tiepoints_C_BAND n_feeds_C_BAND")
                .setCachedData(latData, false);
        Variable.Builder<?> lonBuilder = Variable.builder()
                .setName("lon_c")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_tiepoints_C_BAND n_feeds_C_BAND")
                .setCachedData(lonData, false);

        rootBuilder.addVariable(latBuilder);
        rootBuilder.addVariable(lonBuilder);

        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(rootBuilder)
                .build();

        CimrDimensions dims = CimrDimensions.from(ncFile);
        String rootPath = "/";

        CimrBandDescriptor latDesc = new CimrBandDescriptor(
                "lat_c", "lat_c", CimrFrequencyBand.C_BAND,
                new String[]{},
                rootPath, 0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_tiepoints_C_BAND", "n_feeds_C_BAND"},
                "double"
        );
        CimrBandDescriptor lonDesc = new CimrBandDescriptor(
                "lon_c", "lon_c", CimrFrequencyBand.C_BAND,
                new String[]{},
                rootPath, 0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_tiepoints_C_BAND", "n_feeds_C_BAND"},
                "double"
        );

        CimrBandDescriptor varDesc = new CimrBandDescriptor(
                "altitude", "altitude", CimrFrequencyBand.C_BAND,
                new String[]{"lat_c", "lon_c"},
                rootPath, 0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double"
        );

        NetcdfCimrGeometryFactory factory = new NetcdfCimrGeometryFactory(ncFile, Arrays.asList(latDesc, lonDesc), dims);

        CimrGeometry g1 = factory.getOrCreateGeometry(varDesc);
        factory.clearCache();
        CimrGeometry g2 = factory.getOrCreateGeometry(varDesc);

        assertNotSame(g1, g2);
        GeoPos p = g2.getGeoPos(0, 0, 0);
        assertEquals(42.0, p.getLat(), doubleErr);
        assertEquals(7.0, p.getLon(), doubleErr);
    }

    @Test(expected = IllegalStateException.class)
    public void testGetOrCreateGeometry_FailsForInvalidGeometryNames() throws IOException, InvalidRangeException {
        Group.Builder rootBuilder = Group.builder(null).setName("root");
        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(rootBuilder)
                .build();

        CimrDimensions dims = CimrDimensions.from(ncFile);

        NetcdfCimrGeometryFactory factory = new NetcdfCimrGeometryFactory(ncFile, Collections.emptyList(), dims);

        CimrBandDescriptor varDesc = new CimrBandDescriptor(
                "altitude", "altitude", CimrFrequencyBand.C_BAND,
                null,
                "root", 0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double"
        );

        factory.getOrCreateGeometry(varDesc);
    }

    @Test(expected = IllegalStateException.class)
    public void testGetOrCreateGeometry_FailsWhenGeometryDescriptorsMissing() throws IOException, InvalidRangeException {
        int nScans = 2;
        int nTiepoints = 2;
        int nFeeds = 2;
        int nSamplesOut = 4;

        Dimension scanDim    = new Dimension("n_scans", nScans);
        Dimension tpDim      = new Dimension("n_tiepoints_C_BAND", nTiepoints);
        Dimension feedDim    = new Dimension("n_feeds_C_BAND", nFeeds);
        Dimension samplesDim = new Dimension("n_samples_C_BAND", nSamplesOut);

        Group.Builder rootBuilder = Group.builder(null).setName("root");
        rootBuilder.addDimension(scanDim)
                .addDimension(tpDim)
                .addDimension(feedDim)
                .addDimension(samplesDim);

        ArrayDouble.D3 latData = new ArrayDouble.D3(nScans, nTiepoints, nFeeds);
        ArrayDouble.D3 lonData = new ArrayDouble.D3(nScans, nTiepoints, nFeeds);
        latData.set(0, 0, 0, 0.0);
        lonData.set(0, 0, 0, 0.0);

        Variable.Builder<?> latBuilder = Variable.builder()
                .setName("lat_c")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_tiepoints_C_BAND n_feeds_C_BAND")
                .setCachedData(latData, false);
        Variable.Builder<?> lonBuilder = Variable.builder()
                .setName("lon_c")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_tiepoints_C_BAND n_feeds_C_BAND")
                .setCachedData(lonData, false);

        rootBuilder.addVariable(latBuilder);
        rootBuilder.addVariable(lonBuilder);

        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(rootBuilder)
                .build();

        CimrDimensions dims = CimrDimensions.from(ncFile);
        String rootPath = ncFile.getRootGroup().getShortName();

        CimrBandDescriptor latDesc = new CimrBandDescriptor(
                "lat_c", "lat_c", CimrFrequencyBand.C_BAND,
                new String[]{},
                rootPath, 0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_tiepoints_C_BAND", "n_feeds_C_BAND"},
                "double"
        );

        CimrBandDescriptor varDesc = new CimrBandDescriptor(
                "altitude", "altitude", CimrFrequencyBand.C_BAND,
                new String[]{"lat_c", "lon_c"},
                rootPath, 0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double"
        );

        NetcdfCimrGeometryFactory factory = new NetcdfCimrGeometryFactory(ncFile, Collections.singletonList(latDesc), dims);

        factory.getOrCreateGeometry(varDesc);
    }

    @Test
    public void testGetOrCreateGeometry_UsesVariableFeedIndex() throws IOException, InvalidRangeException {
        int nScans = 2;
        int nTiepoints = 2;
        int nFeeds = 2;
        int nSamplesOut = 4;

        Dimension scanDim    = new Dimension("n_scans", nScans);
        Dimension tpDim      = new Dimension("n_tiepoints_C_BAND", nTiepoints);
        Dimension feedDim    = new Dimension("n_feeds_C_BAND", nFeeds);
        Dimension samplesDim = new Dimension("n_samples_C_BAND", nSamplesOut);

        Group.Builder rootBuilder = Group.builder(null).setName("root");
        rootBuilder.addDimension(scanDim)
                .addDimension(tpDim)
                .addDimension(feedDim)
                .addDimension(samplesDim);

        ArrayDouble.D3 latData = new ArrayDouble.D3(nScans, nTiepoints, nFeeds);
        ArrayDouble.D3 lonData = new ArrayDouble.D3(nScans, nTiepoints, nFeeds);
        latData.set(0, 0, 0, 1.0);
        lonData.set(0, 0, 0, 2.0);
        latData.set(0, 0, 1, 10.0);
        lonData.set(0, 0, 1, 20.0);

        Variable.Builder<?> latBuilder = Variable.builder()
                .setName("lat_c")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_tiepoints_C_BAND n_feeds_C_BAND")
                .setCachedData(latData, false);
        Variable.Builder<?> lonBuilder = Variable.builder()
                .setName("lon_c")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_tiepoints_C_BAND n_feeds_C_BAND")
                .setCachedData(lonData, false);

        rootBuilder.addVariable(latBuilder);
        rootBuilder.addVariable(lonBuilder);

        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(rootBuilder)
                .build();

        CimrDimensions dims = CimrDimensions.from(ncFile);
        String rootPath = "/";

        CimrBandDescriptor latDesc = new CimrBandDescriptor(
                "lat_c", "lat_c", CimrFrequencyBand.C_BAND,
                new String[]{},
                rootPath, 0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_tiepoints_C_BAND", "n_feeds_C_BAND"},
                "double"
        );
        CimrBandDescriptor lonDesc = new CimrBandDescriptor(
                "lon_c", "lon_c", CimrFrequencyBand.C_BAND,
                new String[]{},
                rootPath, 0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_tiepoints_C_BAND", "n_feeds_C_BAND"},
                "double"
        );

        CimrBandDescriptor varDesc = new CimrBandDescriptor(
                "altitude", "altitude", CimrFrequencyBand.C_BAND,
                new String[]{"lat_c", "lon_c"},
                rootPath, 1, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double"
        );

        NetcdfCimrGeometryFactory factory = new NetcdfCimrGeometryFactory(ncFile, Arrays.asList(latDesc, lonDesc), dims);

        CimrGeometry geom = factory.getOrCreateGeometry(varDesc);
        GeoPos p = geom.getGeoPos(0, 0, 0);
        assertEquals(10.0, p.getLat(), doubleErr);
        assertEquals(20.0, p.getLon(), doubleErr);
    }
}