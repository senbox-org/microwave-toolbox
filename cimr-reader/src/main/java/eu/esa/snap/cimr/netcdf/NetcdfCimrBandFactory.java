package eu.esa.snap.cimr.netcdf;

import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.dddb.descriptor.CimrDescriptorKind;
import eu.esa.snap.cimr.grid.CimrGeometry;
import eu.esa.snap.cimr.grid.CimrGeometryBand;
import eu.esa.snap.cimr.grid.CimrSampleBuffer;
import eu.esa.snap.cimr.grid.TiepointInterpolator;
import ucar.ma2.Array;
import ucar.ma2.Index3D;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.IOException;


public class NetcdfCimrBandFactory {

    private final NetcdfFile ncFile;
    private final CimrDimensions dimensions;


    public NetcdfCimrBandFactory(NetcdfFile ncFile, CimrDimensions dimensions) {
        this.ncFile = ncFile;
        this.dimensions = dimensions;
    }


    public CimrGeometryBand createGeometryBand(CimrBandDescriptor desc, CimrGeometry geometry) throws IOException, InvalidRangeException {

        Group group = NcUtil.findGroupOrThrow(this.ncFile, desc.getGroupPath());
        Variable var = NcUtil.findVarOrThrow(group, desc.getValueVarName());

        if (var.getRank() == 2 && desc.getKind() == CimrDescriptorKind.TIEPOINT_VARIABLE) {
            throw new IllegalArgumentException("Expected 3D tie-point variable for '"
                    + desc.getValueVarName() + "', but rank=" + var.getRank());
        }
        if (var.getRank() != 2 && var.getRank() != 3) {
            throw new IllegalArgumentException("Expected 2D or 3D variable for '"
                    + desc.getValueVarName() + "', but rank=" + var.getRank());
        }

        int nScans   = dimensions.get(desc.getDimensions()[0]);
        int nSamples = dimensions.get(desc.getDimensions()[1]);
        int feedIdx = desc.getFeedIndex();

        int[] origin;
        int[] shape;
        if (var.getRank() == 2) {
            origin = new int[] {0, 0};
            shape  = new int[] {nScans, nSamples};
        } else {
            origin = new int[] {0, 0, feedIdx};
            shape  = new int[] {nScans, nSamples, 1};
        }

        final Array data;
        synchronized (this.ncFile) {
            data = var.read(origin, shape);
        }

        if (desc.getKind() == CimrDescriptorKind.TIEPOINT_VARIABLE) {
            int outputSampleCount = getSampleCount(desc);
            return new CimrGeometryBand(readTiepointValues(desc, nScans, nSamples, data), nScans, outputSampleCount, geometry, feedIdx);
        }
        return new CimrGeometryBand(readSampleValues(nScans, nSamples, data), nScans, nSamples, geometry, feedIdx);
    }

    private CimrSampleBuffer readTiepointValues(CimrBandDescriptor desc, int nScans, int nTiepoints, Array data) {
        int sampleCount = getSampleCount(desc);
        CimrSampleBuffer values = CimrSampleBuffer.create(getRasterDataType(data), nScans * sampleCount);
        Index3D idx = new Index3D(data.getShape());

        for (int s = 0; s < nScans; s++) {
            for (int smp = 0; smp < sampleCount; smp++) {
                TiepointInterpolator.Position position = TiepointInterpolator.position(smp, sampleCount, nTiepoints);

                idx.set(s, position.getLowerIndex(), 0);
                double v0 = data.getDouble(idx);
                idx.set(s, position.getUpperIndex(), 0);
                double v1 = data.getDouble(idx);

                values.set(s * sampleCount + smp, TiepointInterpolator.interpolate(v0, v1, position.getFraction()));
            }
        }
        return values;
    }

    private CimrSampleBuffer readSampleValues(int nScans, int nSamples, Array data) {
        CimrSampleBuffer values = CimrSampleBuffer.fromNetcdfArray(data);
        if (values.size() != nScans * nSamples) {
            throw new IllegalArgumentException("Unexpected sample value count: " + values.size());
        }
        return values;
    }

    private int getRasterDataType(Array data) {
        return CimrSampleBuffer.getRasterDataType(data);
    }

    private int getSampleCount(CimrBandDescriptor d) {
        return dimensions.get("n_samples_" + d.getBand());
    }
}
