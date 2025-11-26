package eu.esa.snap.cimr;

import eu.esa.snap.cimr.cimr.*;
import eu.esa.snap.cimr.grid.*;
import eu.esa.snap.cimr.netcdf.NetcdfCimrBandFactory;
import eu.esa.snap.cimr.netcdf.NetcdfCimrGeometryFactory;
import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;
import ucar.nc2.NetcdfFile;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;


public class CimrReaderContextTest {

    private static final double doubleErr = 1e-6;


    @Test
    public void testConstructorAndGetters() {
        GlobalGrid grid = createTestGrid();
        CimrDescriptorSet descriptorSet = createEmptyDescriptorSet();

        NetcdfCimrGeometryFactory geomFactory = new NetcdfCimrGeometryFactory(null, Collections.emptyList(), null);
        NetcdfCimrBandFactory bandFactory = new NetcdfCimrBandFactory(null, null);

        CimrReaderContext ctx = new CimrReaderContext(
                null,
                descriptorSet,
                grid,
                geomFactory,
                bandFactory
        );

        assertSame(grid, ctx.getGlobalGrid());
        assertSame(descriptorSet, ctx.getDescriptorSet());
    }

    @Test
    public void testGetOrCreateGeometry_DelegatesAndWrapsCheckedException() {
        GlobalGrid grid = createTestGrid();
        CimrDescriptorSet descriptorSet = createEmptyDescriptorSet();
        CimrBandDescriptor desc = createTestDescriptor();

        NetcdfCimrGeometryFactory geomFactory = new NetcdfCimrGeometryFactory(null, Collections.emptyList(), null) {
            @Override
            public CimrGeometry getOrCreateGeometry(CimrBandDescriptor variableDesc)
                    throws IOException {
                throw new IOException("boom-geo");
            }
        };
        NetcdfCimrBandFactory bandFactory = new NetcdfCimrBandFactory(null, null);
        CimrReaderContext ctx = new CimrReaderContext(
                null,
                descriptorSet,
                grid,
                geomFactory,
                bandFactory
        );

        try {
            ctx.getOrCreateGeometry(desc);
            fail("Expected RuntimeException");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Failed to build geometry for variable testVar"));
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof IOException);
            assertEquals("boom-geo", e.getCause().getMessage());
        }
    }

    @Test
    public void testGetOrCreateGridForVariable_BuildsAndCachesOnce() {
        GlobalGrid grid = createTestGrid();
        CimrDescriptorSet descriptorSet = createEmptyDescriptorSet();
        CimrBandDescriptor desc = createTestDescriptor();

        CimrGeometry stubGeom = createStubGeometry();

        AtomicInteger geomCalls = new AtomicInteger();
        AtomicInteger bandCalls = new AtomicInteger();

        NetcdfCimrGeometryFactory geomFactory = new NetcdfCimrGeometryFactory(null, Collections.emptyList(), null) {
            @Override
            public CimrGeometry getOrCreateGeometry(CimrBandDescriptor variableDesc){
                geomCalls.incrementAndGet();
                return stubGeom;
            }
        };

        NetcdfCimrBandFactory bandFactory = new NetcdfCimrBandFactory(null, null) {
            @Override
            public CimrGeometryBand createGeometryBand(CimrBandDescriptor desc, CimrGeometry geometry) {
                bandCalls.incrementAndGet();
                double[][] values = {{42.0, 42.0}};
                return new CimrGeometryBand(values, geometry, desc.getFeedIndex());
            }
        };

        CimrReaderContext ctx = new CimrReaderContext(
                null,
                descriptorSet,
                grid,
                geomFactory,
                bandFactory
        );

        GridBandDataSource grid1 = ctx.getOrCreateGridForVariable(desc, true);
        GridBandDataSource grid2 = ctx.getOrCreateGridForVariable(desc, false);

        assertSame(grid1, grid2);

        assertEquals(1, geomCalls.get());
        assertEquals(1, bandCalls.get());

        assertEquals(42.0, grid1.getSample(0, 0), doubleErr);
    }

    @Test
    public void testGetOrCreateGridForVariable_WrapsBandFactoryCheckedException() {
        GlobalGrid grid = createTestGrid();
        CimrDescriptorSet descriptorSet = createEmptyDescriptorSet();
        CimrBandDescriptor desc = createTestDescriptor();

        CimrGeometry stubGeom = createStubGeometry();

        NetcdfCimrGeometryFactory geomFactory = new NetcdfCimrGeometryFactory(null, Collections.emptyList(), null) {
            @Override
            public CimrGeometry getOrCreateGeometry(CimrBandDescriptor variableDesc) {
                return stubGeom;
            }
        };

        NetcdfCimrBandFactory bandFactory = new NetcdfCimrBandFactory(null, null) {
            @Override
            public CimrGeometryBand createGeometryBand(CimrBandDescriptor desc, CimrGeometry geometry)
                    throws IOException {
                throw new IOException("boom-band");
            }
        };
        CimrReaderContext ctx = new CimrReaderContext(
                null,
                descriptorSet,
                grid,
                geomFactory,
                bandFactory
        );

        try {
            ctx.getOrCreateGridForVariable(desc, true);
            fail("Expected RuntimeException");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Failed to build grid for variable testVar"));
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof IOException);
            assertEquals("boom-band", e.getCause().getMessage());
        }
    }

    @Test
    public void testGetOrCreateGridForVariable_PropagatesGeometryRuntimeException() {
        GlobalGrid grid = createTestGrid();
        CimrDescriptorSet descriptorSet = createEmptyDescriptorSet();
        CimrBandDescriptor desc = createTestDescriptor();

        NetcdfCimrGeometryFactory geomFactory = new NetcdfCimrGeometryFactory(null, Collections.emptyList(), null) {
            @Override
            public CimrGeometry getOrCreateGeometry(CimrBandDescriptor variableDesc)
                    throws IOException {
                throw new IOException("boom-geo");
            }
        };
        NetcdfCimrBandFactory bandFactory = new NetcdfCimrBandFactory(null, null);
        CimrReaderContext ctx = new CimrReaderContext(
                null,
                descriptorSet,
                grid,
                geomFactory,
                bandFactory
        );

        RuntimeException geoEx;
        try {
            ctx.getOrCreateGeometry(desc);
            fail("Expected RuntimeException from getOrCreateGeometry");
            return;
        } catch (RuntimeException e) {
            geoEx = e;
            assertTrue(e.getMessage().contains("Failed to build geometry for variable testVar"));
            assertTrue(e.getCause() instanceof IOException);
        }

        try {
            ctx.getOrCreateGridForVariable(desc, true);
            fail("Expected RuntimeException from getOrCreateGridForVariable");
        } catch (RuntimeException e) {
            assertEquals(geoEx.getMessage(), e.getMessage());
            assertNotNull(e.getCause());
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    @Test
    public void testClearCache_clearsBandCacheAndGeometryCache() {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                2, 1,
                0.0, 0.0,
                1.0, 1.0
        );
        GlobalGrid grid = new GlobalGrid(proj, 2, 1);

        CimrBandDescriptor varDesc = new CimrBandDescriptor(
                "testVar", "v", CimrFrequencyBand.C_BAND,
                new String[]{"lat", "lon"},
                "/Data", 0, CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double", "", ""
        );
        CimrDescriptorSet descriptorSet = new CimrDescriptorSet(
                Collections.singletonList(varDesc),
                Collections.emptyList(),
                Collections.emptyList()
        );

        NetcdfFile ncFile = NetcdfFile.builder().setLocation("dummy").build();
        CimrDimensions dims = new CimrDimensions(Collections.emptyMap());

        class CountingGeometryFactory extends NetcdfCimrGeometryFactory {
            int getCalls = 0;
            int clearCalls = 0;

            CountingGeometryFactory() {
                super(ncFile, Collections.emptyList(), dims);
            }

            @Override
            public CimrGeometry getOrCreateGeometry(CimrBandDescriptor d) {
                getCalls++;
                return createStubGeometry();
            }

            @Override
            public void clearCache() {
                clearCalls++;
                super.clearCache();
            }
        }

        class CountingBandFactory extends NetcdfCimrBandFactory {
            int calls = 0;

            CountingBandFactory() {
                super(ncFile, dims);
            }

            @Override
            public CimrGeometryBand createGeometryBand(CimrBandDescriptor desc, CimrGeometry geometry) {
                calls++;
                double[][] values = {{1.0, 2.0}};
                return new CimrGeometryBand(values, geometry, desc.getFeedIndex());
            }
        }

        CountingGeometryFactory geomFactory = new CountingGeometryFactory();
        CountingBandFactory bandFactory = new CountingBandFactory();

        CimrReaderContext ctx = new CimrReaderContext(ncFile, descriptorSet, grid, geomFactory, bandFactory);

        GridBandDataSource ds1 = ctx.getOrCreateGridForVariable(varDesc, true);
        GridBandDataSource ds2 = ctx.getOrCreateGridForVariable(varDesc, true);

        assertSame(ds1, ds2);
        assertEquals(1, geomFactory.getCalls);
        assertEquals(1, bandFactory.calls);

        ctx.clearCache();
        assertEquals(1, geomFactory.clearCalls);

        GridBandDataSource ds3 = ctx.getOrCreateGridForVariable(varDesc, true);
        assertNotSame(ds1, ds3);
        assertEquals(2, geomFactory.getCalls);
        assertEquals(2, bandFactory.calls);
    }



    private GlobalGrid createTestGrid() {
        PlateCarreeProjection proj = new PlateCarreeProjection(
                1, 1,
                -0.5, 0.5,
                1.0, 1.0
        );
        return new GlobalGrid(proj, 1, 1);
    }

    private CimrBandDescriptor createTestDescriptor() {
        return new CimrBandDescriptor(
                "testVar",
                "testVar",
                CimrFrequencyBand.C_BAND,
                new String[]{"lat", "lon"},
                "/dummy",
                0,
                CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double",
                "",
                ""
        );
    }

    private CimrDescriptorSet createEmptyDescriptorSet() {
        return new CimrDescriptorSet(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private CimrGeometry createStubGeometry() {
        GeoPos[][][] tp = new GeoPos[1][2][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        tp[0][1][0] = new GeoPos(0f, 1f);
        return new CimrTiepointGeometry(tp, 2);
    }
}