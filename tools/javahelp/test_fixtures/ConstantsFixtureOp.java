/*
 * Test fixture for tools/javahelp/test_gen_params.py.
 * Adapted from SpectralDiversityOp: valueSet and defaultValue given by constants.
 */
package eu.esa.sar.fixture;

import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;

@OperatorMetadata(alias = "Constants-Fixture",
        category = "Radar/Test",
        description = "Fixture with constant-valued parameters")
public class ConstantsFixtureOp extends BaseFixtureOp {

    private final static String A = "x";
    static final String B = "y", C = "z";
    public static final String SUFFIX = " (Auto Download)";

    @Parameter(label = "Estimator",
            valueSet = {A, B, C},
            defaultValue = B,
            description = "Estimator used for the shift computation")
    private String estimator = B;

    @Parameter(valueSet = {A + SUFFIX, "Prefix " + BaseFixtureOp.BASE_NAME, Unknown.VALUE},
            defaultValue = A + SUFFIX, label = "Orbit")
    private String orbit = A + SUFFIX;

    @Parameter(label = "Mode")
    private Mode mode = Mode.FAST;

    @Parameter(defaultValue = INHERITED, label = "From superclass")
    private String inherited = INHERITED;
}
