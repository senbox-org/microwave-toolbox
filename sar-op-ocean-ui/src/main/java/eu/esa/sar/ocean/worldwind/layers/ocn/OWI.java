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

import com.bc.ceres.core.ProgressMonitor;
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
import org.esa.snap.core.datamodel.GeoPos;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.PixelPos;
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
        final Band firstBand = product.getBandAt(0);
        final String firstBandName = firstBand.getName().toLowerCase();
        final String prefix = firstBandName.startsWith("vv") ? "vv" : "hh";

        final MetadataElement metadataRoot = product.getMetadataRoot();
        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final String acquisitionMode = absRoot.getAttributeString(AbstractMetadata.ACQUISITION_MODE);

        final GeoPos geoPos1 = product.getSceneGeoCoding().getGeoPos(new PixelPos(0, 0), null);
        final GeoPos geoPos2 = product.getSceneGeoCoding().getGeoPos(new PixelPos(product.getSceneRasterWidth() - 1,
                product.getSceneRasterHeight() - 1), null);

        final Band owiLonBand = findBands(product, "owiLon")[0];
        final Band owiLatBand = findBands(product, "owiLat")[0];
        final Band owiIncAngleBand = findBands(product, "owiIncidenceAngle")[0];
        final Band owiWindSpeedBand = findBands(product, "owiWindSpeed")[0];
        final Band owiWindDirBand = findBands(product, "owiWindDirection")[0];

        if (owiLonBand != null) {
            final double[] lonValues = new double[owiLonBand.getRasterWidth() * owiLonBand.getRasterHeight()];
            owiLonBand.readPixels(0, 0, owiLonBand.getRasterWidth(), owiLonBand.getRasterHeight(), lonValues, ProgressMonitor.NULL);

            final double[] latValues = new double[owiLatBand.getRasterWidth() * owiLatBand.getRasterHeight()];
            owiLatBand.readPixels(0, 0, owiLatBand.getRasterWidth(), owiLatBand.getRasterHeight(), latValues, ProgressMonitor.NULL);

            final double[] incAngleValues = new double[owiIncAngleBand.getRasterWidth() * owiIncAngleBand.getRasterHeight()];
            owiIncAngleBand.readPixels(0, 0, owiIncAngleBand.getRasterWidth(), owiIncAngleBand.getRasterHeight(), incAngleValues, ProgressMonitor.NULL);

            final double[] windSpeedValues = new double[owiWindSpeedBand.getRasterWidth() * owiWindSpeedBand.getRasterHeight()];
            owiWindSpeedBand.readPixels(0, 0, owiWindSpeedBand.getRasterWidth(), owiWindSpeedBand.getRasterHeight(), windSpeedValues, ProgressMonitor.NULL);

            final double[] windDirValues = new double[owiWindDirBand.getRasterWidth() * owiWindDirBand.getRasterHeight()];
            owiWindDirBand.readPixels(0, 0, owiWindDirBand.getRasterWidth(), owiWindDirBand.getRasterHeight(), windDirValues, ProgressMonitor.NULL);

            addWindSpeedArrows(latValues, lonValues, incAngleValues, windSpeedValues, windDirValues,
                    owiLonBand.getRasterWidth(), owiLonBand.getRasterHeight(), productRenderablesInfo.theRenderableListHash.get("owi"));

            createColorSurfaceWithGradient(geoPos1, geoPos2, latValues, lonValues, windSpeedValues,
                    owiWindSpeedBand.getRasterWidth(), owiWindSpeedBand.getRasterHeight(), 0, 10,
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

        if (owiData != null && owiLonBand == null) {
            //addWaveLengthArrows(owiData[0], owiData[1], owiData[3], owiData[4], productRenderablesInfo.theRenderableListHash.get("owi"));
            createWVColorSurfaceWithGradient(product, owiData[0], owiData[1], owiData[2], productRenderablesInfo.theRenderableListHash.get("owi"), "owi");
        }
    }

    private void addWindSpeedArrows(double[] latValues,
                                    double[] lonValues,
                                    double[] incAngleValues,
                                    double[] windSpeedValues,
                                    double[] windDirValues,
                                    int width,
                                    int height,
                                    List<Renderable> renderableList) {
        double pixelWidth = Math.abs(lonValues[0] - lonValues[lonValues.length - 1]) / width;
        double pixelHeight = Math.abs(latValues[0] - latValues[latValues.length - 1]) / height;

        //SystemUtils.LOG.info("pixelWidth " + pixelWidth + " pixelHeight " + pixelHeight);

        //System.out.println("pixelWidth " + pixelWidth + " pixelHeight " + pixelHeight);

        // take the smaller dimension
        double arrowLength_deg = pixelWidth;
        if (pixelHeight < pixelWidth) {
            arrowLength_deg = pixelHeight;
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
                int globalInd = row * width + col;
                float avgLat = 0;
                float avgLon = 0;
                double avgIncAngle = 0;
                double avgWindSpeed = 0;
                double avgWindDir = 0;
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
                        avgLat += latValues[i];
                        avgLon += lonValues[i];
                        avgIncAngle += incAngleValues[i];
                        avgWindSpeed += windSpeedValues[i];
                        avgWindDir += windDirValues[i];
                    }
                }

                avgLat = avgLat / ((finalCellRow - row) * (finalCellCol - col));
                avgLon = avgLon / ((finalCellRow - row) * (finalCellCol - col));
                avgIncAngle = avgIncAngle / ((finalCellRow - row) * (finalCellCol - col));
                avgWindSpeed = avgWindSpeed / ((finalCellRow - row) * (finalCellCol - col));
                avgWindDir = avgWindDir / ((finalCellRow - row) * (finalCellCol - col));


                //System.out.println("avgIncAngle " + avgIncAngle);
                //for (int i = 0; i < latValues.length; i=i+50) {
                //System.out.println(lonValues[i] + "::==::" + latValues[i] + "::==::" + incAngleValues[i] + "::==::" + windSpeedValues[i] + "::==::" + windDirValues[i] + "::==::");
                final Position startPos = new Position(Angle.fromDegreesLatitude(avgLat), Angle.fromDegreesLongitude(avgLon), 10.0);
                final Position endPos = new Position(LatLon.greatCircleEndPosition(startPos, Angle.fromDegrees(avgWindDir), Angle.fromDegrees(arrowLength_deg)), 10.0);

                //System.out.println("startPos " + startPos + " endPos " + endPos);

                final List<Position> positions = new ArrayList<>();
                positions.add(startPos);
                positions.add(endPos);

                final DirectedPath directedPath = getDirectedPath(positions, dpAttrs);

                //double arrowHeadLength = computeSegmentLength(directedPath, dc, startPos, endPos) / 4;
                directedPath.setArrowLength(arrowHeadLength);
                int currCellRow = row / theOWIArrowCellSize;
                int currCellCol = col / theOWIArrowCellSize;
                //SystemUtils.LOG.info(":: currCell: " + currCellRow + " " + currCellCol);
                arrowGrid[0][currCellRow][currCellCol] = new ArrowInfo(directedPath, avgIncAngle, avgWindSpeed, avgWindDir, arrowLength_deg);


                //if (currCellRow > 0 && currCellCol > 0) {
                for (int cellSizeResolution = 1; cellSizeResolution < theOWIArrowNumLevels; cellSizeResolution++) {
                    // treating the original cell size as 1
                    int currBigCellSize = (int) FastMath.pow(2, cellSizeResolution);
                    if ((currCellRow % currBigCellSize == currBigCellSize - 1) && (currCellCol % currBigCellSize == currBigCellSize - 1)) {
                        int bigCellRow = (currCellRow / currBigCellSize);
                        int bigCellCol = (currCellCol / currBigCellSize);

                        int smallCellStartRow = bigCellRow * 2;
                        int smallCellStartCol = bigCellCol * 2;

                        double cumAvgIncAngle = 0;
                        double cumAvgWindSpeed = 0;
                        double cumAvgWindDir = 0;
                        Position cumStartPos = new Position(Angle.fromDegreesLatitude(0.0), Angle.fromDegreesLongitude(0.0), 10.0);
                        Position cumEndPos = new Position(Angle.fromDegreesLatitude(0.0), Angle.fromDegreesLongitude(0.0), 10.0);
                        double cumStartPosLat_deg = 0;
                        double cumStartPosLon_deg = 0;
                        double bigCellArrowLength_deg = currBigCellSize * arrowLength_deg;
                        for (int currSmallCellRow = smallCellStartRow; currSmallCellRow < smallCellStartRow + 2; currSmallCellRow++) {
                            for (int currSmallCellCol = smallCellStartCol; currSmallCellCol < smallCellStartCol + 2; currSmallCellCol++) {
                                ArrowInfo currSmallArrow = arrowGrid[cellSizeResolution - 1][currSmallCellRow][currSmallCellCol];
                                // all small cell's arrow length's will be the same
                                //bigCellArrowLength_deg = 2*currSmallArrow.theArrowLength;
                                cumAvgIncAngle += currSmallArrow.theAvgIncAngle;
                                cumAvgWindSpeed += currSmallArrow.theAvgWindSpeed;
                                cumAvgWindDir += currSmallArrow.theAvgWindDir;
                                boolean firstPosNext = true;
                                for (Position pos : currSmallArrow.theDirectedPath.getPositions()) {
                                    if (firstPosNext) {
                                        cumStartPos = cumStartPos.add(pos);
                                        cumStartPosLat_deg += pos.getLatitude().getDegrees();
                                        cumStartPosLon_deg += pos.getLongitude().getDegrees();
                                        firstPosNext = false;
                                    } else {
                                        cumEndPos = cumEndPos.add(pos);
                                    }
                                }
                            }
                        }
                        cumAvgIncAngle = cumAvgIncAngle / 4;
                        cumAvgWindSpeed = cumAvgWindSpeed / 4;
                        cumAvgWindDir = cumAvgWindDir / 4;
                        cumStartPosLat_deg = cumStartPosLat_deg / 4;
                        cumStartPosLon_deg = cumStartPosLon_deg / 4;

                        arrowGrid[cellSizeResolution][bigCellRow][bigCellCol] = null;


                        Position bigCellStartPos = new Position(Angle.fromDegreesLatitude(cumStartPosLat_deg), Angle.fromDegreesLongitude(cumStartPosLon_deg), 10.0);
                        Position bigCellEndPos = new Position(LatLon.greatCircleEndPosition(bigCellStartPos, Angle.fromDegrees(cumAvgWindDir), Angle.fromDegrees(bigCellArrowLength_deg)), 10.0);

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
        for (int cellRow = 0; cellRow < numCellRows; cellRow++) {
            for (int cellCol = 0; cellCol < numCellCols; cellCol++) {

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
                                        info += "Wind Direction: " + currArrow.theAvgWindDir + "<br/>";
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
