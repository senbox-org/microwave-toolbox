/*
 * Copyright (C) 2024 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.ocean.worldwind.layers.ocn;

import eu.esa.sar.ocean.worldwind.layers.Level2ProductLayer;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.render.BasicShapeAttributes;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.Material;
import gov.nasa.worldwind.render.Path;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.render.ShapeAttributes;
import gov.nasa.worldwindx.examples.util.DirectedPath;
import org.apache.commons.math3.util.FastMath;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.worldwind.ArrowInfo;
import org.esa.snap.worldwind.ColorBarLegend;
import org.esa.snap.worldwind.ProductRenderablesInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class OWI extends OCNComponent {

    // default colour range of the wind speed in m/s
    public static final int DEFAULT_MAX_WIND_SPEED = 10;

    private boolean theOWIArrowsDisplayed = false;

    // this is the dimension of the cell in which to draw an arrow
    // at the highest resolution
    private static final int theOWIArrowCellSize = 4;

    // the number of resolutions for OWI arrows
    private static final int theOWIArrowNumLevels = 5;

    public OWI(final Level2ProductLayer wwLayer,
               final Map<String, ColorBarLegend> theColorBarLegendHash,
               final Map<Object, String> theObjectInfoHash,
               final Map<Object, Product> theSurfaceProductHash,
               final Map<Object, Integer> theSurfaceSequenceHash) {
        super(wwLayer,
                theColorBarLegendHash,
                theObjectInfoHash,
                theSurfaceProductHash,
                theSurfaceSequenceHash);
    }

    @Override
    public void setArrowsDisplayed(boolean displayed) {
        theOWIArrowsDisplayed = displayed;
    }

    @Override
    public void addProduct(final Product product, final ProductRenderablesInfo productRenderablesInfo)
            throws IOException {
        final MetadataElement metadataRoot = product.getMetadataRoot();
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final String acquisitionMode = absRoot != null ?
                absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE, "") : "";

        final Band owiLonBand = findFirstBand(product, "owiLon");
        final Band owiLatBand = findFirstBand(product, "owiLat");
        final Band owiIncAngleBand = findFirstBand(product, "owiIncidenceAngle");
        final Band owiWindSpeedBand = findFirstBand(product, "owiWindSpeed");
        final Band owiWindDirBand = findFirstBand(product, "owiWindDirection");

        final boolean hasGrid = owiLonBand != null && owiLatBand != null && owiIncAngleBand != null &&
                owiWindSpeedBand != null && owiWindDirBand != null;
        if (hasGrid) {
            final int width = owiLonBand.getRasterWidth();
            final int height = owiLonBand.getRasterHeight();
            for (Band band : new Band[]{owiLatBand, owiIncAngleBand, owiWindSpeedBand, owiWindDirBand}) {
                if (band.getRasterWidth() != width || band.getRasterHeight() != height) {
                    throw new IOException("OWI band " + band.getName() + " is " + band.getRasterWidth() + " x " +
                                                  band.getRasterHeight() + " pixels, the positions " + width + " x " + height);
                }
            }
            final double[] lonValues = readValues(owiLonBand);
            final double[] latValues = readValues(owiLatBand);
            final double[] incAngleValues = readValues(owiIncAngleBand);
            final double[] windSpeedValues = readValues(owiWindSpeedBand);
            final double[] windDirValues = readValues(owiWindDirBand);

            addWindSpeedArrows(latValues, lonValues, incAngleValues, windSpeedValues, windDirValues,
                    width, height, productRenderablesInfo.theRenderableListHash.get("owi"));

            createColorSurfaceWithGradient(null, null, latValues, lonValues, windSpeedValues,
                    width, height, 0, DEFAULT_MAX_WIND_SPEED,
                    false, productRenderablesInfo.theRenderableListHash.get("owi"), productRenderablesInfo, "owi");
        }

        double[][] owiData = null;

        if (acquisitionMode.equalsIgnoreCase("WV") &&
                metadataRoot.getElement("Original_Product_Metadata") != null &&
                metadataRoot.getElement("Original_Product_Metadata").getElement("annotation") != null) {
            int numElements = metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getNumElements();
            if (numElements > 0) {
                owiData = new double[3][numElements];

                int i = 0;
                for (MetadataElement element : metadataRoot.getElement("Original_Product_Metadata").getElement("annotation").getElements()) {

                    owiData[0][i] = getData(element.getElement("owiLat"));
                    owiData[1][i] = getData(element.getElement("owiLon"));
                    owiData[2][i] = getData(element.getElement("owiWindSpeed"));

                    i++;
                }
            }
        }

        if (owiData != null && !hasGrid) {
            //addWaveLengthArrows(owiData[0], owiData[1], owiData[3], owiData[4], productRenderablesInfo.theRenderableListHash.get("owi"));
            createWVColorSurfaceWithGradient(product, owiData[0], owiData[1], owiData[2], 0, DEFAULT_MAX_WIND_SPEED,
                    false, productRenderablesInfo.theRenderableListHash.get("owi"), productRenderablesInfo, "owi");
        }
    }

    ArrowInfo[][][] addWindSpeedArrows(double[] latValues,
                                    double[] lonValues,
                                    double[] incAngleValues,
                                    double[] windSpeedValues,
                                    double[] windDirValues,
                                    int width,
                                    int height,
                                    List<Renderable> renderableList) {
        double arrowLength_deg = computeGridSpacingDeg(latValues, lonValues, width, height);
        if (!(arrowLength_deg > 0)) {
            // no valid neighbouring positions; fall back to the nominal OCN grid spacing of about 1 km
            arrowLength_deg = 0.01;
        }

        arrowLength_deg = arrowLength_deg * theOWIArrowCellSize;
        // let the arrow head be approximately one third of the whole length
        double arrowHeadLength = Angle.fromDegrees(arrowLength_deg).radians * GLOBE_RADIUS / 3;

        final ShapeAttributes dpAttrs = new BasicShapeAttributes();
        dpAttrs.setOutlineMaterial(Material.BLACK);
        dpAttrs.setOutlineWidth(2d);


        //int numCellRows = (int) Math.ceil((height / cellSize));
        //int numCellCols = (int) Math.ceil((width / cellSize));

        int numCellRows = height / theOWIArrowCellSize;
        int numCellCols = width / theOWIArrowCellSize;
        // the last cells may be partial, if the grid size is not a multiple of the cell size
        final int lastCellRow = Math.max(0, height - 1) / theOWIArrowCellSize;
        final int lastCellCol = Math.max(0, width - 1) / theOWIArrowCellSize;
        //SystemUtils.LOG.info(":: numCells: " + numCellRows + " " + numCellCols);

        // we need to add 1 because if height is not divisible by cellSize then (height / cellSize) is equal
        // to (height-1) / cellSize so the last element is [numCellRows]
        // (this same argument applies to width)
        // Still, we'll keep numCellRows and numCellCols as limits when we iterate
        // through it and disregard this possible last element (which is the remainder, in the corners of the whole area)
        ArrowInfo[][][] arrowGrid = new ArrowInfo[theOWIArrowNumLevels][numCellRows + 1][numCellCols + 1];

        for (int row = 0; row < height; row = row + theOWIArrowCellSize) {
            for (int col = 0; col < width; col = col + theOWIArrowCellSize) {
                //int i = row*width + col;
                final PositionMean avgPos = new PositionMean();
                final DirectionMean avgDir = new DirectionMean();
                double avgIncAngle = 0;
                int numIncAngles = 0;
                double avgWindSpeed = 0;
                int numValid = 0;
                int finalCellRow = row + theOWIArrowCellSize;
                int finalCellCol = col + theOWIArrowCellSize;

                if (finalCellRow > height) {
                    finalCellRow = height;
                }
                if (finalCellCol > width) {
                    finalCellCol = width;
                }
                for (int currCellRow = row; currCellRow < finalCellRow; currCellRow++) {
                    for (int currCellCol = col; currCellCol < finalCellCol; currCellCol++) {
                        int i = currCellRow * width + currCellCol;
                        // skip fill values (land, outside the swath)
                        if (!isValidLatLon(latValues[i], lonValues[i]) || Double.isNaN(windSpeedValues[i]) ||
                                Double.isNaN(windDirValues[i])) {
                            continue;
                        }
                        avgPos.add(latValues[i], lonValues[i]);
                        avgDir.add(windDirValues[i]);
                        if (!Double.isNaN(incAngleValues[i])) {
                            avgIncAngle += incAngleValues[i];
                            numIncAngles++;
                        }
                        avgWindSpeed += windSpeedValues[i];
                        numValid++;
                    }
                }

                int currCellRow = row / theOWIArrowCellSize;
                int currCellCol = col / theOWIArrowCellSize;
                final double avgWindDir = avgDir.getMeanDeg();
                // cells without valid data (land, outside the swath) get no arrow;
                // coarser levels average the remaining cells
                if (numValid > 0 && !Double.isNaN(avgWindDir)) {
                    avgIncAngle = numIncAngles > 0 ? avgIncAngle / numIncAngles : Double.NaN;
                    avgWindSpeed = avgWindSpeed / numValid;


                    //System.out.println("avgIncAngle " + avgIncAngle);
                    //for (int i = 0; i < latValues.length; i=i+50) {
                    //System.out.println(lonValues[i] + "::==::" + latValues[i] + "::==::" + incAngleValues[i] + "::==::" + windSpeedValues[i] + "::==::" + windDirValues[i] + "::==::");
                    final Position startPos = new Position(Angle.fromDegreesLatitude(avgPos.getLatDeg()), Angle.fromDegreesLongitude(avgPos.getLonDeg()), 10.0);
                    final Position endPos = new Position(LatLon.greatCircleEndPosition(startPos, Angle.fromDegrees(toArrowAzimuthDeg(avgWindDir)), Angle.fromDegrees(arrowLength_deg)), 10.0);

                    //System.out.println("startPos " + startPos + " endPos " + endPos);

                    final List<Position> positions = new ArrayList<>();
                    positions.add(startPos);
                    positions.add(endPos);

                    final DirectedPath directedPath = getDirectedPath(positions, dpAttrs);

                    //double arrowHeadLength = computeSegmentLength(directedPath, dc, startPos, endPos) / 4;
                    directedPath.setArrowLength(arrowHeadLength);
                    //SystemUtils.LOG.info(":: currCell: " + currCellRow + " " + currCellCol);
                    arrowGrid[0][currCellRow][currCellCol] = new ArrowInfo(directedPath, avgIncAngle, avgWindSpeed, avgWindDir, arrowLength_deg);
                }

                //if (currCellRow > 0 && currCellCol > 0) {
                for (int cellSizeResolution = 1; cellSizeResolution < theOWIArrowNumLevels; cellSizeResolution++) {
                    // treating the original cell size as 1
                    int currBigCellSize = (int) FastMath.pow(2, cellSizeResolution);
                    // a big cell is built at its last small cell; partial big cells at the end of the grid as well,
                    // otherwise the edges of the swath have no arrows at the coarser levels
                    if ((currCellRow % currBigCellSize == currBigCellSize - 1 || currCellRow == lastCellRow) &&
                            (currCellCol % currBigCellSize == currBigCellSize - 1 || currCellCol == lastCellCol)) {
                        int bigCellRow = (currCellRow / currBigCellSize);
                        int bigCellCol = (currCellCol / currBigCellSize);

                        int smallCellStartRow = bigCellRow * 2;
                        int smallCellStartCol = bigCellCol * 2;

                        double cumAvgIncAngle = 0;
                        int numCumIncAngles = 0;
                        double cumAvgWindSpeed = 0;
                        final DirectionMean cumAvgDir = new DirectionMean();
                        final PositionMean cumStartPos = new PositionMean();
                        int numSmallArrows = 0;
                        double bigCellArrowLength_deg = currBigCellSize * arrowLength_deg;
                        for (int currSmallCellRow = smallCellStartRow; currSmallCellRow < smallCellStartRow + 2; currSmallCellRow++) {
                            for (int currSmallCellCol = smallCellStartCol; currSmallCellCol < smallCellStartCol + 2; currSmallCellCol++) {
                                if (currSmallCellRow >= arrowGrid[cellSizeResolution - 1].length ||
                                        currSmallCellCol >= arrowGrid[cellSizeResolution - 1][currSmallCellRow].length) {
                                    // beyond the end of the grid
                                    continue;
                                }
                                ArrowInfo currSmallArrow = arrowGrid[cellSizeResolution - 1][currSmallCellRow][currSmallCellCol];
                                if (currSmallArrow == null) {
                                    // cell without valid data
                                    continue;
                                }
                                if (!Double.isNaN(currSmallArrow.theAvgIncAngle)) {
                                    cumAvgIncAngle += currSmallArrow.theAvgIncAngle;
                                    numCumIncAngles++;
                                }
                                cumAvgWindSpeed += currSmallArrow.theAvgWindSpeed;
                                cumAvgDir.add(currSmallArrow.theAvgWindDir);
                                for (Position pos : currSmallArrow.theDirectedPath.getPositions()) {
                                    // the first position is the start of the arrow
                                    cumStartPos.add(pos.getLatitude().getDegrees(), pos.getLongitude().getDegrees());
                                    break;
                                }
                                numSmallArrows++;
                            }
                        }

                        arrowGrid[cellSizeResolution][bigCellRow][bigCellCol] = null;
                        final double cumAvgWindDir = cumAvgDir.getMeanDeg();
                        if (numSmallArrows == 0 || Double.isNaN(cumAvgWindDir)) {
                            continue;
                        }
                        cumAvgIncAngle = numCumIncAngles > 0 ? cumAvgIncAngle / numCumIncAngles : Double.NaN;
                        cumAvgWindSpeed = cumAvgWindSpeed / numSmallArrows;

                        Position bigCellStartPos = new Position(Angle.fromDegreesLatitude(cumStartPos.getLatDeg()), Angle.fromDegreesLongitude(cumStartPos.getLonDeg()), 10.0);
                        Position bigCellEndPos = new Position(LatLon.greatCircleEndPosition(bigCellStartPos, Angle.fromDegrees(toArrowAzimuthDeg(cumAvgWindDir)), Angle.fromDegrees(bigCellArrowLength_deg)), 10.0);

                        //System.out.println("startPos " + startPos + " endPos " + endPos);
                        List<Position> bigCellPositions = new ArrayList<>();
                        bigCellPositions.add(bigCellStartPos);
                        bigCellPositions.add(bigCellEndPos);

                        DirectedPath bigDC = getDirectedPath(bigCellPositions, dpAttrs);
                        bigDC.setArrowLength(currBigCellSize * arrowHeadLength);
                        arrowGrid[cellSizeResolution][bigCellRow][bigCellCol] = new ArrowInfo(bigDC, cumAvgIncAngle, cumAvgWindSpeed, cumAvgWindDir, bigCellArrowLength_deg);

                    }

                }
                //}

            }
        }
        for (int cellRow = 0; cellRow <= lastCellRow; cellRow++) {
            for (int cellCol = 0; cellCol <= lastCellCol; cellCol++) {

                final int finalCellRow = cellRow;
                final int finalCellCol = cellCol;

                //DirectedPath directedPath = arrowGrid[0][cellRow][cellCol].theDirectedPath;
                Renderable renderable = new Renderable() {
                    public void render(DrawContext dc) {
                        if (!theOWIArrowsDisplayed) {
                            return;
                        }

                        // this is the length of the arrow head actually
                        //double arrowHeadLength = computeSegmentLength(directedPath, dc, startPos, endPos) / 4;
                        //directedPath.setArrowLength(arrowHeadLength);

                        //double maxHeight = cellSize * 0.5e6 / 16;

                        double currAlt = dc.getView().getCurrentEyePosition().getAltitude();
                        /*
                        int selectedResolutionInd = 0;
                        for (int resolutionInd = 0; resolutionInd < theOWIArrowNumLevels; resolutionInd++) {
                            double maxResAlt = (0.5e6 / 4) * Math.pow(2,resolutionInd);
                            double minResAlt = maxResAlt / 2;
                            if (currAlt > minResAlt && currAlt < maxResAlt) {
                                selectedResolutionInd = resolutionInd;
                                break;
                            }
                        }
                        */
                        int selectedResolutionInd = (int) (Math.log(currAlt * (4 / 0.5e6)) / Math.log(2));
                        if (selectedResolutionInd < 0) {
                            selectedResolutionInd = 0;
                        } else if (selectedResolutionInd > 4) {
                            selectedResolutionInd = 4;
                        }
                        int selectedInd = (int) FastMath.pow(2, selectedResolutionInd);


                        //int selectedInd = (int) (currAlt * (4 / 0.5e6));
                        if ((finalCellRow % selectedInd == 0) && (finalCellCol % selectedInd == 0)) {
                            int bigCellRow = finalCellRow / selectedInd;
                            int bigCellCol = finalCellCol / selectedInd;

                            // this check is necessary because the possible last element which we disregarded and which is null

                            if (arrowGrid[selectedResolutionInd][bigCellRow] != null && arrowGrid[selectedResolutionInd][bigCellRow][bigCellCol] != null) {

                                ArrowInfo currArrow = arrowGrid[selectedResolutionInd][bigCellRow][bigCellCol];

                                // we won't render the arrow if the wind speed is zero
                                if (currArrow.theAvgWindSpeed > 0) {
                                    DirectedPath currDirectedPath = currArrow.theDirectedPath;
                                    currDirectedPath.render(dc);


                                    if (theObjectInfoHash.get(currDirectedPath) == null) {
                                        String info = "Wind Speed: " + currArrow.theAvgWindSpeed + "<br/>";
                                        info += "Wind Direction (from): " + currArrow.theAvgWindDir + "<br/>";
                                        info += "Incidence Angle: " + currArrow.theAvgIncAngle + "<br/>";
                                        theObjectInfoHash.put(currDirectedPath, info);
                                    }
                                }
                            }
                        }

                        /*
                        if (currAlt > minHeight && currAlt < maxHeight) {
                            directedPath.render(dc);
                            //System.out.println("arrowHeadLength " + arrowHeadLength);
                        }
                        */

                        //System.out.println("eyePosition " + dc.getView().getCurrentEyePosition());
                    }
                };

                theWWLayer.addRenderable(renderable);
                if (renderableList != null) {
                    renderableList.add(renderable);
                }

            }
        }
        return arrowGrid;
    }

    /**
     * Grid spacing in degrees of arc: the smaller of the great circle distances from the valid grid point nearest
     * the centre row to its right and lower neighbours. The distance between two corners does not work: depending
     * on the heading of the swath, their latitude or longitude difference can be close to zero.
     *
     * @return the spacing, or NaN if no grid point has valid right and lower neighbours
     */
    static double computeGridSpacingDeg(final double[] latValues, final double[] lonValues,
                                        final int width, final int height) {
        final int centreRow = height / 2;
        for (int d = 0; d <= height; d++) {
            for (int row : new int[]{centreRow - d, centreRow + d}) {
                if (row < 0 || row + 1 >= height) {
                    continue;
                }
                for (int col = 0; col + 1 < width; col++) {
                    final int i = row * width + col;
                    if (isValidLatLon(latValues[i], lonValues[i]) &&
                            isValidLatLon(latValues[i + 1], lonValues[i + 1]) &&
                            isValidLatLon(latValues[i + width], lonValues[i + width])) {
                        final LatLon pos = LatLon.fromDegrees(latValues[i], normalizeLon(lonValues[i]));
                        final double dx = LatLon.greatCircleDistance(pos,
                                LatLon.fromDegrees(latValues[i + 1], normalizeLon(lonValues[i + 1]))).degrees;
                        final double dy = LatLon.greatCircleDistance(pos,
                                LatLon.fromDegrees(latValues[i + width], normalizeLon(lonValues[i + width]))).degrees;
                        return Math.min(dx, dy);
                    }
                }
            }
        }
        return Double.NaN;
    }

    private static double computeSegmentLength(Path path, DrawContext dc, Position posA, Position posB) {
        final LatLon llA = new LatLon(posA.getLatitude(), posA.getLongitude());
        final LatLon llB = new LatLon(posB.getLatitude(), posB.getLongitude());

        Angle ang;
        String pathType = path.getPathType();
        if (Objects.equals(pathType, AVKey.LINEAR)) {
            ang = LatLon.linearDistance(llA, llB);
        } else if (Objects.equals(pathType, AVKey.RHUMB_LINE) || Objects.equals(pathType, AVKey.LOXODROME)) {
            ang = LatLon.rhumbDistance(llA, llB);
        } else { // Great circle
            ang = LatLon.greatCircleDistance(llA, llB);
        }

        if (path.getAltitudeMode() == WorldWind.CLAMP_TO_GROUND) {
            return ang.radians * (dc.getGlobe().getRadius());
        }

        final double height = 0.5 * (posA.getElevation() + posB.getElevation());
        return ang.radians * (dc.getGlobe().getRadius() + height * dc.getVerticalExaggeration());
    }
}
