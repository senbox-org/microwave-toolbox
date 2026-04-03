package eu.esa.sar.sar.gpf.filtering.SpeckleFilters;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.engine_utilities.gpf.TileIndex;

import java.awt.Rectangle;
import java.util.Map;
import java.util.stream.IntStream;

@OperatorMetadata(alias = "MuLog-Filter",
        category = "Radar/Speckle Filtering",
        authors = "AI",
        version = "1.0",
        description = "MuLoG (Multi-channel Logarithm with Gaussian denoising) speckle filter")
public class MuLog implements SpeckleFilter {

    private final Operator operator;
    private final Product sourceProduct;
    private final Product targetProduct;

    @Parameter(defaultValue = "10", label = "Number of Iterations")
    private int iterations = 10;

    @Parameter(defaultValue = "1.0", label = "Number of Looks (L)")
    private double enl = 1.0;

    @Parameter(defaultValue = "2", label = "NLM Search Radius")
    private int nlmSearchRadius = 2;

    @Parameter(defaultValue = "1", label = "NLM Patch Radius")
    private int nlmPatchRadius = 1;

    @Parameter(defaultValue = "1.0", label = "NLM Smoothing (h)")
    private double nlmH = 1.0;

    @Parameter(defaultValue = "1.0", label = "ADMM Rho")
    private double rho = 1.0;

    public MuLog(final Operator op, final Product srcProduct, final Product trgProduct,
                 final Map<String, String[]> targetBandNameToSourceBandName) {
        this.operator = op;
        this.sourceProduct = srcProduct;
        this.targetProduct = trgProduct;
    }

    @Override
    public double[][] performFiltering(
            final int x0, final int y0, final int w, final int h, final String[] srcBandNames) {
        // Not used in this implementation as we override computeTile directly
        return null;
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        try {
            final Rectangle targetRect = targetTile.getRectangle();
            final Band sourceBand = sourceProduct.getBand(targetBand.getName());
            if (sourceBand == null) {
                return;
            }

            // Request source tile with padding for NLM
            final int padding = nlmSearchRadius + nlmPatchRadius;
            final Rectangle sourceRect = new Rectangle(
                    targetRect.x - padding,
                    targetRect.y - padding,
                    targetRect.width + 2 * padding,
                    targetRect.height + 2 * padding
            );

            // Clip to image boundaries
            final Rectangle imageRect = new Rectangle(0, 0, sourceProduct.getSceneRasterWidth(), sourceProduct.getSceneRasterHeight());
            final Rectangle effectiveSourceRect = sourceRect.intersection(imageRect);

            final Tile sourceTile = operator.getSourceTile(sourceBand, effectiveSourceRect);
            final ProductData srcData = sourceTile.getDataBuffer();

            final int sw = effectiveSourceRect.width;
            final int sh = effectiveSourceRect.height;
            final int len = sw * sh;

            final float[] y_full = new float[len];
            final float[] u_full = new float[len];
            final float[] w_full = new float[len];
            final float[] v_full = new float[len];
            final float[] inputToDenoiser = new float[len];

            final TileIndex srcIndex = new TileIndex(sourceTile);

            // Sequential initialization to be safe with TileIndex
            for (int y = 0; y < sh; y++) {
                final int sy = effectiveSourceRect.y + y;
                srcIndex.calculateStride(sy);
                for (int x = 0; x < sw; x++) {
                    final int sx = effectiveSourceRect.x + x;
                    final float val = srcData.getElemFloatAt(srcIndex.getIndex(sx));
                    // Log transform: y = ln(I + eps)
                    final float logVal = (float) Math.log(val + 1e-10);
                    y_full[y * sw + x] = logVal;
                    u_full[y * sw + x] = logVal;
                    w_full[y * sw + x] = 0.0f;
                }
            }

            // ADMM Loop
            for (int i = 0; i < iterations; i++) {
                // Step A: Gaussian Denoising (NLM)
                // v = Denoise(u - w)
                
                // Prepare input (Parallel)
                IntStream.range(0, len).parallel().forEach(k -> {
                    inputToDenoiser[k] = u_full[k] - w_full[k];
                });

                applyNLM(inputToDenoiser, v_full, sw, sh, nlmSearchRadius, nlmPatchRadius, nlmH);

                // Step B & C: Proximal Operator & Lagrange Update (Parallel)
                IntStream.range(0, len).parallel().forEach(k -> {
                    final float r = v_full[k] + w_full[k];
                    final float y_val = y_full[k];
                    final float u_new = solveProximal(y_val, r, (float) enl, (float) rho);
                    
                    u_full[k] = u_new;
                    w_full[k] = w_full[k] + v_full[k] - u_new;
                });
            }

            // Exp Transform & Write
            final ProductData tgtData = targetTile.getDataBuffer();
            final TileIndex tgtIndex = new TileIndex(targetTile);

            final int offsetX = targetRect.x - effectiveSourceRect.x;
            final int offsetY = targetRect.y - effectiveSourceRect.y;

            final int w = targetRect.width;
            final int h = targetRect.height;

            // Sequential write to be safe with TileIndex
            for (int y = 0; y < h; y++) {
                final int ty = targetRect.y + y;
                tgtIndex.calculateStride(ty);
                for (int x = 0; x < w; x++) {
                    final int tx = targetRect.x + x;

                    final int sy = y + offsetY;
                    final int sx = x + offsetX;

                    final float u_val = u_full[sy * sw + sx];
                    final float res = (float) Math.exp(u_val);

                    tgtData.setElemFloatAt(tgtIndex.getIndex(tx), res);
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        } finally {
            pm.done();
        }
    }

    private void applyNLM(float[] input, float[] output, int width, int height, int searchRadius, int patchRadius, double hParam) {
        final double h2 = hParam * hParam;

        IntStream.range(0, height).parallel().forEach(y -> {
            for (int x = 0; x < width; x++) {

                double sumWeights = 0.0;
                double sumValues = 0.0;

                final int minSy = Math.max(0, y - searchRadius);
                final int maxSy = Math.min(height - 1, y + searchRadius);
                final int minSx = Math.max(0, x - searchRadius);
                final int maxSx = Math.min(width - 1, x + searchRadius);

                for (int sy = minSy; sy <= maxSy; sy++) {
                    for (int sx = minSx; sx <= maxSx; sx++) {

                        final double dist = computePatchDistance(input, width, height, x, y, sx, sy, patchRadius);
                        final double w = Math.exp(-dist / h2);

                        sumWeights += w;
                        sumValues += w * input[sy * width + sx];
                    }
                }

                output[y * width + x] = (float) (sumValues / sumWeights);
            }
        });
    }

    private double computePatchDistance(float[] img, int w, int h, int x1, int y1, int x2, int y2, int r) {
        // Fast path: if both patches are fully inside the image
        if (x1 - r >= 0 && x1 + r < w && y1 - r >= 0 && y1 + r < h &&
            x2 - r >= 0 && x2 + r < w && y2 - r >= 0 && y2 + r < h) {
            
            double dist = 0.0;
            for (int dy = -r; dy <= r; dy++) {
                int idx1 = (y1 + dy) * w + (x1 - r);
                int idx2 = (y2 + dy) * w + (x2 - r);
                // Unroll loop slightly or just iterate
                for (int dx = -r; dx <= r; dx++) {
                    float diff = img[idx1++] - img[idx2++];
                    dist += diff * diff;
                }
            }
            return dist / ((2 * r + 1) * (2 * r + 1));
        }

        // Slow path: boundary checks
        double dist = 0.0;
        int count = 0;

        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                final int yy1 = y1 + dy;
                final int xx1 = x1 + dx;
                final int yy2 = y2 + dy;
                final int xx2 = x2 + dx;

                if (yy1 >= 0 && yy1 < h && xx1 >= 0 && xx1 < w &&
                        yy2 >= 0 && yy2 < h && xx2 >= 0 && xx2 < w) {

                    final float v1 = img[yy1 * w + xx1];
                    final float v2 = img[yy2 * w + xx2];
                    final float diff = v1 - v2;
                    dist += diff * diff;
                    count++;
                }
            }
        }

        if (count > 0) return dist / count;
        return 0.0;
    }

    private float solveProximal(float y, float r, float L, float rho) {
        // Solve f(u) = L(1 - e^{y-u}) + rho(u - r) = 0
        // Newton-Raphson
        // f'(u) = L e^{y-u} + rho

        float u = r; // Initial guess
        for (int i = 0; i < 5; i++) {
            final double expTerm = Math.exp(y - u);
            final double f = L * (1 - expTerm) + rho * (u - r);
            final double f_prime = L * expTerm + rho;

            u = (float) (u - f / f_prime);
        }
        return u;
    }
}
