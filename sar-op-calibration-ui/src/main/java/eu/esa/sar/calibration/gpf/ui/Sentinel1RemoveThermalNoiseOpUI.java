/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
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
package eu.esa.sar.calibration.gpf.ui;

import eu.esa.sar.commons.Sentinel1Utils;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.esa.snap.graphbuilder.gpf.ui.OperatorUIUtils;
import org.esa.snap.graphbuilder.gpf.ui.UIValidation;
import org.esa.snap.graphbuilder.rcp.utils.DialogUtils;
import org.esa.snap.ui.AppContext;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.Map;

/**
 * User interface for S-1 thermal noise correction operator
 */
public class Sentinel1RemoveThermalNoiseOpUI extends BaseOperatorUI {

    private final JList<String> polList = new JList<>();
    private final JCheckBox removeThermalNoiseCheckBox = new JCheckBox("Remove Thermal Noise");
    private final JCheckBox outputNoiseCheckBox = new JCheckBox("Output Noise");
    private final JCheckBox reIntroduceThermalNoiseCheckBox = new JCheckBox("Re-Introduce Thermal Noise");
    private final JCheckBox clipNegativeValuesCheckBox = new JCheckBox("Clip Negative Values");
    private final JTextField clipFloorValue = new JTextField("");
    private final JCheckBox applyNoiseRecalibrationCheckBox = new JCheckBox("Apply Noise Retro-Calibration");
    private boolean removeThermalNoise = false;
    private boolean outputNoise = false;
    private boolean reIntroduceThermalNoise = false;
    private boolean clipNegativeValues = true;

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();
        initParameters();

        // remove and re-introduce are mutually exclusive; both unchecked is the noise-only mode (Output Noise),
        // so unchecking one does not check the other. Output Noise is available in every mode.
        removeThermalNoiseCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                removeThermalNoise = (e.getStateChange() == ItemEvent.SELECTED);
                if (removeThermalNoise) {
                    reIntroduceThermalNoise = false;
                    reIntroduceThermalNoiseCheckBox.setSelected(false);
                }
                updateClipControls();
            }
        });

        reIntroduceThermalNoiseCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                reIntroduceThermalNoise = (e.getStateChange() == ItemEvent.SELECTED);
                if (reIntroduceThermalNoise) {
                    removeThermalNoise = false;
                    removeThermalNoiseCheckBox.setSelected(false);
                }
                updateClipControls();
            }
        });

        outputNoiseCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                outputNoise = (e.getStateChange() == ItemEvent.SELECTED);
            }
        });

        clipNegativeValuesCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                clipNegativeValues = (e.getStateChange() == ItemEvent.SELECTED);
                updateClipControls();
            }
        });
        updateClipControls();

        return new JScrollPane(panel);
    }

    /** Clipping only applies when noise is removed; the floor only when clipping. */
    private void updateClipControls() {
        clipNegativeValuesCheckBox.setEnabled(removeThermalNoise);
        clipFloorValue.setEnabled(removeThermalNoise && clipNegativeValues);
    }

    @Override
    public void initParameters() {

        if (sourceProducts != null && sourceProducts.length > 0) {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProducts[0]);
            final String[] polarisations = Sentinel1Utils.getProductPolarizations(absRoot);
            polList.setListData(polarisations);

            OperatorUIUtils.initParamList(polList, polarisations);
        }

        Boolean paramVal;
        paramVal = (Boolean) paramMap.get("removeThermalNoise");
        if (paramVal != null) {
            removeThermalNoise = paramVal;
            removeThermalNoiseCheckBox.setSelected(removeThermalNoise);
        }

        paramVal = (Boolean) paramMap.get("reIntroduceThermalNoise");
        if (paramVal != null) {
            reIntroduceThermalNoise = paramVal;
            reIntroduceThermalNoiseCheckBox.setSelected(reIntroduceThermalNoise);
        }

        paramVal = (Boolean) paramMap.get("outputNoise");
        if (paramVal != null) {
            outputNoise = paramVal;
            outputNoiseCheckBox.setSelected(outputNoise);
        }

        paramVal = (Boolean) paramMap.get("clipNegativeValues");
        if (paramVal != null) {
            clipNegativeValues = paramVal;
            clipNegativeValuesCheckBox.setSelected(clipNegativeValues);
        }
        final Object floor = paramMap.get("clipFloorValue");
        clipFloorValue.setText(floor != null ? String.valueOf(floor) : "1.0E-5");
        paramVal = (Boolean) paramMap.get("applyNoiseRecalibration");
        applyNoiseRecalibrationCheckBox.setSelected(paramVal != null && paramVal);
        updateClipControls();
    }

    @Override
    public UIValidation validateParameters() {
        if (removeThermalNoise && reIntroduceThermalNoise) {
            return new UIValidation(UIValidation.State.ERROR,
                    "Remove and Re-Introduce Thermal Noise cannot both be selected");
        }
        final String floorText = clipFloorValue.getText().trim();
        if (!floorText.isEmpty()) {
            final double floor;
            try {
                floor = Double.parseDouble(floorText);
            } catch (NumberFormatException e) {
                return new UIValidation(UIValidation.State.ERROR, "Clip floor value must be a number");
            }
            // same rule as the operator: NaN, Infinity and negative values are refused
            if (!Double.isFinite(floor) || floor < 0) {
                return new UIValidation(UIValidation.State.ERROR,
                        "Clip floor value must be a finite, non-negative number (0 = ESA MPC-0392 Eq-2)");
            }
        }
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        OperatorUIUtils.updateParamList(polList, paramMap, "selectedPolarisations");

        paramMap.put("removeThermalNoise", removeThermalNoise);
        paramMap.put("reIntroduceThermalNoise", reIntroduceThermalNoise);
        paramMap.put("outputNoise", outputNoise);
        paramMap.put("clipNegativeValues", clipNegativeValues);
        if (!clipFloorValue.getText().trim().isEmpty()) {
            try {
                paramMap.put("clipFloorValue", Double.parseDouble(clipFloorValue.getText().trim()));
            } catch (NumberFormatException e) {
                // reported by validateParameters; the operator keeps its default
            }
        }
        paramMap.put("applyNoiseRecalibration", applyNoiseRecalibrationCheckBox.isSelected());
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        DialogUtils.addComponent(contentPane, gbc, "Polarisations:", polList);

        gbc.gridx = 0;
        gbc.gridy++;
        contentPane.add(removeThermalNoiseCheckBox, gbc);
        gbc.gridx = 1;
        contentPane.add(outputNoiseCheckBox, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        contentPane.add(reIntroduceThermalNoiseCheckBox, gbc);

        gbc.gridx = 0;
        gbc.gridy++;
        contentPane.add(clipNegativeValuesCheckBox, gbc);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Clip floor value:", clipFloorValue);
        gbc.gridx = 0;
        gbc.gridy++;
        contentPane.add(applyNoiseRecalibrationCheckBox, gbc);

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }
}
