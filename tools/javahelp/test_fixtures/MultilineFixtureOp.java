/*
 * Test fixture for tools/javahelp/test_gen_params.py.
 * Adapted from GoldsteinFilterOp and SpectralDiversityOp: multi-line annotations,
 * string concatenation, nested braces, comments and a commented-out @Parameter.
 */
package eu.esa.sar.fixture;

import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;

import java.util.List;

@OperatorMetadata(alias = "Multiline-Fixture",
        category = "Radar/Test",
        description = "Fixture with multi-line parameters")
public class MultilineFixtureOp extends Operator {

    @SourceProduct
    private Product sourceProduct;

    /* a block comment that mentions @Parameter(label = "ghost") must be ignored */
    @Parameter(valueSet = {"32", "64",   // trailing comment inside the annotation
                           "128", "256"},
            interval = "(0, 1]",
            defaultValue = "64",
            unit = "pixels",
            description = "FFT size used for the block filter " +
                    "(a power of two; {braces} and \"quotes\" inside a string)",
            label = "FFT Size")
    private String fftSize = "64";

    // TODO: uncomment for showing in the GUI
//    @Parameter(label = "Optimization criterion",
//            defaultValue = "Max",
//            description = "Commented-out parameter")
    private String optObjective = "Max";

    /*
    @Parameter(label = "Block-commented parameter", defaultValue = "1")
    private int blockCommented = 1;
    */

    @Deprecated
    @Parameter(description = "The list of source bands.", alias = "sourceBands",
            rasterDataNodeType = org.esa.snap.core.datamodel.Band.class, label = "Source Bands")
    private String[] sourceBandNames;

    @Parameter(defaultValue = "true", label = "Use alpha < 1 & more")
    private final Boolean useAlpha = true;

    @Parameter(label = "Weights", notNull = true, pattern = "[0-9,]+")
    protected List<String> weights;

    @Parameter(label = "Legacy array", defaultValue = "0.5")
    private static double legacy[] = null;

    @Parameter(label = "Separator", description = "Uses internally cached separators")
    private char separator = ',';

    @Parameter(description = "Internal: only used by the graph builder", label = "Hidden")
    private int hiddenCounter = 0;

    private static class Nested {
        @Parameter(label = "Nested parameter must not leak")
        private int nested = 3;
    }
}
