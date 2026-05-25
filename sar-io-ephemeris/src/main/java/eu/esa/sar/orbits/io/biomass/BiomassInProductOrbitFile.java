/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.orbits.io.biomass;

import Jama.Matrix;
import eu.esa.sar.orbits.io.BaseOrbitFile;
import eu.esa.sar.orbits.io.OrbitFile;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Orbits;
import org.esa.snap.engine_utilities.util.Maths;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Tier 0 orbit provider for BIOMASS: uses the operational orbit annotations that already ship
 * inside every BIOMASS L1 product (parsed by BiomassProductDirectory into the abstract metadata's
 * Orbit_State_Vectors element). No download, no credentials required.
 */
public class BiomassInProductOrbitFile extends BaseOrbitFile implements OrbitFile {

    public static final String IN_PRODUCT = "Biomass In-Product";

    private final int polyDegree;
    private List<Orbits.OrbitVector> osvList = new ArrayList<>();

    public BiomassInProductOrbitFile(final MetadataElement absRoot, final int polyDegree) {
        super(absRoot);
        this.polyDegree = polyDegree;
    }

    @Override
    public String[] getAvailableOrbitTypes() {
        return new String[]{IN_PRODUCT};
    }

    @Override
    public File retrieveOrbitFile(final String orbitType) throws Exception {
        readFromMetadata();
        if (osvList.isEmpty()) {
            throw new java.io.IOException("BIOMASS product has no in-product orbit state vectors");
        }
        // No physical file; orbits are sourced from product metadata.
        return null;
    }

    @Override
    public boolean isOrbitAlreadyApplied(final MetadataElement productAbsRoot) {
        // By definition, the in-product orbit is already applied.
        return true;
    }

    @Override
    public String getVersion() {
        return absRoot.getAttributeString(AbstractMetadata.VECTOR_SOURCE, null);
    }

    @Override
    public Orbits.OrbitVector getOrbitData(final double utc) {
        final int numVectors = osvList.size();
        final double t0 = osvList.get(0).utcMJD;
        final double tN = osvList.get(numVectors - 1).utcMJD;

        final int numVecPolyFit = polyDegree + 1;
        final int halfNumVecPolyFit = numVecPolyFit / 2;
        final int[] vectorIndices = new int[numVecPolyFit];

        final int vecIdx = (int) ((utc - t0) / (tN - t0) * (numVectors - 1));
        if (vecIdx <= halfNumVecPolyFit - 1) {
            for (int i = 0; i < numVecPolyFit; i++) vectorIndices[i] = i;
        } else if (vecIdx >= numVectors - halfNumVecPolyFit) {
            for (int i = 0; i < numVecPolyFit; i++) vectorIndices[i] = numVectors - numVecPolyFit + i;
        } else {
            for (int i = 0; i < numVecPolyFit; i++) vectorIndices[i] = vecIdx - halfNumVecPolyFit + 1 + i;
        }

        final double[] timeArray = new double[numVecPolyFit];
        final double[] xPosArray = new double[numVecPolyFit];
        final double[] yPosArray = new double[numVecPolyFit];
        final double[] zPosArray = new double[numVecPolyFit];
        final double[] xVelArray = new double[numVecPolyFit];
        final double[] yVelArray = new double[numVecPolyFit];
        final double[] zVelArray = new double[numVecPolyFit];

        for (int i = 0; i < numVecPolyFit; i++) {
            final Orbits.OrbitVector v = osvList.get(vectorIndices[i]);
            timeArray[i] = v.utcMJD - t0;
            xPosArray[i] = v.xPos;
            yPosArray[i] = v.yPos;
            zPosArray[i] = v.zPos;
            xVelArray[i] = v.xVel;
            yVelArray[i] = v.yVel;
            zVelArray[i] = v.zVel;
        }

        final Matrix A = Maths.createVandermondeMatrix(timeArray, polyDegree);
        final double[] xPosCoeff = Maths.polyFit(A, xPosArray);
        final double[] yPosCoeff = Maths.polyFit(A, yPosArray);
        final double[] zPosCoeff = Maths.polyFit(A, zPosArray);
        final double[] xVelCoeff = Maths.polyFit(A, xVelArray);
        final double[] yVelCoeff = Maths.polyFit(A, yVelArray);
        final double[] zVelCoeff = Maths.polyFit(A, zVelArray);

        final double normalizedTime = utc - t0;
        return new Orbits.OrbitVector(utc,
                Maths.polyVal(normalizedTime, xPosCoeff),
                Maths.polyVal(normalizedTime, yPosCoeff),
                Maths.polyVal(normalizedTime, zPosCoeff),
                Maths.polyVal(normalizedTime, xVelCoeff),
                Maths.polyVal(normalizedTime, yVelCoeff),
                Maths.polyVal(normalizedTime, zVelCoeff));
    }

    private void readFromMetadata() {
        osvList.clear();
        final MetadataElement vectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);
        if (vectorListElem == null) return;

        for (MetadataElement v : vectorListElem.getElements()) {
            final double utcMJD = v.getAttributeUTC(AbstractMetadata.orbit_vector_time).getMJD();
            osvList.add(new Orbits.OrbitVector(utcMJD,
                    v.getAttributeDouble(AbstractMetadata.orbit_vector_x_pos),
                    v.getAttributeDouble(AbstractMetadata.orbit_vector_y_pos),
                    v.getAttributeDouble(AbstractMetadata.orbit_vector_z_pos),
                    v.getAttributeDouble(AbstractMetadata.orbit_vector_x_vel),
                    v.getAttributeDouble(AbstractMetadata.orbit_vector_y_vel),
                    v.getAttributeDouble(AbstractMetadata.orbit_vector_z_vel)));
        }
        osvList.sort(new Orbits.OrbitComparator());
    }
}
