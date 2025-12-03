package eu.esa.snap.cimr.netcdf;


import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDescriptorKind;
import eu.esa.snap.cimr.cimr.CimrDimensions;
import eu.esa.snap.cimr.cimr.CimrFrequencyBand;
import eu.esa.snap.cimr.grid.CimrGeometryBand;
import eu.esa.snap.cimr.grid.CimrTiepointGeometry;
import org.esa.snap.core.datamodel.GeoPos;
import org.junit.Test;
import ucar.ma2.ArrayDouble;
import ucar.ma2.DataType;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.*;

import java.io.IOException;

import static org.junit.Assert.*;


public class NetcdfCimrBandFactoryTest {


    private static final double doubleErr = 1e-6;


    @Test
    public void testCreateGeometryBand_NormalVariable() throws IOException, InvalidRangeException {
        int nScans   = 2;
        int nSamples = 3;
        int nFeeds   = 1;

        Dimension scanDim   = new Dimension("n_scans", nScans);
        Dimension sampleDim = new Dimension("n_samples_C_BAND", nSamples);
        Dimension feedDim   = new Dimension("n_feeds_C_BAND", nFeeds);

        Group.Builder root = Group.builder(null).setName("root");
        root.addDimension(scanDim).addDimension(sampleDim).addDimension(feedDim);

        Group.Builder dataGroup = Group.builder(root).setName("Data");
        root.addGroup(dataGroup);

        ArrayDouble.D3 data = new ArrayDouble.D3(nScans, nSamples, nFeeds);
        for (int s = 0; s < nScans; s++) {
            for (int smp = 0; smp < nSamples; smp++) {
                data.set(s, smp, 0, 100 * s + smp);
            }
        }

        Variable.Builder<?> varBuilder = Variable.builder()
                .setName("altitude")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_samples_C_BAND n_feeds_C_BAND")
                .setCachedData(data, false);
        dataGroup.addVariable(varBuilder);

        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(root)
                .build();

        CimrDimensions dims = CimrDimensions.from(ncFile);

        CimrBandDescriptor desc = new CimrBandDescriptor(
                "altitude",
                "altitude",
                CimrFrequencyBand.C_BAND,
                new String[] {},
                new String[] {},
                "/Data",
                0,
                CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double",
                "",
                ""
        );

        GeoPos[][][] tp = new GeoPos[nScans][nSamples][1];
        for (int s = 0; s < nScans; s++) {
            for (int smp = 0; smp < nSamples; smp++) {
                tp[s][smp][0] = new GeoPos(0f, 0f);
            }
        }
        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, nSamples);

        NetcdfCimrBandFactory factory = new NetcdfCimrBandFactory(ncFile, dims);
        CimrGeometryBand band = factory.createGeometryBand(desc, geom);

        assertEquals(nScans, band.getScanCount());
        assertEquals(nSamples, band.getSampleCount());

        assertEquals(0.0,   band.getValue(0, 0), doubleErr);
        assertEquals(2.0,   band.getValue(0, 2), doubleErr);
        assertEquals(100.0, band.getValue(1, 0), doubleErr);
        assertEquals(102.0, band.getValue(1, 2), doubleErr);
    }

    @Test
    public void testCreateGeometryBand_TiepointVariableInterpolates() throws IOException, InvalidRangeException {
        int nScans      = 1;
        int nTiepoints  = 2;
        int nFeeds      = 1;
        int nSamplesOut = 4;

        Dimension scanDim     = new Dimension("n_scans", nScans);
        Dimension tpDim       = new Dimension("n_tiepoints_C_BAND", nTiepoints);
        Dimension feedDim     = new Dimension("n_feeds_C_BAND", nFeeds);
        Dimension samplesDim  = new Dimension("n_samples_C_BAND", nSamplesOut);

        Group.Builder root = Group.builder(null).setName("root");
        root.addDimension(scanDim)
                .addDimension(tpDim)
                .addDimension(feedDim)
                .addDimension(samplesDim);

        Group.Builder dataGroup = Group.builder(root).setName("Data");
        root.addGroup(dataGroup);

        ArrayDouble.D3 data = new ArrayDouble.D3(nScans, nTiepoints, nFeeds);
        data.set(0, 0, 0, 0.0);
        data.set(0, 1, 0, 10.0);

        Variable.Builder<?> varBuilder = Variable.builder()
                .setName("tie_var")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_tiepoints_C_BAND n_feeds_C_BAND")
                .setCachedData(data, false);
        dataGroup.addVariable(varBuilder);

        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(root)
                .build();

        CimrDimensions dims = CimrDimensions.from(ncFile);

        CimrBandDescriptor desc = new CimrBandDescriptor(
                "tie_var",
                "tie_var",
                CimrFrequencyBand.C_BAND,
                new String[]{},
                new String[] {},
                "/Data",
                0,
                CimrDescriptorKind.TIEPOINT_VARIABLE,
                new String[]{"n_scans", "n_tiepoints_C_BAND", "n_feeds_C_BAND"},
                "double",
                "",
                ""
        );

        GeoPos[][][] tp = new GeoPos[nScans][nTiepoints][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        tp[0][1][0] = new GeoPos(0f, 10f);
        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, nSamplesOut);

        NetcdfCimrBandFactory factory = new NetcdfCimrBandFactory(ncFile, dims);
        CimrGeometryBand band = factory.createGeometryBand(desc, geom);

        assertEquals(nScans, band.getScanCount());
        assertEquals(nSamplesOut, band.getSampleCount());

        assertEquals(0.0,       band.getValue(0, 0), doubleErr);
        assertEquals(10.0,      band.getValue(0, 3), doubleErr);
        assertEquals(10.0 / 3,  band.getValue(0, 1), doubleErr);
        assertEquals(20.0 / 3,  band.getValue(0, 2), doubleErr);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateGeometryBand_FailsForNon3DVariable() throws IOException, InvalidRangeException {
        int nScans   = 1;
        int nSamples = 2;

        Dimension scanDim   = new Dimension("n_scans", nScans);
        Dimension sampleDim = new Dimension("n_samples_C_BAND", nSamples);
        Dimension feedDim   = new Dimension("n_feeds_C_BAND", 1);

        Group.Builder root = Group.builder(null).setName("root");
        root.addDimension(scanDim).addDimension(sampleDim).addDimension(feedDim);

        Group.Builder dataGroup = Group.builder(root).setName("Data");
        root.addGroup(dataGroup);

        ArrayDouble.D2 data = new ArrayDouble.D2(nScans, nSamples);
        data.set(0, 0, 1.0);
        data.set(0, 1, 2.0);

        Variable.Builder<?> varBuilder = Variable.builder()
                .setName("bad_var")
                .setDataType(DataType.DOUBLE)
                .setDimensionsByName("n_scans n_samples_C_BAND")
                .setCachedData(data, false);
        dataGroup.addVariable(varBuilder);

        NetcdfFile ncFile = NetcdfFile.builder()
                .setLocation("test")
                .setRootGroup(root)
                .build();

        CimrDimensions dims = CimrDimensions.from(ncFile);

        CimrBandDescriptor desc = new CimrBandDescriptor(
                "bad_var",
                "bad_var",
                CimrFrequencyBand.C_BAND,
                new String[]{},
                new String[] {},
                "/Data",
                0,
                CimrDescriptorKind.VARIABLE,
                new String[]{"n_scans", "n_samples_C_BAND", "n_feeds_C_BAND"},
                "double",
                "",
                ""
        );

        GeoPos[][][] tp = new GeoPos[1][2][1];
        tp[0][0][0] = new GeoPos(0f, 0f);
        tp[0][1][0] = new GeoPos(0f, 1f);
        CimrTiepointGeometry geom = new CimrTiepointGeometry(tp, nSamples);

        NetcdfCimrBandFactory factory = new NetcdfCimrBandFactory(ncFile, dims);
        factory.createGeometryBand(desc, geom);
    }
}