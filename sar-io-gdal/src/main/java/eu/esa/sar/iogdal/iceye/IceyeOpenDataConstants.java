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

/**
 * Property names of the STAC item that the ICEYE Open Data Initiative ships
 * beside each COG, and the GDAL metadata items that carry a copy of it inside
 * the TIFF.
 * <p>
 * This is a different schema from the one in {@link IceyeConstants}, which
 * addresses the nested {@code data}/{@code collection} document embedded in the
 * AML and CPX deliveries. The two share no key, which is why they get separate
 * readers.
 *
 * @author Luis Veci
 */
public class IceyeOpenDataConstants {

    private IceyeOpenDataConstants() {
    }

    /** GDAL metadata item holding the STAC {@code properties} object. */
    public static final String ICEYE_PROPERTIES = "ICEYE_PROPERTIES";
    /** GDAL metadata item holding the nested schema, written by the AML/CPX deliveries. */
    public static final String METADATA_JSON = "METADATA_JSON";

    public static final String PRODUCT_NAME_ITEM = "PRODUCT_NAME";
    public static final String PRODUCT_FILE_ITEM = "PRODUCT_FILE";
    public static final String SCALE_ITEM = "SCALE";
    public static final String OFFSET_ITEM = "OFFSET";
    public static final String NAME_ITEM = "NAME";

    /** Element name under Original_Product_Metadata holding the STAC properties. */
    public static final String PRODUCT_METADATA = "stacItem";

    public static final String STAC_PROPERTIES = "properties";
    public static final String STAC_BBOX = "bbox";

    // --- identification ---
    public static final String platform = "platform";
    public static final String product_type = "sar:product_type";
    public static final String acquisition_mode = "sar:instrument_mode";
    public static final String polarizations = "sar:polarizations";
    public static final String observation_direction = "sar:observation_direction";
    public static final String orbit_state = "sat:orbit_state";
    public static final String filename = "iceye:filename";
    public static final String scene_id = "iceye:scene_id";
    public static final String image_id = "iceye:image_id";
    public static final String processing_software = "processing:software";
    public static final String processor = "processor";

    // --- timing ---
    public static final String start_datetime = "start_datetime";
    public static final String end_datetime = "end_datetime";
    public static final String first_line_time = "iceye:zero_doppler_start_datetime";
    public static final String last_line_time = "iceye:zero_doppler_end_datetime";
    public static final String processing_end = "iceye:processing_end_datetime";

    // --- sampling ---
    public static final String range_spacing = "sar:pixel_spacing_range";
    public static final String azimuth_spacing = "sar:pixel_spacing_azimuth";
    public static final String range_looks = "sar:looks_range";
    public static final String azimuth_looks = "sar:looks_azimuth";
    public static final String radar_frequency = "sar:center_frequency";
    public static final String range_sampling_rate = "iceye:acquisition_range_sampling_rate";
    public static final String range_bandwidth = "iceye:processing_bandwidth_range";
    public static final String azimuth_bandwidth = "iceye:processing_bandwidth_azimuth";
    public static final String pulse_repetition_frequency = "iceye:processing_prf";

    // --- geometry ---
    public static final String proj_shape = "proj:shape";
    public static final String proj_transform = "proj:transform";
    public static final String incidence_near = "iceye:incidence_angle_near";
    public static final String incidence_far = "iceye:incidence_angle_far";
    public static final String incidence_angle_coeffs = "iceye:incidence_angle_coeffs";
    public static final String slant_range_to_first_pixel = "iceye:range_near";
    public static final String ground_to_slant_coeff = "iceye:ground_to_slant_coeff";
    public static final String avg_scene_height = "iceye:average_scene_height";
    public static final String orbit_states = "iceye:orbit_states";
    public static final String orbit_state_time = "time";
    public static final String orbit_state_position = "position";
    public static final String orbit_state_velocity = "velocity";

    // --- doppler ---
    public static final String doppler_centroid_coeffs = "iceye:doppler_centroid_coeffs";
    public static final String doppler_centroid_datetimes = "iceye:doppler_centroid_datetimes";
    public static final String doppler_rate_coeffs = "iceye:doppler_rate_coeffs";

    // --- radiometry ---
    public static final String calibration_factor = "iceye:calibration_factor";
    public static final String raster_bands = "raster:bands";
    public static final String data_type = "data_type";

    // --- values ---
    public static final String SLC = "SLC";
    public static final String left = "left";
    public static final String spot = "spot";
    public static final String strip = "strip";

    public static final String TIF_EXTENSION = ".tif";
    public static final String JSON_EXTENSION = ".json";
}
