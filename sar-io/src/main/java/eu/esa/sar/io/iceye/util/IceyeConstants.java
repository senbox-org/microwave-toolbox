package eu.esa.sar.io.iceye.util;

import java.text.DateFormat;

import org.esa.snap.core.datamodel.ProductData.UTC;

public class IceyeConstants {
    public static final String PRODUCT = "product_name";
    public static final String PRODUCT_TYPE = "product_type";
    public static final String SPH_DESCRIPTOR = "product_level";
    public static final String MISSION = "satellite_name";
    public static final String ACQUISITION_MODE = "acquisition_mode";
    public static final String ANTENNA_POINTING = "look_side";
    public static final String BEAMS_DEFAULT_VALUE = "";
    public static final String PROCESSING_SYSTEM_IDENTIFIER = "processor_version";
    public static final String CYCLE = "orbit_repeat_cycle";
    public static final String REL_ORBIT = "orbit_relative_number";
    public static final String ABS_ORBIT = "orbit_absolute_number";
    public static final String STATE_VECTOR_TIME = "state_vector_time_utc";
    public static final String INCIDENCE_ANGLES = "local_incidence_angle";
    public static final int SLICE_NUM_DEFAULT_VALUE = 99999;
    public static final int DATA_TAKE_ID_DEFAULT_VALUE = 99999;
    public static final String GEO_REFERENCE_SYSTEM_DEFAULT_VALUE = "WGS84";
    public static final String GEO_REFERENCE_SYSTEM = "geo_ref_system";
    public static final String FIRST_LINE_TIME = "zerodoppler_start_utc";
    public static final String LAST_LINE_TIME = "zerodoppler_end_utc";
    public static final String FIRST_NEAR = "coord_first_near";
    public static final String FIRST_FAR = "coord_first_far";
    public static final String LAST_NEAR = "coord_last_near";
    public static final String LAST_FAR = "coord_last_far";
    public static final String PASS = "orbit_direction";
    public static final String MDS1_TX_RX_POLAR = "polarization";
    public static final String AZIMUTH_LOOKS = "azimuth_looks";
    public static final String RANGE_LOOKS = "range_looks";
    public static final String SLANT_RANGE_SPACING = "slant_range_spacing";
    public static final String AZIMUTH_GROUND_SPACING = "azimuth_ground_spacing";
    public static final String PULSE_REPETITION_FREQUENCY = "processing_prf";
    public static final String RADAR_FREQUENCY = "carrier_frequency";
    public static final String LINE_TIME_INTERVAL = "azimuth_time_interval";
    public static final String NUM_OUTPUT_LINES = "number_of_azimuth_samples";
    public static final String NUM_SAMPLES_PER_LINE = "number_of_range_samples";
    public static final int SUBSET_OFFSET_X_DEFAULT_VALUE = 0;
    public static final int SUBSET_OFFSET_Y_DEFAULT_VALUE = 0;
    public static final String AVG_SCENE_HEIGHT = "avg_scene_height";
    public static final double LAT_PIXEL_RES_DEFAULT_VALUE = 99999.0;
    public static final double LON_PIXEL_RES_DEFAULT_VALUE = 99999.0;
    public static final String FIRST_PIXEL_TIME = "first_pixel_time";
    public static final int ANT_ELEV_CORR_FLAG_DEFAULT_VALUE = 1;
    public static final String ANT_ELEV_CORR_FLAG = "ant_elev_corr_flag";
    public static final int RANGE_SPREAD_COMP_FLAG_DEFAULT_VALUE = 1;
    public static final String RANGE_SPREAD_COMP_FLAG = "range_spread_comp_flag";
    public static final int REPLICA_POWER_CORR_FLAG_DEFAULT_VALUE = 0;
    public static final int ABS_CALIBRATION_FLAG_DEFAULT_VALUE = 0;
    public static final String CALIBRATION_FACTOR = "calibration_factor";
    public static final int INC_ANGLE_COMP_FLAG_DEFAULT_VALUE = 0;
    public static final double REF_INC_ANGLE_DEFAULT_VALUE = 99999.0;
    public static final double REF_SLANT_RANGE_DEFAULT_VALUE = 99999.0;
    public static final double REF_SLANT_RANGE_EXP_DEFAULT_VALUE = 99999.0;
    public static final double RESCALING_FACTOR_DEFAULT_VALUE = 99999.0;
    public static final String RANGE_SAMPLING_RATE = "range_sampling_rate";
    public static final String RANGE_BANDWIDTH = "chirp_bandwidth";
    public static final String AZIMUTH_BANDWIDTH = "total_processed_bandwidth_azimuth";
    public static final int BISTATIC_CORRECTION_APPLIED_DEFAULT = 1;
    public static final int MULTI_LOOK_FLAG_DEFAULT_VALUE = 0;
    public static final int COREGISTERED_STACK_DEFAULT_VALUE = 0;
    public static final String ORBIT_VECTOR_N_X_POS = "posX";
    public static final String ORBIT_VECTOR_N_Y_POS = "posY";
    public static final String ORBIT_VECTOR_N_Z_POS = "posZ";
    public static final String ORBIT_VECTOR_N_X_VEL = "velX";
    public static final String ORBIT_VECTOR_N_Y_VEL = "velY";
    public static final String ORBIT_VECTOR_N_Z_VEL = "velZ";
    public static final String ACQUISITION_START_UTC = "acquisition_start_utc";
    public static final String ACQUISITION_END_UTC = "acquisition_end_utc";
    public static final String NUMBER_OF_STATE_VECTORS = "number_of_state_vectors";
    public static final String DC_ESTIMATE_COEFFS = "dc_estimate_coeffs";
    public static final String DR_COEFFS = "doppler_rate_coeffs";
    public static final String S_I = "s_i";
    public static final String S_Q = "s_q";
    public static final String S_AMPLITUDE = "s_amplitude";
    public static final String ICEYE_PROCESSOR_NAME_PREFIX = "ICEYE_P_";
    public static final String PROC_TIME_UTC = "processing_time";
    public static final String SLANT_RANGE_TO_FIRST_PIXEL = "slant_range_to_first_pixel";
    public static final String RIGHT = "right";
    public static final String ASCENDING = "ascending";
    public static final String DESCENDING = "descending";
    public static final String GRSR_GROUND_RANGE_ORIGIN = "grsr_ground_range_origin";
    public static final String GRSR_COEFFICIENTS = "grsr_coefficients";
    public static final String RANGE_SPACING = "range_spacing";
    public static final String COORD_CENTER = "coord_center";
    public static final String INCIDENCE_NEAR = "incidence_near";
    public static final String DC_ESTIMATE_POLY_ORDER = "dc_estimate_poly_order";
    public static final String DC_REFERENCE_PIXEL_TIME = "dc_reference_pixel_time";
    public static final String DC_ESTIMATE_TIME_UTC = "dc_estimate_time_utc";
    public static final String GRSR_ZERO_DOPPLER_TIME = "grsr_zero_doppler_time";
    public static final String INCIDENCE_FAR = "incidence_far";
    public static final String AZIMUTH_SPACING = "azimuth_spacing";
    public static final String GDALMETADATA = "<GDALMetadata";
    public static final String GRD = "grd";
    public static final String SLC = "slc";
    public static final String COMPLEX = "COMPLEX";
    public static final String DETECTED = "DETECTED";

    public static final String geo_ref_system_default = "WGS84";
    public static final String ground = "ground";
    public static final String ICEYE_FILE_PREFIX = "ICEYE";
    public static final String left = "left";
    public static final String METADATA_JSON = "METADATA_JSON";
    public static final String PRODUCT_NAME = "PRODUCT_NAME";
    public static final String ProductMetadata = "productMetadata";
    public static final String spot = "spot";
    public static final String spotlight = "spotlight";
    public static final String strip = "strip";
    public static final String stripmap = "stripmap";
    public static final String time = "time";
    public static final String coeffs = "coeffs";
    public static final String qlk_png = "qlk.png";
    public static final String thm_png = "thm.png";
    public static final String Quicklook = "Quicklook";
    public static final String Thumbnail = "Thumbnail";

    public static final DateFormat standardDateFormat = UTC.createDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    public static final int TIFFTagImageWidth = 256;
    public static final int TIFFTagImageLength = 257;
    public static final int TIFFTagModelTransformation = 34264;
    public static final int TIFFTagGDAL_METADATA = 42112;

    public static final int AMPLITUDE_BAND_INDEX = 0;
    public static final int PHASE_BAND_INDEX = 1;
    public static final int I_BAND_VIRTUAL_INDEX = 2;
    public static final int Q_BAND_VIRTUAL_INDEX = 3;
    public static final int QUICKLOOK_INDEX = 4;

    public static final String amplitude_band_prefix = "Amplitude_";
    public static final String phase_band_prefix = "Phase_";
    public static final String i_band_prefix = "i_";
    public static final String q_band_prefix = "q_";

    public static final String SEP = ",";

    public static final String data                        = "data" ;

    public static final String calibration_factor          = data          + SEP + "calibration_factor";
    public static final String looks                       = data          + SEP + "looks";

    public static final String azimuth_looks               = looks         + SEP + "az" + SEP + "count" ;
    public static final String range_looks                 = looks         + SEP + "rg" + SEP + "count";

    public static final String processing                  = data          + SEP + "processing";

    public static final String azimuth_bandwidth           = processing    + SEP + "bandwidth"+ SEP + "az";
    public static final String ProcessingSystemIdentifier   = processing    + SEP + "version";
    public static final String PROC_TIME                   = processing    + SEP + "end";
    public static final String first_line_time             = processing    + SEP + "zero_doppler_start_utc";
    public static final String last_line_time              = processing    + SEP + "zero_doppler_end_utc";
    public static final String pulse_repetition_frequency  = processing    + SEP + "prf";

    public static final String sample                      = data          + SEP + "sample";
    public static final String sample_size                 = sample        + SEP + "size";
    public static final String num_samples_per_line        = sample_size   + SEP + "rg";
    public static final String num_output_lines            = sample_size   + SEP + "az";
    public static final String sample_sp                   = sample        + SEP + "spacing";
    public static final String azimuth_spacing             = sample_sp     + SEP + "az";
    public static final String range_spacing               = sample_sp     + SEP + "rg";

    public static final String scene                       = data          + SEP + "scene";
    public static final String avg_scene_height            = scene         + SEP + "average_scene_height";
    public static final String inc_angle                   = scene         + SEP + "incidence_angle";
    public static final String inc_angle_coeffs            = inc_angle     + SEP + "coefficients";
    public static final String incidence_far               = inc_angle     + SEP + "far";
    public static final String incidence_near              = inc_angle     + SEP + "near";
    public static final String projection                  = scene         + SEP + "projection";
    public static final String grsr                        = projection    + SEP + "grsr";
    public static final String grsr_coefficients           = grsr          + SEP + "coefficients";
    public static final String zero_doppler_time_utc       = grsr          + SEP + "zero_doppler_time_utc";
    public static final String projection_plane            = projection    + SEP + "plane" ;
    public static final String slant_range_to_first_pixel  = scene         + SEP + "slant_range_to_first_pixel";

    public static final String product_type                = data          + SEP + "type";
    public static final String product_name                = data          + SEP + "file";

    public static final String collection                  = "collection";

    public static final String antenna_pointing            = collection    + SEP + "look_side";
    public static final String acquisition_mode            = collection    + SEP + "mode";
    public static final String acquisition_start_utc       = collection    + SEP + "start";
    public static final String acquisition_end_utc         = collection    + SEP + "end";
    public static final String data_take_id                = collection    + SEP + "id";
    public static final String orbit                       = collection    + SEP + "orbit";
    public static final String orbit_states                = orbit         + SEP + "states";
    public static final String position                    = "position" ;
    public static final String velocity                    = "velocity" ;
    public static final String platform                    = collection    + SEP + "platform";
    public static final String polarization                = collection    + SEP + "polarization";
    public static final String radar_frequency             = collection    + SEP + "carrier_frequency";
    public static final String range_bandwidth             = collection    + SEP + "chirp_bandwidth";
    public static final String range_sampling_rate         = collection    + SEP + "range_sampling_rate";
    public static final String dop_param                   = collection    + SEP + "doppler_parameters";
    public static final String centroid_estimates          = dop_param     + SEP + "centroid_estimates";
    public static final String doppler_rate_coffs          = dop_param     + SEP + "rate_coeffs";
}
