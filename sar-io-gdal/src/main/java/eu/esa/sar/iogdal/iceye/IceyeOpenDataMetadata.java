/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package eu.esa.sar.iogdal.iceye;

import eu.esa.sar.commons.product.Missions;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.datamodel.ProductData.UTC;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.OrbitStateVector;
import org.esa.snap.engine_utilities.eo.Constants;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * The ICEYE Open Data STAC property set, and its mapping onto SNAP's abstracted
 * metadata.
 * <p>
 * Deliberately free of TIFF, GDAL and {@code Product}: the delivery carries the
 * same property object twice - once as the side-car STAC item and once in the
 * TIFF's {@code ICEYE_PROPERTIES} GDAL metadata item - so the mapping is worth
 * exercising on its own.
 * <p>
 * Note the axis convention. {@code proj:shape} is {@code [azimuth, range]},
 * matching the TIFF's {@code [ImageWidth, ImageLength]}, which is the transpose
 * of SNAP's raster. {@link #getRangeSamples()} and {@link #getAzimuthLines()}
 * name the axes rather than the storage order so callers cannot mix them up.
 *
 * @author Luis Veci
 */
public class IceyeOpenDataMetadata {

    private static final DateFormat dateFormat = UTC.createDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    private static final int MAX_FRACTION_DIGITS = 6;

    private final JSONObject properties;

    private IceyeOpenDataMetadata(final JSONObject properties) {
        this.properties = properties;
    }

    public static IceyeOpenDataMetadata fromProperties(final JSONObject properties) {
        return new IceyeOpenDataMetadata(properties == null ? new JSONObject() : properties);
    }

    /** Reads a side-car STAC item and keeps its {@code properties} object. */
    public static IceyeOpenDataMetadata fromStacItem(final File stacItem) throws IOException {
        try (Reader reader = Files.newBufferedReader(stacItem.toPath())) {
            final JSONObject item = (JSONObject) new JSONParser().parse(reader);
            final Object props = item.get(IceyeOpenDataConstants.STAC_PROPERTIES);
            if (!(props instanceof JSONObject)) {
                throw new IOException(stacItem.getName() + " is not a STAC item: no 'properties' object");
            }
            return new IceyeOpenDataMetadata((JSONObject) props);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to parse " + stacItem.getName() + ": " + e.getMessage(), e);
        }
    }

    /** Parses the {@code ICEYE_PROPERTIES} blob, which is the properties object on its own. */
    public static IceyeOpenDataMetadata fromPropertiesJSON(final String json) throws IOException {
        try {
            return new IceyeOpenDataMetadata((JSONObject) new JSONParser().parse(json));
        } catch (Exception e) {
            throw new IOException("Unable to parse ICEYE_PROPERTIES: " + e.getMessage(), e);
        }
    }

    public JSONObject getProperties() {
        return properties;
    }

    // ------------------------------------------------------------------
    // typed access
    // ------------------------------------------------------------------

    private Object get(final String key) {
        return properties.get(key);
    }

    public String getString(final String key) {
        final Object value = get(key);
        return value == null ? null : value.toString();
    }

    public Double getDouble(final String key) {
        final Object value = get(key);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    public Integer getInt(final String key) {
        final Object value = get(key);
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    public double[] getDoubles(final String key) {
        return toDoubles(get(key));
    }

    private static double[] toDoubles(final Object value) {
        if (!(value instanceof JSONArray)) {
            return new double[0];
        }
        final JSONArray array = (JSONArray) value;
        final double[] values = new double[array.size()];
        for (int i = 0; i < values.length; ++i) {
            final Object element = array.get(i);
            values[i] = element instanceof Number ? ((Number) element).doubleValue() : 0.0;
        }
        return values;
    }

    /**
     * STAC timestamps are RFC 3339 with a trailing Z and up to seven fractional
     * digits; {@link UTC#parse} accepts at most six, so the tail is dropped
     * rather than letting the whole timestamp fail.
     */
    public static UTC parseUTC(final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String value = text.endsWith("Z") ? text.substring(0, text.length() - 1) : text;
        final int dot = value.lastIndexOf('.');
        if (dot > 0 && value.length() - dot - 1 > MAX_FRACTION_DIGITS) {
            value = value.substring(0, dot + 1 + MAX_FRACTION_DIGITS);
        }
        try {
            return UTC.parse(value, dateFormat);
        } catch (Exception e) {
            SystemUtils.LOG.warning("ICEYE: unable to parse timestamp '" + text + '\'');
            return null;
        }
    }

    public UTC getUTC(final String key) {
        return parseUTC(getString(key));
    }

    // ------------------------------------------------------------------
    // identification
    // ------------------------------------------------------------------

    public String getProductType() {
        return getString(IceyeOpenDataConstants.product_type);
    }

    public String getProductName() {
        final String fileName = getString(IceyeOpenDataConstants.filename);
        if (fileName == null) {
            return null;
        }
        final int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    public String getPlatform() {
        return getString(IceyeOpenDataConstants.platform);
    }

    /** ICEYE writes "spot"/"strip" on some products and the long names on others. */
    public String getAcquisitionMode() {
        final String mode = getString(IceyeOpenDataConstants.acquisition_mode);
        if (IceyeOpenDataConstants.spot.equalsIgnoreCase(mode)) {
            return IceyeConstants.spotlight;
        }
        if (IceyeOpenDataConstants.strip.equalsIgnoreCase(mode)) {
            return IceyeConstants.stripmap;
        }
        return mode;
    }

    public String getPolarization() {
        final Object value = get(IceyeOpenDataConstants.polarizations);
        if (value instanceof JSONArray && !((JSONArray) value).isEmpty()) {
            return String.valueOf(((JSONArray) value).get(0));
        }
        return value == null ? null : value.toString();
    }

    /** ICEYE flies both ways; the look side sets which way azimuth runs in the raster. */
    public boolean isLookLeft() {
        return IceyeOpenDataConstants.left.equalsIgnoreCase(
                getString(IceyeOpenDataConstants.observation_direction));
    }

    public boolean isComplex() {
        final String productType = getProductType();
        return productType != null && productType.toUpperCase().startsWith(IceyeOpenDataConstants.SLC);
    }

    // ------------------------------------------------------------------
    // raster geometry
    // ------------------------------------------------------------------

    private int shape(final int index) {
        final double[] shape = getDoubles(IceyeOpenDataConstants.proj_shape);
        return shape.length > index ? (int) shape[index] : 0;
    }

    /** Range samples - SNAP's raster width, and the TIFF's ImageLength. */
    public int getRangeSamples() {
        return shape(1);
    }

    /** Azimuth lines - SNAP's raster height, and the TIFF's ImageWidth. */
    public int getAzimuthLines() {
        return shape(0);
    }

    /**
     * {@code [lon0, dLon/dColumn, dLon/dRow, lat0, dLat/dColumn, dLat/dRow]},
     * where column is the azimuth axis and row the range axis.
     */
    public double[] getGeoTransform() {
        return getDoubles(IceyeOpenDataConstants.proj_transform);
    }

    public double getLongitude(final double column, final double row) {
        final double[] t = getGeoTransform();
        return t.length < 6 ? 0.0 : t[0] + t[1] * column + t[2] * row;
    }

    public double getLatitude(final double column, final double row) {
        final double[] t = getGeoTransform();
        return t.length < 6 ? 0.0 : t[3] + t[4] * column + t[5] * row;
    }

    /**
     * The TIFF column holding a given SNAP raster position. Range runs down the
     * TIFF rows either way, so the row is simply x; the column direction depends
     * on the look side. See {@link IceyeTransposedMultiLevelSource}.
     */
    public int tiffColumn(final int y, final int azimuthLines) {
        return isLookLeft() ? azimuthLines - 1 - y : y;
    }

    /** Scene corner latitudes in SNAP order: upper left, upper right, lower left, lower right. */
    public double[] getCornerLatitudes(final int rangeSamples, final int azimuthLines) {
        final int firstLine = tiffColumn(0, azimuthLines);
        final int lastLine = tiffColumn(azimuthLines - 1, azimuthLines);
        final int lastSample = rangeSamples - 1;
        return new double[]{
                getLatitude(firstLine, 0), getLatitude(firstLine, lastSample),
                getLatitude(lastLine, 0), getLatitude(lastLine, lastSample)};
    }

    /** Scene corner longitudes in SNAP order: upper left, upper right, lower left, lower right. */
    public double[] getCornerLongitudes(final int rangeSamples, final int azimuthLines) {
        final int firstLine = tiffColumn(0, azimuthLines);
        final int lastLine = tiffColumn(azimuthLines - 1, azimuthLines);
        final int lastSample = rangeSamples - 1;
        return new double[]{
                getLongitude(firstLine, 0), getLongitude(firstLine, lastSample),
                getLongitude(lastLine, 0), getLongitude(lastLine, lastSample)};
    }

    /** {@code [minLon, minLat, maxLon, maxLat]}, derived from the transform and the shape. */
    public double[] getBoundingBox() {
        final int lastColumn = getAzimuthLines() - 1;
        final int lastRow = getRangeSamples() - 1;
        final double[][] corners = {
                {0, 0}, {lastColumn, 0}, {lastColumn, lastRow}, {0, lastRow}
        };
        double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        for (double[] corner : corners) {
            final double lon = getLongitude(corner[0], corner[1]);
            final double lat = getLatitude(corner[0], corner[1]);
            minLon = Math.min(minLon, lon);
            maxLon = Math.max(maxLon, lon);
            minLat = Math.min(minLat, lat);
            maxLat = Math.max(maxLat, lat);
        }
        return new double[]{minLon, minLat, maxLon, maxLat};
    }

    public int getBandCount() {
        final Object bands = get(IceyeOpenDataConstants.raster_bands);
        return bands instanceof JSONArray ? ((JSONArray) bands).size() : 1;
    }

    public int getRasterDataType() {
        final Object bands = get(IceyeOpenDataConstants.raster_bands);
        if (bands instanceof JSONArray && !((JSONArray) bands).isEmpty()) {
            final Object first = ((JSONArray) bands).get(0);
            if (first instanceof JSONObject) {
                final Object type = ((JSONObject) first).get(IceyeOpenDataConstants.data_type);
                if (type != null) {
                    return toProductDataType(type.toString());
                }
            }
        }
        return isComplex() ? ProductData.TYPE_FLOAT32 : ProductData.TYPE_UINT16;
    }

    /** STAC raster extension data types, as far as ICEYE uses them. */
    private static int toProductDataType(final String dataType) {
        switch (dataType.toLowerCase()) {
            case "uint8":
            case "ui8":
                return ProductData.TYPE_UINT8;
            case "int8":
            case "i8":
                return ProductData.TYPE_INT8;
            case "uint16":
            case "ui16":
                return ProductData.TYPE_UINT16;
            case "int16":
            case "i16":
                return ProductData.TYPE_INT16;
            case "uint32":
            case "ui32":
                return ProductData.TYPE_UINT32;
            case "int32":
            case "i32":
                return ProductData.TYPE_INT32;
            case "float64":
            case "f64":
                return ProductData.TYPE_FLOAT64;
            default:
                return ProductData.TYPE_FLOAT32;
        }
    }

    // ------------------------------------------------------------------
    // timing
    // ------------------------------------------------------------------

    public UTC getFirstLineTime() {
        return getUTC(IceyeOpenDataConstants.first_line_time);
    }

    public UTC getLastLineTime() {
        return getUTC(IceyeOpenDataConstants.last_line_time);
    }

    /**
     * Seconds per azimuth line, derived from the zero-Doppler span and the line
     * count rather than taken from {@code iceye:processing_prf}. The two differ
     * by about 2% on spotlight products, and azimuth time has to stay a linear
     * function of y for interferometric use.
     */
    public double getLineTimeInterval() {
        final UTC first = getFirstLineTime();
        final UTC last = getLastLineTime();
        final int lines = getAzimuthLines();
        if (first == null || last == null || lines < 2) {
            final Double prf = getDouble(IceyeOpenDataConstants.pulse_repetition_frequency);
            return prf == null || prf == 0 ? 0 : 1 / prf;
        }
        return (last.getMJD() - first.getMJD()) * Constants.secondsInDay / (lines - 1);
    }

    // ------------------------------------------------------------------
    // orbit and doppler
    // ------------------------------------------------------------------

    public OrbitStateVector[] getOrbitStateVectors() {
        final Object states = get(IceyeOpenDataConstants.orbit_states);
        if (!(states instanceof JSONArray)) {
            return new OrbitStateVector[0];
        }
        final JSONArray array = (JSONArray) states;
        final List<OrbitStateVector> vectors = new ArrayList<>(array.size());
        for (Object element : array) {
            if (!(element instanceof JSONObject)) {
                continue;
            }
            final JSONObject state = (JSONObject) element;
            final UTC time = parseUTC(String.valueOf(state.get(IceyeOpenDataConstants.orbit_state_time)));
            final double[] position = toDoubles(state.get(IceyeOpenDataConstants.orbit_state_position));
            final double[] velocity = toDoubles(state.get(IceyeOpenDataConstants.orbit_state_velocity));
            if (time == null || position.length < 3 || velocity.length < 3) {
                continue;
            }
            vectors.add(new OrbitStateVector(time, position[0], position[1], position[2],
                    velocity[0], velocity[1], velocity[2]));
        }
        return vectors.toArray(new OrbitStateVector[0]);
    }

    public AbstractMetadata.DopplerCentroidCoefficientList[] getDopplerCentroidCoefficients() {
        final Object coefficients = get(IceyeOpenDataConstants.doppler_centroid_coeffs);
        final Object datetimes = get(IceyeOpenDataConstants.doppler_centroid_datetimes);
        if (!(coefficients instanceof JSONArray)) {
            return new AbstractMetadata.DopplerCentroidCoefficientList[0];
        }
        final JSONArray coefficientArray = (JSONArray) coefficients;
        final JSONArray timeArray = datetimes instanceof JSONArray ? (JSONArray) datetimes : null;

        final AbstractMetadata.DopplerCentroidCoefficientList[] list =
                new AbstractMetadata.DopplerCentroidCoefficientList[coefficientArray.size()];
        for (int i = 0; i < list.length; ++i) {
            final AbstractMetadata.DopplerCentroidCoefficientList entry =
                    new AbstractMetadata.DopplerCentroidCoefficientList();
            entry.coefficients = toDoubles(coefficientArray.get(i));
            if (timeArray != null && i < timeArray.size()) {
                entry.time = parseUTC(String.valueOf(timeArray.get(i)));
                if (entry.time != null) {
                    entry.timeMJD = entry.time.getMJD();
                }
            }
            list[i] = entry;
        }
        return list;
    }

    public double[] getDopplerRateCoefficients() {
        return getDoubles(IceyeOpenDataConstants.doppler_rate_coeffs);
    }

    // ------------------------------------------------------------------
    // range geometry
    // ------------------------------------------------------------------

    /** Incidence angle at a range sample index, from the delivered polynomial. */
    public double getIncidenceAngle(final double rangeSample) {
        final double[] coefficients = getDoubles(IceyeOpenDataConstants.incidence_angle_coeffs);
        if (coefficients.length == 0) {
            final Double near = getDouble(IceyeOpenDataConstants.incidence_near);
            return near == null ? 0 : near;
        }
        return polynomial(coefficients, rangeSample);
    }

    /**
     * Slant range in metres at a range sample index. The GRD is ground range,
     * so it goes through {@code iceye:ground_to_slant_coeff}, a polynomial in
     * ground range metres; the SLC is already slant range.
     */
    public double getSlantRange(final double rangeSample) {
        final Double nearRange = getDouble(IceyeOpenDataConstants.slant_range_to_first_pixel);
        final Double spacing = getDouble(IceyeOpenDataConstants.range_spacing);
        if (isComplex()) {
            return (nearRange == null ? 0 : nearRange) + (spacing == null ? 0 : spacing) * rangeSample;
        }
        final double[] coefficients = getDoubles(IceyeOpenDataConstants.ground_to_slant_coeff);
        if (coefficients.length == 0) {
            return (nearRange == null ? 0 : nearRange) + (spacing == null ? 0 : spacing) * rangeSample;
        }
        return polynomial(coefficients, (spacing == null ? 0 : spacing) * rangeSample);
    }

    /** Slant range two-way time in nanoseconds, which is what SNAP's tie point grid holds. */
    public double getSlantRangeTime(final double rangeSample) {
        return getSlantRange(rangeSample) / Constants.halfLightSpeed * Constants.sTOns;
    }

    static double polynomial(final double[] coefficients, final double t) {
        double sum = coefficients[coefficients.length - 1];
        for (int i = coefficients.length - 1; i > 0; --i) {
            sum = sum * t + coefficients[i - 1];
        }
        return sum;
    }

    // ------------------------------------------------------------------
    // abstracted metadata
    // ------------------------------------------------------------------

    /**
     * Fills an abstracted metadata header. Every field is optional: a delivery
     * missing a key leaves the SNAP default in place instead of aborting the
     * read.
     */
    public void populateAbstractedMetadata(final MetadataElement absRoot) {
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, Missions.ICEYE);
        setString(absRoot, AbstractMetadata.PRODUCT, getProductName());
        setString(absRoot, AbstractMetadata.PRODUCT_TYPE, getProductType());
        setString(absRoot, AbstractMetadata.SPH_DESCRIPTOR, getProductType());
        setString(absRoot, AbstractMetadata.ACQUISITION_MODE, getAcquisitionMode());
        setString(absRoot, AbstractMetadata.antenna_pointing,
                getString(IceyeOpenDataConstants.observation_direction));
        setString(absRoot, AbstractMetadata.mds1_tx_rx_polar, getPolarization());

        final String pass = getString(IceyeOpenDataConstants.orbit_state);
        if (pass != null) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, pass.toUpperCase());
        }
        setString(absRoot, AbstractMetadata.ProcessingSystemIdentifier, getProcessorVersion());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.geo_ref_system,
                IceyeConstants.geo_ref_system_default);

        setUTC(absRoot, AbstractMetadata.first_line_time, getFirstLineTime());
        setUTC(absRoot, AbstractMetadata.last_line_time, getLastLineTime());
        setUTC(absRoot, AbstractMetadata.PROC_TIME, getUTC(IceyeOpenDataConstants.processing_end));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line, getRangeSamples());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines, getAzimuthLines());
        setInt(absRoot, AbstractMetadata.range_looks, getInt(IceyeOpenDataConstants.range_looks));
        setInt(absRoot, AbstractMetadata.azimuth_looks, getInt(IceyeOpenDataConstants.azimuth_looks));

        setDouble(absRoot, AbstractMetadata.range_spacing, getDouble(IceyeOpenDataConstants.range_spacing));
        setDouble(absRoot, AbstractMetadata.azimuth_spacing, getDouble(IceyeOpenDataConstants.azimuth_spacing));
        setDouble(absRoot, AbstractMetadata.incidence_near, getDouble(IceyeOpenDataConstants.incidence_near));
        setDouble(absRoot, AbstractMetadata.incidence_far, getDouble(IceyeOpenDataConstants.incidence_far));
        setDouble(absRoot, AbstractMetadata.avg_scene_height,
                getDouble(IceyeOpenDataConstants.avg_scene_height));
        setDouble(absRoot, AbstractMetadata.slant_range_to_first_pixel,
                getDouble(IceyeOpenDataConstants.slant_range_to_first_pixel));
        setDouble(absRoot, AbstractMetadata.calibration_factor,
                getDouble(IceyeOpenDataConstants.calibration_factor));

        setMHz(absRoot, AbstractMetadata.radar_frequency, IceyeOpenDataConstants.radar_frequency);
        setMHz(absRoot, AbstractMetadata.range_sampling_rate, IceyeOpenDataConstants.range_sampling_rate);
        setMHz(absRoot, AbstractMetadata.range_bandwidth, IceyeOpenDataConstants.range_bandwidth);
        setDouble(absRoot, AbstractMetadata.azimuth_bandwidth,
                getDouble(IceyeOpenDataConstants.azimuth_bandwidth));
        setDouble(absRoot, AbstractMetadata.pulse_repetition_frequency,
                getDouble(IceyeOpenDataConstants.pulse_repetition_frequency));

        final double lineTimeInterval = getLineTimeInterval();
        if (lineTimeInterval > 0) {
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval, lineTimeInterval);
        }

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE,
                isComplex() ? IceyeConstants.COMPLEX : IceyeConstants.DETECTED);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, isComplex() ? 0 : 1);
        final Integer azimuthLooks = getInt(IceyeOpenDataConstants.azimuth_looks);
        final Integer rangeLooks = getInt(IceyeOpenDataConstants.range_looks);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.multilook_flag,
                !isComplex() && (gt1(azimuthLooks) || gt1(rangeLooks)) ? 1 : 0);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag,
                IceyeConstants.ANT_ELEV_CORR_FLAG_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag,
                IceyeConstants.RANGE_SPREAD_COMP_FLAG_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.replica_power_corr_flag,
                IceyeConstants.REPLICA_POWER_CORR_FLAG_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.abs_calibration_flag,
                IceyeConstants.ABS_CALIBRATION_FLAG_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.inc_angle_comp_flag,
                IceyeConstants.INC_ANGLE_COMP_FLAG_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.coregistered_stack,
                IceyeConstants.COREGISTERED_STACK_DEFAULT_VALUE);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.bistatic_correction_applied,
                IceyeConstants.BISTATIC_CORRECTION_APPLIED_DEFAULT);

        addOrbitStateVectors(absRoot);
        addSRGRCoefficients(absRoot);
        addGeoCorners(absRoot);
        AbstractMetadata.setDopplerCentroidCoefficients(absRoot, getDopplerCentroidCoefficients());
    }

    private static boolean gt1(final Integer looks) {
        return looks != null && looks > 1;
    }

    public String getProcessorVersion() {
        final Object software = get(IceyeOpenDataConstants.processing_software);
        if (software instanceof JSONObject) {
            final Object processor = ((JSONObject) software).get(IceyeOpenDataConstants.processor);
            if (processor != null) {
                return processor.toString();
            }
        }
        return software == null ? null : software.toString();
    }

    private void addOrbitStateVectors(final MetadataElement absRoot) {
        final OrbitStateVector[] vectors = getOrbitStateVectors();
        if (vectors.length == 0) {
            return;
        }
        try {
            AbstractMetadata.setOrbitStateVectors(absRoot, vectors);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME, vectors[0].time);
        } catch (Exception e) {
            SystemUtils.LOG.severe("ICEYE: unable to add orbit state vectors: " + e.getMessage());
        }
    }

    /**
     * The GRD is ground range, so downstream terrain correction needs the
     * ground-to-slant polynomial as SRGR coefficients.
     */
    private void addSRGRCoefficients(final MetadataElement absRoot) {
        if (isComplex()) {
            return;
        }
        final double[] coefficients = getDoubles(IceyeOpenDataConstants.ground_to_slant_coeff);
        if (coefficients.length == 0) {
            return;
        }
        final MetadataElement srgr = absRoot.getElement(AbstractMetadata.srgr_coefficients);
        if (srgr == null) {
            return;
        }
        final MetadataElement list = new MetadataElement(AbstractMetadata.srgr_coef_list + ".1");
        srgr.addElement(list);

        AbstractMetadata.addAbstractedAttribute(list, AbstractMetadata.ground_range_origin,
                ProductData.TYPE_FLOAT64, "m", "Ground Range Origin");
        AbstractMetadata.setAttribute(list, AbstractMetadata.ground_range_origin, 0.0);

        final UTC firstLineTime = getFirstLineTime();
        if (firstLineTime != null) {
            list.setAttributeUTC(AbstractMetadata.srgr_coef_time, firstLineTime);
        }

        for (int i = 0; i < coefficients.length; ++i) {
            final MetadataElement coefficient = new MetadataElement(AbstractMetadata.coefficient + '.' + (i + 1));
            list.addElement(coefficient);
            AbstractMetadata.addAbstractedAttribute(coefficient, AbstractMetadata.srgr_coef,
                    ProductData.TYPE_FLOAT64, "", "SRGR Coefficient");
            AbstractMetadata.setAttribute(coefficient, AbstractMetadata.srgr_coef, coefficients[i]);
        }
    }

    /** Scene corners in SNAP's raster order, which depends on the look side. */
    private void addGeoCorners(final MetadataElement absRoot) {
        if (getGeoTransform().length < 6) {
            return;
        }
        final double[] lat = getCornerLatitudes(getRangeSamples(), getAzimuthLines());
        final double[] lon = getCornerLongitudes(getRangeSamples(), getAzimuthLines());

        absRoot.setAttributeDouble(AbstractMetadata.first_near_lat, lat[0]);
        absRoot.setAttributeDouble(AbstractMetadata.first_near_long, lon[0]);
        absRoot.setAttributeDouble(AbstractMetadata.first_far_lat, lat[1]);
        absRoot.setAttributeDouble(AbstractMetadata.first_far_long, lon[1]);
        absRoot.setAttributeDouble(AbstractMetadata.last_near_lat, lat[2]);
        absRoot.setAttributeDouble(AbstractMetadata.last_near_long, lon[2]);
        absRoot.setAttributeDouble(AbstractMetadata.last_far_lat, lat[3]);
        absRoot.setAttributeDouble(AbstractMetadata.last_far_long, lon[3]);
    }

    private static void setString(final MetadataElement absRoot, final String tag, final String value) {
        if (value != null) {
            AbstractMetadata.setAttribute(absRoot, tag, value);
        }
    }

    private static void setInt(final MetadataElement absRoot, final String tag, final Integer value) {
        if (value != null) {
            AbstractMetadata.setAttribute(absRoot, tag, value);
        }
    }

    private static void setDouble(final MetadataElement absRoot, final String tag, final Double value) {
        if (value != null) {
            AbstractMetadata.setAttribute(absRoot, tag, value);
        }
    }

    private static void setUTC(final MetadataElement absRoot, final String tag, final UTC value) {
        if (value != null) {
            AbstractMetadata.setAttribute(absRoot, tag, value);
        }
    }

    private void setMHz(final MetadataElement absRoot, final String tag, final String key) {
        final Double value = getDouble(key);
        if (value != null) {
            AbstractMetadata.setAttribute(absRoot, tag, value / Constants.oneMillion);
        }
    }
}
