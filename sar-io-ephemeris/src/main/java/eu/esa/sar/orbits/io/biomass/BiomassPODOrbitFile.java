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
import eu.esa.sar.cloud.opendata.MaapBiomassOrbitAccess;
import eu.esa.sar.orbits.io.BaseOrbitFile;
import eu.esa.sar.orbits.io.OrbitFile;
import eu.esa.sar.orbits.io.sentinel1.Sentinel1OrbitFileReader;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.util.StringUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Orbits;
import org.esa.snap.engine_utilities.util.Maths;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tier 1 orbit provider for BIOMASS: pulls AUX_ORB___ (operational precise orbit) from the
 * ESA MAAP STAC catalogue via {@link MaapBiomassOrbitAccess}. Requires user-supplied credentials
 * registered in the SNAP Product Library under the repository name "ESA MAAP" (password = 90-day
 * offline token).
 *
 * Files are cached under {@code <auxdata>/Orbits/Biomass/AUXORB/<year>/<month>/} and parsed with
 * {@link Sentinel1OrbitFileReader} (BIOMASS uses the same Earth Explorer FFS v3 EOF schema).
 */
public class BiomassPODOrbitFile extends BaseOrbitFile implements OrbitFile {

    public static final String PRECISE = "Biomass Precise (MAAP)";

    private final int polyDegree;
    private List<Orbits.OrbitVector> osvList = new ArrayList<>();
    private String fileVersion;

    public BiomassPODOrbitFile(final MetadataElement absRoot, final int polyDegree) {
        super(absRoot);
        this.polyDegree = polyDegree;
    }

    @Override
    public String[] getAvailableOrbitTypes() {
        return new String[]{PRECISE};
    }

    @Override
    public File retrieveOrbitFile(final String orbitType) throws Exception {
        final ProductData.UTC stateVectorTime = absRoot.getAttributeUTC(AbstractMetadata.STATE_VECTOR_TIME);
        final Calendar calendar = stateVectorTime.getAsCalendar();
        final int year = calendar.get(Calendar.YEAR);
        final int month = calendar.get(Calendar.MONTH) + 1;

        final File localFolder = getDestFolder(year, month);

        orbitFile = findCachedOrbitFile(localFolder, stateVectorTime);

        if (orbitFile == null) {
            final MaapBiomassOrbitAccess maap = new MaapBiomassOrbitAccess();
            final String acquisitionISO = stateVectorTime.format();
            final MaapBiomassOrbitAccess.Result hit = maap.searchByTime(
                    MaapBiomassOrbitAccess.PRODUCT_TYPE_AUX_ORB, acquisitionISO);
            if (hit == null) {
                throw new IOException("No BIOMASS AUX_ORB file found on MAAP for " + acquisitionISO);
            }
            orbitFile = maap.download(hit, localFolder);
        }

        if (orbitFile == null || !orbitFile.exists()) {
            throw new IOException("BiomassPODOrbitFile: unable to retrieve precise orbit");
        }

        readOrbitFile();
        return orbitFile;
    }

    @Override
    public boolean isOrbitAlreadyApplied(final MetadataElement productAbsRoot) {
        if (orbitFile == null) return false;
        final String productSource = productAbsRoot.getAttributeString(AbstractMetadata.VECTOR_SOURCE, null);
        if (productSource == null || productSource.isEmpty()) return false;

        final String baseName = stripExtension(orbitFile.getName());
        // Match either way: VECTOR_SOURCE may carry the full filename or just the identifier.
        return productSource.contains(baseName) || baseName.contains(stripExtension(productSource));
    }

    @Override
    public String getVersion() {
        return fileVersion;
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

    private static File getDestFolder(final int year, final int month) {
        final File folder = SystemUtils.getAuxDataPath()
                .resolve("Orbits").resolve("Biomass").resolve("AUXORB")
                .resolve(String.valueOf(year))
                .resolve(StringUtils.padNum(month, 2, '0'))
                .toFile();
        folder.mkdirs();
        return folder;
    }

    private static File findCachedOrbitFile(final File folder, final ProductData.UTC stateVectorTime) {
        if (!folder.exists()) return null;
        final File[] files = folder.listFiles(new EofFilter());
        if (files == null || files.length == 0) return null;
        for (File file : files) {
            if (isWithinRange(file.getName(), stateVectorTime)) {
                return file;
            }
        }
        return null;
    }

    /**
     * BIOMASS-aware filename validity check. Earth Explorer FFS v3 BIOMASS AUX_ORB filenames have
     * the form {@code BIO_<class>_AUX_ORB____<creation>_V<start>_<stop>.EOF}. The 'V' delimiter
     * before the validity start sits at a different offset than for Sentinel-1 (which adds an
     * extra OPOD_ field), so we locate it by searching for the {@code _V<digits>_<digits>} suffix.
     */
    static boolean isWithinRange(final String filename, final ProductData.UTC stateVectorTime) {
        final Matcher m = VALIDITY_PATTERN.matcher(filename);
        if (!m.find()) return false;
        try {
            final ProductData.UTC start = ProductData.UTC.parse(toDashTime(m.group(1)), VALIDITY_FORMAT);
            final ProductData.UTC stop = ProductData.UTC.parse(toDashTime(m.group(2)), VALIDITY_FORMAT);
            final double mjd = stateVectorTime.getMJD();
            return mjd >= start.getMJD() && mjd < stop.getMJD();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final Pattern VALIDITY_PATTERN =
            Pattern.compile("_V(\\d{8}T\\d{6})_(\\d{8}T\\d{6})");
    private static final DateFormat VALIDITY_FORMAT = ProductData.UTC.createDateFormat("yyyyMMdd-HHmmss");

    private static String toDashTime(final String compact) {
        return compact.replace("T", "-");
    }

    private void readOrbitFile() throws Exception {
        final Sentinel1OrbitFileReader reader = new Sentinel1OrbitFileReader(orbitFile);
        reader.read();
        osvList = reader.getOrbitStateVectors();
        fileVersion = reader.getFileVersion();
    }

    private static String stripExtension(final String name) {
        if (name == null) return null;
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static class EofFilter implements FilenameFilter {
        @Override
        public boolean accept(File dir, String name) {
            final String u = name.toUpperCase();
            return (u.endsWith(".EOF") || u.endsWith(".ZIP")) && u.startsWith("BIO_");
        }
    }
}
