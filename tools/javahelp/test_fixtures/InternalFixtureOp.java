/*
 * Test fixture for tools/javahelp/test_gen_params.py.
 * An internal operator (cf. DataAnalysisOp): listed by --list, never inserted into a page.
 */
package eu.esa.sar.fixture;

import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;

@OperatorMetadata(alias = "Internal-Fixture",
        category = "Radar/Test",
        description = "Internal fixture operator",
        internal = true)
public final class InternalFixtureOp extends Operator {

    @Parameter(defaultValue = "1", label = "Level")
    private int level = 1;
}
