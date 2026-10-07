package eu.esa.snap.cimr.netcdf;

import eu.esa.snap.cimr.dddb.descriptor.CimrBandDescriptor;
import eu.esa.snap.cimr.grid.CimrBoundingBox;
import eu.esa.snap.cimr.grid.CimrTiepointGeometry;
import eu.esa.snap.cimr.grid.CimrGeometry;
import org.esa.snap.core.datamodel.GeoPos;
import ucar.ma2.Array;
import ucar.ma2.Index3D;
import ucar.ma2.IndexIterator;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Group;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class NetcdfCimrGeometryFactory {


    private static final String LATITUDE_VARIABLE_NAME = "latitude";
    private static final String LONGITUDE_VARIABLE_NAME = "longitude";

    private final NetcdfFile ncFile;
    private final CimrDimensions dimensions;
    private final Map<String, CimrBandDescriptor> geometryByName = new HashMap<>();
    private final Map<String, CimrGeometry> cache = new HashMap<>();


    public NetcdfCimrGeometryFactory(NetcdfFile ncFile, List<CimrBandDescriptor> geometryDescriptors, CimrDimensions dimensions) {
        this.ncFile = ncFile;
        this.dimensions = dimensions;
        for (CimrBandDescriptor d : geometryDescriptors) {
            this.geometryByName.put(d.getName(), d);
        }
    }


    public CimrGeometry getOrCreateGeometry(CimrBandDescriptor variableDesc) throws IOException, InvalidRangeException {
        CimrGeometry geometry = this.cache.get(key(variableDesc));
        if (geometry != null) {
            return geometry;
        }

        String[] geomNames = variableDesc.getGeometryNames();
        if (geomNames == null || geomNames.length != 2) {
            throw new IllegalStateException(
                    "Descriptor '" + variableDesc.getName() + "' must define exactly two geometryNames (lat, lon)");
        }

        CimrBandDescriptor latDesc = geometryByName.get(geomNames[0]);
        CimrBandDescriptor lonDesc = geometryByName.get(geomNames[1]);

        if (latDesc == null || lonDesc == null) {
            throw new IllegalStateException("Geometry descriptors not found for variable '" + variableDesc.getName() + "': expected '" + geomNames[0] + "' and '" + geomNames[1] + "'");
        }

        Group latGroup = NcUtil.findGroupOrThrow(this.ncFile, latDesc.getGroupPath());
        Group lonGroup = NcUtil.findGroupOrThrow(this.ncFile, lonDesc.getGroupPath());
        Variable latVar = NcUtil.findVarOrThrow(latGroup, latDesc.getValueVarName());
        Variable lonVar = NcUtil.findVarOrThrow(lonGroup, lonDesc.getValueVarName());

        int nScans     = dimensions.get(latDesc.getDimensions()[0]);
        int nTiePoints = dimensions.get(latDesc.getDimensions()[1]);

        int feedIndex = variableDesc.getFeedIndex();

        int[] origin = {0, 0, feedIndex};
        int[] shape  = {nScans, nTiePoints, 1};

        Array latData;
        Array lonData;
        synchronized (this.ncFile) {
            lonData = lonVar.read(origin, shape);
            latData = latVar.read(origin, shape);
        }


        GeoPos[][][] tiePoints = new GeoPos[nScans][nTiePoints][1];
        Index3D idx = new Index3D(new int[]{nScans, nTiePoints, 1});

        for (int s = 0; s < nScans; s++) {
            for (int tp = 0; tp < nTiePoints; tp++) {
                idx.set(s, tp, 0);
                double lat = latData.getDouble(idx);
                double lon = lonData.getDouble(idx);
                tiePoints[s][tp][0] = new GeoPos(lat, ensureLongitude(lon));
            }
        }

        int sampleCount = getSampleCount(variableDesc);
        geometry = new CimrTiepointGeometry(tiePoints, sampleCount);
        this.cache.put(key(variableDesc), geometry);

        return geometry;
    }


    private String key(CimrBandDescriptor d) {
        return d.getBand().name() + "#" + d.getFeedIndex();
    }

    private int getSampleCount(CimrBandDescriptor d) {
        return dimensions.get("n_samples_" + d.getBand());
    }

    public void clearCache() {
        this.cache.clear();
    }

    private double ensureLongitude(double lon) {
        while (lon > 180.0) lon -= 360.0;
        while (lon <= -180.0) lon += 360.0;
        return lon;
    }

    public CimrBoundingBox getBoundingBox(CimrBandDescriptor variableDesc, double cellSizeDeg) throws InvalidRangeException, IOException {
        CimrGeometry bbGeometry = getOrCreateGeometry(variableDesc);
        return CimrBoundingBox.create(bbGeometry, cellSizeDeg);
    }

    public CimrBoundingBox getBoundingBox(double cellSizeDeg) throws IOException {
        Map<String, CimrBandDescriptor> latitudesByBand = new LinkedHashMap<>();
        Map<String, CimrBandDescriptor> longitudesByBand = new LinkedHashMap<>();

        for (CimrBandDescriptor descriptor : geometryByName.values()) {
            String bandName = descriptor.getBand().name();
            if (LATITUDE_VARIABLE_NAME.equals(descriptor.getValueVarName())) {
                latitudesByBand.putIfAbsent(bandName, descriptor);
            } else if (LONGITUDE_VARIABLE_NAME.equals(descriptor.getValueVarName())) {
                longitudesByBand.putIfAbsent(bandName, descriptor);
            }
        }

        double lonMin = Double.POSITIVE_INFINITY;
        double lonMax = Double.NEGATIVE_INFINITY;
        double latMin = Double.POSITIVE_INFINITY;
        double latMax = Double.NEGATIVE_INFINITY;

        for (Map.Entry<String, CimrBandDescriptor> entry : latitudesByBand.entrySet()) {
            String bandName = entry.getKey();
            CimrBandDescriptor latDesc = entry.getValue();
            CimrBandDescriptor lonDesc = longitudesByBand.get(bandName);
            if (lonDesc == null) {
                throw new IllegalStateException("Longitude descriptor not found for band '" + bandName + "'");
            }

            Group latGroup = NcUtil.findGroupOrThrow(this.ncFile, latDesc.getGroupPath());
            Group lonGroup = NcUtil.findGroupOrThrow(this.ncFile, lonDesc.getGroupPath());
            Variable latVar = NcUtil.findVarOrThrow(latGroup, latDesc.getValueVarName());
            Variable lonVar = NcUtil.findVarOrThrow(lonGroup, lonDesc.getValueVarName());

            Array latData;
            Array lonData;
            synchronized (this.ncFile) {
                latData = latVar.read();
                lonData = lonVar.read();
            }

            IndexIterator latIterator = latData.getIndexIterator();
            IndexIterator lonIterator = lonData.getIndexIterator();
            while (latIterator.hasNext() && lonIterator.hasNext()) {
                double lat = latIterator.getDoubleNext();
                double lon = ensureLongitude(lonIterator.getDoubleNext());
                if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
                    continue;
                }
                if (lon < lonMin) {
                    lonMin = lon;
                }
                if (lon > lonMax) {
                    lonMax = lon;
                }
                if (lat < latMin) {
                    latMin = lat;
                }
                if (lat > latMax) {
                    latMax = lat;
                }
            }
        }

        if (!Double.isFinite(lonMin) || !Double.isFinite(lonMax) || !Double.isFinite(latMin) || !Double.isFinite(latMax)) {
            throw new IllegalStateException("No valid CIMR navigation points found for bounding box");
        }

        return CimrBoundingBox.create(lonMin, lonMax, latMin, latMax, cellSizeDeg);
    }
}
