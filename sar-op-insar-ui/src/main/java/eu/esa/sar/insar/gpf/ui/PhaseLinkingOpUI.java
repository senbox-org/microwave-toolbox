/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc. https://www.skywatch.com
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

import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.esa.snap.graphbuilder.gpf.ui.UIValidation;
import org.esa.snap.graphbuilder.rcp.utils.DialogUtils;
import org.esa.snap.ui.AppContext;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.Map;

/**
 * User interface for PhaseLinkingOp.
 */
public class PhaseLinkingOpUI extends BaseOperatorUI {

    private final JTextField windowAzimuth = new JTextField("");
    private final JTextField windowRange = new JTextField("");
    private final JComboBox<String> shpTest = new JComboBox<>(new String[]{"KS", "AD", "TLog"});
    private final JTextField shpAlpha = new JTextField("");
    private final JTextField shpMin = new JTextField("");
    private final JComboBox<String> estimator = new JComboBox<>(new String[]{"EVD", "EMI"});
    private final JTextField referenceEpochDate = new JTextField("");
    private final JTextField tempCohMin = new JTextField("");
    private final JCheckBox outputTempCoherence = new JCheckBox("Output Temporal Coherence");
    private final JCheckBox outputShpCount = new JCheckBox("Output SHP Count");

    @Override
    public JComponent CreateOpTab(final String operatorName,
                                  final Map<String, Object> parameterMap,
                                  final AppContext appContext) {
        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();
        initParameters();
        return new JScrollPane(panel);
    }

    @Override
    public void initParameters() {
        setIntField(windowAzimuth, "windowAzimuth", 21);
        setIntField(windowRange, "windowRange", 7);
        setStringCombo(shpTest, "shpTest", "KS");
        setDoubleField(shpAlpha, "shpAlpha", 0.05);
        setIntField(shpMin, "shpMin", 20);
        setStringCombo(estimator, "estimator", "EVD");
        final Object refDate = paramMap.get("referenceEpochDate");
        referenceEpochDate.setText(refDate == null ? "" : refDate.toString());
        setDoubleField(tempCohMin, "tempCohMin", 0.6);
        setBoolCheck(outputTempCoherence, "outputTempCoherence", true);
        setBoolCheck(outputShpCount, "outputShpCount", false);
    }

    @Override
    public UIValidation validateParameters() {
        try {
            final int waz = Integer.parseInt(windowAzimuth.getText().trim());
            final int wrg = Integer.parseInt(windowRange.getText().trim());
            if (waz < 3 || wrg < 3) {
                return new UIValidation(UIValidation.State.ERROR, "SHP window sizes must be >= 3");
            }
            final double alpha = Double.parseDouble(shpAlpha.getText().trim());
            if (alpha <= 0.0 || alpha >= 1.0) {
                return new UIValidation(UIValidation.State.ERROR, "SHP alpha must be in (0, 1)");
            }
            final int min = Integer.parseInt(shpMin.getText().trim());
            if (min < 1) {
                return new UIValidation(UIValidation.State.ERROR, "shpMin must be >= 1");
            }
            final double tcm = Double.parseDouble(tempCohMin.getText().trim());
            if (tcm < 0.0 || tcm > 1.0) {
                return new UIValidation(UIValidation.State.ERROR, "tempCohMin must be in [0, 1]");
            }
        } catch (NumberFormatException e) {
            return new UIValidation(UIValidation.State.ERROR, "Could not parse numeric parameter: " + e.getMessage());
        }
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {
        paramMap.put("windowAzimuth", Integer.parseInt(windowAzimuth.getText().trim()));
        paramMap.put("windowRange", Integer.parseInt(windowRange.getText().trim()));
        paramMap.put("shpTest", shpTest.getSelectedItem());
        paramMap.put("shpAlpha", Double.parseDouble(shpAlpha.getText().trim()));
        paramMap.put("shpMin", Integer.parseInt(shpMin.getText().trim()));
        paramMap.put("estimator", estimator.getSelectedItem());
        paramMap.put("referenceEpochDate", referenceEpochDate.getText().trim());
        paramMap.put("tempCohMin", Double.parseDouble(tempCohMin.getText().trim()));
        paramMap.put("outputTempCoherence", outputTempCoherence.isSelected());
        paramMap.put("outputShpCount", outputShpCount.isSelected());
    }

    private JComponent createPanel() {
        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        addSectionHeader(contentPane, gbc, "SHP selection");
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Window Azimuth:", windowAzimuth);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Window Range:", windowRange);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "SHP Test:", shpTest);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "SHP alpha:", shpAlpha);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Minimum SHPs:", shpMin);

        gbc.gridy++;
        addSectionHeader(contentPane, gbc, "Phase estimation");
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Estimator:", estimator);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Reference Epoch (ddMMMyyyy):", referenceEpochDate);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Temp. Coherence Threshold:", tempCohMin);

        gbc.gridy++;
        addSectionHeader(contentPane, gbc, "Diagnostics");
        gbc.gridy++;
        gbc.gridx = 1;
        contentPane.add(outputTempCoherence, gbc);
        gbc.gridy++;
        contentPane.add(outputShpCount, gbc);
        gbc.gridx = 0;

        DialogUtils.fillPanel(contentPane, gbc);
        return contentPane;
    }

    private static void addSectionHeader(final JPanel pane, final GridBagConstraints gbc, final String text) {
        final JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        final int savedWidth = gbc.gridwidth;
        final int savedX = gbc.gridx;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        pane.add(label, gbc);
        gbc.gridwidth = savedWidth;
        gbc.gridx = savedX;
    }

    private void setIntField(final JTextField field, final String key, final int defaultVal) {
        final Object v = paramMap.get(key);
        field.setText(v == null ? String.valueOf(defaultVal) : v.toString());
    }

    private void setDoubleField(final JTextField field, final String key, final double defaultVal) {
        final Object v = paramMap.get(key);
        field.setText(v == null ? String.valueOf(defaultVal) : v.toString());
    }

    private void setStringCombo(final JComboBox<String> combo, final String key, final String defaultVal) {
        final Object v = paramMap.get(key);
        combo.setSelectedItem(v == null ? defaultVal : v.toString());
    }

    private void setBoolCheck(final JCheckBox box, final String key, final boolean defaultVal) {
        final Object v = paramMap.get(key);
        box.setSelected(v == null ? defaultVal : (Boolean) v);
    }
}
