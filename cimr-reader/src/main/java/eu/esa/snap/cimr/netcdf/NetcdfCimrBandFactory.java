package eu.esa.snap.cimr.netcdf;

import eu.esa.snap.cimr.cimr.CimrBandDescriptor;
import eu.esa.snap.cimr.cimr.CimrDescriptorKind;
import eu.esa.snap.cimr.cimr.CimrDimensions;
import eu.esa.snap.cimr.grid.CimrGeometry;
import eu.esa.snap.cimr.grid.CimrGeometryBand;
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

        if (var.getRank() != 3) {
            throw new IllegalArgumentException("Expected 3D variable for '"
                    + desc.getValueVarName() + "', but rank=" + var.getRank());
        }

        int nScans   = dimensions.get(desc.getDimensions()[0]);
        int nSamples = dimensions.get(desc.getDimensions()[1]);
        int feedIdx = desc.getFeedIndex();

        int[] origin = new int[] {0, 0, feedIdx};
        int[] shape  = new int[] {nScans, nSamples, 1};

        final Array data;
        synchronized (this.ncFile) {
            data = var.read(origin, shape);
        }

        double[][] values;
        Index3D idx = new Index3D(data.getShape());

        if (desc.getKind() == CimrDescriptorKind.TIEPOINT_VARIABLE) {
            int sampleCount = getSampleCount(desc);
            values = new double[nScans][sampleCount];

            // TODO extract Tiepoint interpolation
            for (int s = 0; s < nScans; s++) {
                for (int smp = 0; smp < sampleCount; smp++) {
                    double t  = (double) smp * (nSamples - 1) / (double) (sampleCount - 1);
                    int tp0   = (int) Math.floor(t);
                    int tp1   = Math.min(tp0 + 1, nSamples - 1);
                    double f  = t - tp0;

                    idx.set(s, tp0, 0);
                    double v0 = data.getDouble(idx);
                    idx.set(s, tp1, 0);
                    double v1 = data.getDouble(idx);

                    values[s][smp] = v0 + f * (v1 - v0);
                }
            }
        } else {
            values = new double[nScans][nSamples];

            for (int s = 0; s < nScans; s++) {
                for (int smp = 0; smp < nSamples; smp++) {
                    idx.set(s, smp, 0);
                    values[s][smp] = data.getDouble(idx);
                }
            }
        }

        return new CimrGeometryBand(values, geometry, feedIdx);
    }

    private int getSampleCount(CimrBandDescriptor d) {
        return dimensions.get("n_samples_" + d.getBand());
    }
}
