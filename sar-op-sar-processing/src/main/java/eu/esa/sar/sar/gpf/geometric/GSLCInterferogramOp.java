/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.sar.gpf.geometric;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.engine_utilities.datamodel.Unit;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@OperatorMetadata(alias = "GSLC-Interferogram",
        category = "Radar/Interferometry",
        authors = "Luis Veci",
        version = "1.0",
        copyright = "Copyright (C) 2026 by SkyWatch Space Applications Inc.",
        description = "Computes interferogram from a stack of coregistered GSLC products without additional phase corrections")
public class GSLCInterferogramOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct
    private Product targetProduct;

    private final Map<Band, Band[]> targetBandToSourceBands = new HashMap<>();

    @Override
    public void initialize() throws OperatorException {
        try {
            targetProduct = new Product(sourceProduct.getName() + "_IFG",
                    sourceProduct.getProductType(),
                    sourceProduct.getSceneRasterWidth(),
                    sourceProduct.getSceneRasterHeight());

            ProductUtils.copyMetadata(sourceProduct, targetProduct);
            targetProduct.setSceneGeoCoding(sourceProduct.getSceneGeoCoding());

            // Identify Master and Slave bands
            // Assuming CreateStack naming convention: mst_... and slv_... or just order
            // For simplicity, we'll look for pairs of Real/Imaginary bands
            
            List<Band> complexBands = new ArrayList<>();
            for (Band b : sourceProduct.getBands()) {
                if (b.getUnit() != null && b.getUnit().equals(Unit.REAL)) {
                    // Check for corresponding Imaginary
                    int idx = sourceProduct.getBandIndex(b.getName());
                    if (idx + 1 < sourceProduct.getNumBands()) {
                        Band next = sourceProduct.getBandAt(idx + 1);
                        if (next.getUnit() != null && next.getUnit().equals(Unit.IMAGINARY)) {
                            complexBands.add(b); // Add the Real band of the pair
                        }
                    }
                }
            }

            if (complexBands.size() < 2) {
                throw new OperatorException("Input product must contain at least two complex pairs (Master and Slave)");
            }

            // Assume first pair is Master, second is Slave (simplified logic)
            // In a real scenario, we might want parameters to select them
            Band mI = complexBands.get(0);
            Band mQ = sourceProduct.getBandAt(sourceProduct.getBandIndex(mI.getName()) + 1);
            
            Band sI = complexBands.get(1);
            Band sQ = sourceProduct.getBandAt(sourceProduct.getBandIndex(sI.getName()) + 1);

            // Create Output Bands
            Band ifgI = targetProduct.addBand("i_ifg", ProductData.TYPE_FLOAT32);
            ifgI.setUnit(Unit.REAL);
            ifgI.setNoDataValue(0.0);
            
            Band ifgQ = targetProduct.addBand("q_ifg", ProductData.TYPE_FLOAT32);
            ifgQ.setUnit(Unit.IMAGINARY);
            ifgQ.setNoDataValue(0.0);

            Band phase = targetProduct.addBand("Phase_ifg", ProductData.TYPE_FLOAT32);
            phase.setUnit(Unit.PHASE);
            phase.setNoDataValue(0.0);
            
            Band coh = targetProduct.addBand("Coherence", ProductData.TYPE_FLOAT32);
            coh.setUnit(Unit.COHERENCE);
            coh.setNoDataValue(0.0);

            targetBandToSourceBands.put(ifgI, new Band[]{mI, mQ, sI, sQ});
            targetBandToSourceBands.put(ifgQ, new Band[]{mI, mQ, sI, sQ});
            targetBandToSourceBands.put(phase, new Band[]{mI, mQ, sI, sQ});
            targetBandToSourceBands.put(coh, new Band[]{mI, mQ, sI, sQ});

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        try {
            Rectangle rect = targetTile.getRectangle();
            Band[] srcBands = targetBandToSourceBands.get(targetBand);
            
            Tile tmI = getSourceTile(srcBands[0], rect);
            Tile tmQ = getSourceTile(srcBands[1], rect);
            Tile tsI = getSourceTile(srcBands[2], rect);
            Tile tsQ = getSourceTile(srcBands[3], rect);

            ProductData dmI = tmI.getDataBuffer();
            ProductData dmQ = tmQ.getDataBuffer();
            ProductData dsI = tsI.getDataBuffer();
            ProductData dsQ = tsQ.getDataBuffer();
            ProductData dt = targetTile.getDataBuffer();

            int w = rect.width;
            int h = rect.height;

            // Simple pixel-by-pixel interferogram
            // If Coherence is requested, we need a window. For now, simple single-look coherence (always 1.0) 
            // or just magnitude for visualization.
            // Let's just compute the complex product.

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    
                    double mi = dmI.getElemDoubleAt(idx);
                    double mq = dmQ.getElemDoubleAt(idx);
                    double si = dsI.getElemDoubleAt(idx);
                    double sq = dsQ.getElemDoubleAt(idx);

                    // Master * conj(Slave)
                    // (mi + j*mq) * (si - j*sq)
                    // Real: mi*si + mq*sq
                    // Imag: mq*si - mi*sq
                    
                    double real = mi * si + mq * sq;
                    double imag = mq * si - mi * sq;

                    if (targetBand.getName().equals("i_ifg")) {
                        dt.setElemDoubleAt(idx, real);
                    } else if (targetBand.getName().equals("q_ifg")) {
                        dt.setElemDoubleAt(idx, imag);
                    } else if (targetBand.getName().equals("Phase_ifg")) {
                        dt.setElemDoubleAt(idx, Math.atan2(imag, real));
                    } else if (targetBand.getName().equals("Coherence")) {
                        // Placeholder: Magnitude of IFG / (Mag(M) * Mag(S))
                        double magM = Math.sqrt(mi*mi + mq*mq);
                        double magS = Math.sqrt(si*si + sq*sq);
                        double magIfg = Math.sqrt(real*real + imag*imag);
                        if (magM > 0 && magS > 0)
                            dt.setElemDoubleAt(idx, magIfg / (magM * magS));
                        else 
                            dt.setElemDoubleAt(idx, 0.0);
                    }
                }
            }

        } catch (Exception e) {
            throw new OperatorException(e);
        }
    }

    public static class Spi extends OperatorSpi {
        public Spi() {
            super(GSLCInterferogramOp.class);
        }
    }
}
