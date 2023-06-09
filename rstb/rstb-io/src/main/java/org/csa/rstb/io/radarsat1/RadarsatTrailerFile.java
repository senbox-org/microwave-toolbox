/*
 * Copyright (C) 2015 by Array Systems Computing Inc. http://www.array.ca
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
package org.csa.rstb.io.radarsat1;

import eu.esa.sar.io.binary.BinaryDBReader;
import eu.esa.sar.io.binary.BinaryFileReader;
import eu.esa.sar.io.binary.BinaryRecord;
import eu.esa.sar.io.ceos.CEOSLeaderFile;
import org.jdom2.Document;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;


class RadarsatTrailerFile extends CEOSLeaderFile {

    private final static String mission = "radarsat";
    private final static String trailer_recordDefinitionFile = "trailer_file.xml";
    private final static String resourcePath = "org/csa/rstb/io/ceos_db/";

    private final static Document trailerXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, trailer_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document sceneXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, scene_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document mapProjXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, mapproj_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document dataQualityXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, dataQuality_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document histogramXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, histogram_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document detailProcXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, detailedProcessing_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document platformXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, platformPosition_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document attitudeXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, attitude_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document radiometricXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, radiometric_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document radiometricCompXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, radiometric_comp_recordDefinitionFile, RadarsatTrailerFile.class);
    private final static Document facilityXML = BinaryDBReader.loadDefinitionFile(resourcePath, mission, facility_recordDefinitionFile, RadarsatTrailerFile.class);

    public RadarsatTrailerFile(final ImageInputStream stream) throws IOException {
        final BinaryFileReader reader = new BinaryFileReader(stream);

        leaderFDR = new BinaryRecord(reader, -1, trailerXML, trailer_recordDefinitionFile);
        reader.seek(leaderFDR.getRecordEndPosition());
        int num = leaderFDR.getAttributeInt("Number of data set summary records");
        for (int i = 0; i < num; ++i) {
            sceneHeaderRecord = new BinaryRecord(reader, -1, sceneXML, scene_recordDefinitionFile);
            reader.seek(sceneHeaderRecord.getRecordEndPosition());
        }
        num = leaderFDR.getAttributeInt("Number of map projection data records");
        for (int i = 0; i < num; ++i) {
            mapProjRecord = new BinaryRecord(reader, -1, mapProjXML, mapproj_recordDefinitionFile);
            reader.seek(mapProjRecord.getRecordEndPosition());
        }
        num = leaderFDR.getAttributeInt("Number of data quality summary records");
        for (int i = 0; i < num; ++i) {
            dataQualityRecord = new BinaryRecord(reader, -1, dataQualityXML, dataQuality_recordDefinitionFile);
            reader.seek(dataQualityRecord.getRecordEndPosition());
        }
        num = leaderFDR.getAttributeInt("Number of data histograms records");
        for (int i = 0; i < num; ++i) {
            histogramRecord = new BinaryRecord(reader, -1, histogramXML, histogram_recordDefinitionFile);
            reader.seek(histogramRecord.getRecordEndPosition());
        }
        num = leaderFDR.getAttributeInt("Number of det. processing records");
        for (int i = 0; i < num; ++i) {
            detailedProcessingRecord = new BinaryRecord(reader, -1, detailProcXML, detailedProcessing_recordDefinitionFile);
            reader.seek(detailedProcessingRecord.getRecordEndPosition());
        }
        num = leaderFDR.getAttributeInt("Number of platform pos. data records");
        for (int i = 0; i < num; ++i) {
            platformPositionRecord = new BinaryRecord(reader, -1, platformXML, platformPosition_recordDefinitionFile);
            reader.seek(platformPositionRecord.getRecordEndPosition());
        }
        num = leaderFDR.getAttributeInt("Number of attitude data records");
        for (int i = 0; i < num; ++i) {
            attitudeRecord = new BinaryRecord(reader, -1, attitudeXML, attitude_recordDefinitionFile);
            reader.seek(attitudeRecord.getRecordEndPosition());
        }
        num = leaderFDR.getAttributeInt("Number of radiometric data records");
        for (int i = 0; i < num; ++i) {
            radiometricRecord = new BinaryRecord(reader, -1, radiometricXML, radiometric_recordDefinitionFile);
            reader.seek(radiometricRecord.getRecordEndPosition());
        }
        num = leaderFDR.getAttributeInt("Number of rad. compensation records");
        for (int i = 0; i < num; ++i) {
            radiometricCompRecord = new BinaryRecord(reader, -1, radiometricCompXML, radiometric_comp_recordDefinitionFile);
            reader.seek(radiometricCompRecord.getRecordEndPosition());
        }
        num = leaderFDR.getAttributeInt("Number of facility data records");
        for (int i = 0; i < num; ++i) {
            facilityRecord = new BinaryRecord(reader, -1, facilityXML, facility_recordDefinitionFile);
            reader.seek(facilityRecord.getRecordEndPosition());
        }

        reader.close();
    }
}
