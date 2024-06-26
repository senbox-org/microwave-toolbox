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
package eu.esa.sar.insar.gpf.ui;

import eu.esa.sar.insar.gpf.CoherenceOp;
import org.esa.snap.core.dataop.dem.ElevationModelDescriptor;
import org.esa.snap.core.dataop.dem.ElevationModelRegistry;
import org.esa.snap.dem.dataio.DEMFactory;
import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.esa.snap.graphbuilder.gpf.ui.OperatorUIUtils;
import org.esa.snap.graphbuilder.gpf.ui.UIValidation;
import org.esa.snap.graphbuilder.rcp.utils.DialogUtils;
import org.esa.snap.rcp.util.Dialogs;
import org.esa.snap.ui.AppContext;

import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.util.Map;

/**
 * User interface for CreateInterferogramOp
 */
public class InterferogramOpUI extends BaseOperatorUI {

    private final JCheckBox subtractFlatEarthPhaseCheckBox = new JCheckBox("Subtract Flat-Earth Phase");
    private final JCheckBox subtractTopographicPhaseCheckBox = new JCheckBox("Subtract Topographic Phase");
    private final JCheckBox includeCoherenceCheckBox = new JCheckBox("Output Coherence");
    private final JCheckBox squarePixelCheckBox = new JCheckBox("Square Pixel");
    private final JCheckBox independentWindowSizeCheckBox = new JCheckBox("Independent Window Sizes");

    private final JCheckBox outputFlatEarthPhaseCheckBox = new JCheckBox("Output Flat Earth Phase");
    private final JCheckBox outputTopoPhaseCheckBox = new JCheckBox("Output Topographic Phase");
    private final JCheckBox outputElevationCheckBox = new JCheckBox("Output Elevation");
    private final JCheckBox outputLatLonCheckBox = new JCheckBox("Output Orthorectified Lat/Lon");

    private final JTextField cohWinAz = new JTextField("");
    private final JTextField cohWinRg = new JTextField("");

    private final JComboBox<Integer> srpPolynomialDegreeStr = new JComboBox(new Integer[]{1, 2, 3, 4, 5, 6, 7, 8});
    private final JComboBox<Integer> srpNumberPointsStr = new JComboBox(new Integer[]{301, 401, 501, 601, 701, 801, 901, 1001});
    private final JComboBox<Integer> orbitDegreeStr = new JComboBox(new Integer[]{1, 2, 3, 4, 5});

    private static final JLabel cohWinAzLabel = new JLabel("Coherence Azimuth Window Size");
    private static final JLabel cohWinRgLabel = new JLabel("Coherence Range Window Size");
    private static final JLabel srpPolynomialDegreeStrLabel = new JLabel("Degree of \"Flat Earth\" polynomial");
    private static final JLabel srpNumberPointsStrLabel = new JLabel("Number of \"Flat Earth\" estimation points");
    private static final JLabel orbitDegreeStrLabel = new JLabel("Orbit interpolation degree");

    private Boolean subtractFlatEarthPhase = false;
    private Boolean includeCoherence = true;
    private Boolean squarePixel = true;
    private final CoherenceOp.DerivedParams param = new CoherenceOp.DerivedParams();
    private Boolean outputFlatEarthPhase = false;
    private Boolean outputTopoPhase = false;
    private Boolean outputElevation = false;
    private Boolean outputLatLon = false;

    private Boolean subtractTopographicPhase = false;
    private static final String[] demValueSet = DEMFactory.getDEMNameList();
    //    private final JTextField orbitDegree = new JTextField("");
    private final JComboBox<String> demName = new JComboBox<>(demValueSet);
    private static final String externalDEMStr = "External DEM";
    private final JTextField externalDEMFile = new JTextField("");
    private final JTextField externalDEMNoDataValue = new JTextField("");
    private final JButton externalDEMBrowseButton = new JButton("...");
    private final JLabel externalDEMFileLabel = new JLabel("External DEM:");
    private final JLabel externalDEMNoDataValueLabel = new JLabel("DEM No Data Value:");
    private final JCheckBox externalDEMApplyEGMCheckBox = new JCheckBox("Apply Earth Gravitational Model");
    private final DialogUtils.TextAreaKeyListener textAreaKeyListener = new DialogUtils.TextAreaKeyListener();
    private final JComboBox<String> tileExtensionPercent = new JComboBox<>(new String[]{"20", "40", "60", "80", "100", "150", "200"});
    private Double extNoDataValue = 0.0;
    private Boolean externalDEMApplyEGM = true;

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        initializeOperatorUI(operatorName, parameterMap);

        final JComponent panel = new JScrollPane(createPanel());
        initParameters();

        subtractFlatEarthPhaseCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {

                subtractFlatEarthPhase = (e.getStateChange() == ItemEvent.SELECTED);
                srpPolynomialDegreeStr.setEnabled(subtractFlatEarthPhase);
                srpNumberPointsStr.setEnabled(subtractFlatEarthPhase);
                orbitDegreeStr.setEnabled(subtractFlatEarthPhase);
                outputFlatEarthPhaseCheckBox.setEnabled(subtractFlatEarthPhase);
            }
        });

        outputFlatEarthPhaseCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                outputFlatEarthPhase = (e.getStateChange() == ItemEvent.SELECTED);
            }
        });

        includeCoherenceCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {

                includeCoherence = (e.getStateChange() == ItemEvent.SELECTED);
                squarePixelCheckBox.setEnabled(includeCoherence);
                independentWindowSizeCheckBox.setEnabled(includeCoherence);
                cohWinAz.setEnabled(includeCoherence);
                cohWinRg.setEnabled(includeCoherence);
            }
        });

        squarePixelCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                squarePixel = (e.getStateChange() == ItemEvent.SELECTED);
                independentWindowSizeCheckBox.setSelected(!squarePixel);
                if (squarePixel) {
                    cohWinAz.setText("2");
                    cohWinAz.setEditable(false);
                }
                setCohWinAz();
                setCohWinRg();
            }
        });

        independentWindowSizeCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                squarePixel = (e.getStateChange() != ItemEvent.SELECTED);
                squarePixelCheckBox.setSelected(squarePixel);
                if (!squarePixel) {
                    cohWinAz.setEditable(true);
                }
                setCohWinAz();
                setCohWinRg();
            }
        });

        subtractTopographicPhaseCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {

                subtractTopographicPhase = (e.getStateChange() == ItemEvent.SELECTED);
                demName.setEnabled(subtractTopographicPhase);
                tileExtensionPercent.setEnabled(subtractTopographicPhase);
                outputElevationCheckBox.setEnabled(subtractTopographicPhase);
                outputLatLonCheckBox.setEnabled(subtractTopographicPhase);
                outputTopoPhaseCheckBox.setEnabled(subtractTopographicPhase);
            }
        });

        demName.addItem(externalDEMStr);

        demName.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent event) {
                final String item = ((String) demName.getSelectedItem()).replace(DEMFactory.AUTODEM, "");
                if (item.equals(externalDEMStr)) {
                    enableExternalDEM(true);
                } else {
                    externalDEMFile.setText("");
                    enableExternalDEM(false);
                }
            }
        });
        externalDEMFile.setColumns(30);
        enableExternalDEM(((String) demName.getSelectedItem()).startsWith(externalDEMStr));

        externalDEMBrowseButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final File file = Dialogs.requestFileForOpen("External DEM File", false, null, DEMFactory.LAST_EXTERNAL_DEM_DIR_KEY);
                if (file != null) {
                    externalDEMFile.setText(file.getAbsolutePath());
                    extNoDataValue = OperatorUIUtils.getNoDataValue(file);
                }
                externalDEMNoDataValue.setText(String.valueOf(extNoDataValue));
            }
        });

        externalDEMApplyEGMCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                externalDEMApplyEGM = (e.getStateChange() == ItemEvent.SELECTED);
            }
        });

        externalDEMNoDataValue.addKeyListener(textAreaKeyListener);

        outputTopoPhaseCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                outputTopoPhase = (e.getStateChange() == ItemEvent.SELECTED);
            }
        });

        outputElevationCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                outputElevation = (e.getStateChange() == ItemEvent.SELECTED);
            }
        });

        outputLatLonCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                outputLatLon = (e.getStateChange() == ItemEvent.SELECTED);
            }
        });

        return panel;
    }

    @Override
    public void initParameters() {

        Boolean paramVal;
        paramVal = (Boolean) paramMap.get("subtractFlatEarthPhase");
        if (paramVal != null) {
            subtractFlatEarthPhase = paramVal;
            subtractFlatEarthPhaseCheckBox.setSelected(subtractFlatEarthPhase);
        }

        srpPolynomialDegreeStr.setSelectedItem(paramMap.get("srpPolynomialDegree"));
        srpNumberPointsStr.setSelectedItem(paramMap.get("srpNumberPoints"));
        orbitDegreeStr.setSelectedItem(paramMap.get("orbitDegree"));

        paramVal = (Boolean) paramMap.get("outputFlatEarthPhase");
        if (paramVal != null) {
            outputFlatEarthPhase = paramVal;
        }
        outputFlatEarthPhaseCheckBox.setSelected(outputFlatEarthPhase);
        outputFlatEarthPhaseCheckBox.setEnabled(subtractFlatEarthPhase);

        if (subtractFlatEarthPhase) {
            srpPolynomialDegreeStr.setEnabled(true);
            srpNumberPointsStr.setEnabled(true);
            orbitDegreeStr.setEnabled(true);
        }
        paramVal = (Boolean) paramMap.get("subtractTopographicPhase");
        if (paramVal != null) {
            subtractTopographicPhase = paramVal;
            subtractTopographicPhaseCheckBox.setSelected(subtractTopographicPhase);
        }

        paramVal = (Boolean) paramMap.get("outputTopoPhase");
        if (paramVal != null) {
            outputTopoPhase = paramVal;
        }
        outputTopoPhaseCheckBox.setSelected(outputTopoPhase);
        outputTopoPhaseCheckBox.setEnabled(subtractTopographicPhase);

        paramVal = (Boolean) paramMap.get("outputElevation");
        if (paramVal != null) {
            outputElevation = paramVal;
        }
        outputElevationCheckBox.setSelected(outputElevation);
        outputElevationCheckBox.setEnabled(subtractTopographicPhase);

        paramVal = (Boolean) paramMap.get("outputLatLon");
        if (paramVal != null) {
            outputLatLon = paramVal;
        }
        outputLatLonCheckBox.setSelected(outputLatLon);
        outputLatLonCheckBox.setEnabled(subtractTopographicPhase);


//        orbitDegree.setText(String.valueOf(paramMap.get("orbitDegree")));
        final String demNameParam = (String) paramMap.get("demName");
        if (demNameParam != null) {
            ElevationModelDescriptor descriptor = ElevationModelRegistry.getInstance().getDescriptor(demNameParam);
            if(descriptor != null) {
                demName.setSelectedItem(DEMFactory.getDEMDisplayName(descriptor));
            } else {
                demName.setSelectedItem(demNameParam);
            }
        }
        demName.setEnabled(subtractTopographicPhase);

        final File extFile = (File)paramMap.get("externalDEMFile");
        if(extFile != null) {
            externalDEMFile.setText(extFile.getAbsolutePath());
            extNoDataValue =  (Double)paramMap.get("externalDEMNoDataValue");
            if(extNoDataValue != null && !textAreaKeyListener.isChangedByUser()) {
                externalDEMNoDataValue.setText(String.valueOf(extNoDataValue));
            }

            paramVal = (Boolean) paramMap.get("externalDEMApplyEGM");
            if (paramVal != null) {
                externalDEMApplyEGM = paramVal;
                externalDEMApplyEGMCheckBox.setSelected(externalDEMApplyEGM);
            }
        }

        tileExtensionPercent.setSelectedItem(paramMap.get("tileExtensionPercent"));
        tileExtensionPercent.setEnabled(subtractTopographicPhase);

        paramVal = (Boolean) paramMap.get("includeCoherence");
        if (paramVal != null) {
            includeCoherence = paramVal;
            includeCoherenceCheckBox.setSelected(includeCoherence);
        }

        cohWinAz.setText(String.valueOf(paramMap.get("cohWinAz")));
        cohWinRg.setText(String.valueOf(paramMap.get("cohWinRg")));

        squarePixel = (Boolean) paramMap.get("squarePixel");
        if (squarePixel != null) {
            squarePixelCheckBox.setSelected(squarePixel);
            independentWindowSizeCheckBox.setSelected(!squarePixel);
            if (squarePixel) {
                cohWinAz.setText("2");
                cohWinAz.setEditable(false);
            } else {
                cohWinAz.setEditable(true);
            }
        }

        setCohWinAz();
        setCohWinRg();

        if (includeCoherence) {
            squarePixelCheckBox.setEnabled(true);
            independentWindowSizeCheckBox.setEnabled(true);
            cohWinAz.setEnabled(true);
            cohWinRg.setEnabled(true);
        }
    }

    @Override
    public UIValidation validateParameters() {

        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        paramMap.put("subtractFlatEarthPhase", subtractFlatEarthPhase);

        if (subtractFlatEarthPhase) {
            paramMap.put("srpPolynomialDegree", srpPolynomialDegreeStr.getSelectedItem());
            paramMap.put("srpNumberPoints", srpNumberPointsStr.getSelectedItem());
            paramMap.put("orbitDegree", orbitDegreeStr.getSelectedItem());
            paramMap.put("outputFlatEarthPhase", outputFlatEarthPhase);
        }

        paramMap.put("subtractTopographicPhase", subtractTopographicPhase);
        if (subtractTopographicPhase) {
//          paramMap.put("orbitDegree", Integer.parseInt(orbitDegree.getText()));
            final String properDEMName = (DEMFactory.getProperDEMName((String) demName.getSelectedItem()));
            paramMap.put("demName", (DEMFactory.getProperDEMName((String) demName.getSelectedItem())));
            if(properDEMName.equals(externalDEMStr)) {
                final String extFileStr = externalDEMFile.getText();
                paramMap.put("externalDEMFile", new File(extFileStr));
                paramMap.put("externalDEMNoDataValue", Double.parseDouble(externalDEMNoDataValue.getText()));
                paramMap.put("externalDEMApplyEGM", externalDEMApplyEGM);
            }
            paramMap.put("tileExtensionPercent", tileExtensionPercent.getSelectedItem());
            paramMap.put("outputTopoPhase", outputTopoPhase);
            paramMap.put("outputElevation", outputElevation);
            paramMap.put("outputLatLon", outputLatLon);
        }

        paramMap.put("includeCoherence", includeCoherence);
        if (includeCoherence) {
            final String cohWinRgStr = cohWinRg.getText();
            final String cohWinAzStr = cohWinAz.getText();
            if (cohWinRgStr != null && !cohWinRgStr.isEmpty())
                paramMap.put("cohWinRg", Integer.parseInt(cohWinRg.getText()));

            if (cohWinAzStr != null && !cohWinAzStr.isEmpty())
                paramMap.put("cohWinAz", Integer.parseInt(cohWinAz.getText()));

            paramMap.put("squarePixel", squarePixel);
        }
    }

    JComponent createPanel() {

        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        final JPanel flatEarthPanel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc2 = DialogUtils.createGridBagConstraints();
        flatEarthPanel.setBorder(BorderFactory.createTitledBorder("Flat Earth Phase"));

        contentPane.add(flatEarthPanel, gbc);

        flatEarthPanel.add(subtractFlatEarthPhaseCheckBox, gbc2);

        gbc2.gridy++;
        DialogUtils.addComponent(flatEarthPanel, gbc2, srpPolynomialDegreeStrLabel, srpPolynomialDegreeStr);
        srpPolynomialDegreeStr.setEnabled(false);

        gbc2.gridy++;
        DialogUtils.addComponent(flatEarthPanel, gbc2, srpNumberPointsStrLabel, srpNumberPointsStr);
        srpNumberPointsStr.setEnabled(false);

        gbc2.gridy++;
        DialogUtils.addComponent(flatEarthPanel, gbc2, orbitDegreeStrLabel, orbitDegreeStr);
        orbitDegreeStr.setEnabled(false);

        gbc2.gridy++;
        flatEarthPanel.add(outputFlatEarthPhaseCheckBox, gbc2);

        final JPanel topoPanel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc3 = DialogUtils.createGridBagConstraints();
        topoPanel.setBorder(BorderFactory.createTitledBorder("Topographic Phase"));

        gbc.gridy++;
        contentPane.add(topoPanel, gbc);

        topoPanel.add(subtractTopographicPhaseCheckBox, gbc3);

        //gbc.gridy++;
        //DialogUtils.addComponent(topoPanel, gbc3, "Orbit Interpolation Degree:", orbitDegree);
        gbc3.gridy++;
        DialogUtils.addComponent(topoPanel, gbc3, "Digital Elevation Model:", demName);
        gbc3.gridy++;
        DialogUtils.addInnerPanel(topoPanel, gbc3, externalDEMFileLabel, externalDEMFile, externalDEMBrowseButton);
        gbc3.gridy++;
        DialogUtils.addComponent(topoPanel, gbc3, externalDEMNoDataValueLabel, externalDEMNoDataValue);
        gbc3.gridy++;
        gbc3.gridx = 1;
        topoPanel.add(externalDEMApplyEGMCheckBox, gbc3);

        gbc3.gridx = 0;
        gbc3.gridy = gbc.gridy + 10;
        DialogUtils.addComponent(topoPanel, gbc3, "Tile Extension [%]", tileExtensionPercent);
        gbc3.gridy++;
        topoPanel.add(outputTopoPhaseCheckBox, gbc3);
        gbc3.gridy++;
        topoPanel.add(outputElevationCheckBox, gbc3);
        gbc3.gridy++;
        topoPanel.add(outputLatLonCheckBox, gbc3);

        demName.setEnabled(false);
        tileExtensionPercent.setEnabled(false);
        outputElevationCheckBox.setEnabled(false);
        outputLatLonCheckBox.setEnabled(false);

        final JPanel coherencePanel = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc4 = DialogUtils.createGridBagConstraints();
        coherencePanel.setBorder(BorderFactory.createTitledBorder("Coherence"));

        gbc.gridy++;
        contentPane.add(coherencePanel, gbc);

        coherencePanel.add(includeCoherenceCheckBox, gbc4);

        gbc4.gridx = 0;
        gbc4.gridy++;
        coherencePanel.add(squarePixelCheckBox, gbc4);
        squarePixelCheckBox.setEnabled(false);

        gbc4.gridx = 1;
        coherencePanel.add(independentWindowSizeCheckBox, gbc4);
        independentWindowSizeCheckBox.setEnabled(false);

        gbc4.gridy++;
        DialogUtils.addComponent(coherencePanel, gbc4, cohWinRgLabel, cohWinRg);
        cohWinRg.setEnabled(false);
        cohWinRg.setDocument(new CohWinRgDocument());

        gbc4.gridy++;
        DialogUtils.addComponent(coherencePanel, gbc4, cohWinAzLabel, cohWinAz);
        cohWinAz.setEnabled(false);
        cohWinAz.setEditable(false);

        DialogUtils.fillPanel(flatEarthPanel, gbc2);
        DialogUtils.fillPanel(topoPanel, gbc3);
        DialogUtils.fillPanel(coherencePanel, gbc4);
        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }


    private synchronized void setCohWinAz() {
        if (sourceProducts != null && sourceProducts.length > 0) {
            try {
                if (squarePixelCheckBox.isSelected()) {
                    param.cohWinRg = Integer.parseInt(cohWinRg.getText());
                    CoherenceOp.getDerivedParameters(sourceProducts[0], param);
                    cohWinAz.setText(String.valueOf(param.cohWinAz));
                }
            } catch (Exception e) {
            }
        }
    }

    private void setCohWinRg() {
        if (sourceProducts != null && sourceProducts.length > 0) {
            if (squarePixelCheckBox.isSelected()) {
                cohWinRg.setText(String.valueOf(param.cohWinRg));
            }
        }
    }

    @SuppressWarnings("serial")
    private class CohWinRgDocument extends PlainDocument {

        @Override
        public void replace(int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
            super.replace(offset, length, text, attrs);

            setCohWinAz();
        }
    }

    private void enableExternalDEM(boolean flag) {
        DialogUtils.enableComponents(externalDEMFileLabel, externalDEMFile, flag);
        DialogUtils.enableComponents(externalDEMNoDataValueLabel, externalDEMNoDataValue, flag);
        if(!flag) {
            externalDEMFile.setText("");
        }
        externalDEMBrowseButton.setVisible(flag);
        externalDEMApplyEGMCheckBox.setVisible(flag);
    }
}
