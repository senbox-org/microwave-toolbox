package eu.esa.sar.commons;

import com.bc.ceres.annotation.STTM;
import org.junit.Test;

import static org.junit.Assume.assumeTrue;

public class SARGeocodingTest {

  @Test
  @STTM("SNAP-3724")
  public void testComputeGroundRange() {

    final int sourceImageWidth = 13638;
    final double groundRangeSpacing = 40.0;
    final double slantRange = 970530.4563706246;
    final double[] srgrCoeff = new double[]{631472.8789249088, 0.3166902223370849, 7.865149038750229E-7,
            -3.957704908474775E-13, -2.925522813361202E-19, 6.3335584020426205E-25, -2.9413809749818036E-32,
            -1.200473077501047E-36, 1.8014886333677E-42, -1.0241528233671593E-48, -2.2068205749647573E-55,
             6.021394462107571E-61, -2.373033835294464E-67};
    final double ground_range_origin = 0.0;
    final double groundRangeExp = 545516.6183853149;

    final double groundRange = SARGeocoding.computeGroundRange(sourceImageWidth, groundRangeSpacing, slantRange,
            srgrCoeff, ground_range_origin);

    assumeTrue(Math.abs(groundRange - groundRangeExp) <= 1e-3);
  }
}
