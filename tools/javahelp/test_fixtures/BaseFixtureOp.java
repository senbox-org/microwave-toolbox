/*
 * Test fixture for tools/javahelp/test_gen_params.py.
 * An abstract operator base class: it is not listed itself, but its parameters are
 * inherited by subclasses (cf. IEMInverBase and the IEM inversion operators).
 */
package eu.esa.sar.fixture;

import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;

@OperatorMetadata(alias = "Base-Fixture", category = "Radar/Test")
public abstract class BaseFixtureOp extends Operator {

    public static final String BASE_NAME = "base";
    protected static final String INHERITED = "from-" + BASE_NAME;

    public enum Mode { SLOW, FAST }

    @Parameter(description = "Number of closest matches", defaultValue = "5", label = "N")
    protected Integer N = 5;
}
