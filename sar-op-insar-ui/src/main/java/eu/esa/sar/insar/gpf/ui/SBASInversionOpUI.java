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
 * User interface for SBASInversionOp.
 */
public class SBASInversionOpUI extends BaseOperatorUI {

    private final JTextField referenceEpochDate = new JTextField("");
    private final JTextField coherenceMin = new JTextField("");
    private final JTextField coherenceLooks = new JTextField("");
    private final JTextField regWeight = new JTextField("");
    private final JTextField condThreshold = new JTextField("");
    private final JCheckBox outputResiduals = new JCheckBox("Output Per-Pair Residuals");
    private final JCheckBox outputClosurePhase = new JCheckBox("Output Closure-Phase RMS");
    private final JCheckBox outputVelocity = new JCheckBox("Output Velocity");

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
        final Object refDate = paramMap.get("referenceEpochDate");
        referenceEpochDate.setText(refDate == null ? "" : refDate.toString());
        setDoubleField(coherenceMin, "coherenceMin", 0.3);
        setIntField(coherenceLooks, "coherenceLooks", 100);
        setDoubleField(regWeight, "regWeight", 1.0e-3);
        setDoubleField(condThreshold, "condThreshold", 1.0e6);
        setBoolCheck(outputResiduals, "outputResiduals", false);
        setBoolCheck(outputClosurePhase, "outputClosurePhase", true);
        setBoolCheck(outputVelocity, "outputVelocity", true);
    }

    @Override
    public UIValidation validateParameters() {
        try {
            final double cm = Double.parseDouble(coherenceMin.getText().trim());
            if (cm < 0.0 || cm > 1.0) {
                return new UIValidation(UIValidation.State.ERROR, "coherenceMin must be in [0, 1]");
            }
            final int cl = Integer.parseInt(coherenceLooks.getText().trim());
            if (cl < 1) {
                return new UIValidation(UIValidation.State.ERROR, "coherenceLooks must be >= 1");
            }
            Double.parseDouble(regWeight.getText().trim());
            Double.parseDouble(condThreshold.getText().trim());
        } catch (NumberFormatException e) {
            return new UIValidation(UIValidation.State.ERROR, "Could not parse numeric parameter: " + e.getMessage());
        }
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {
        paramMap.put("referenceEpochDate", referenceEpochDate.getText().trim());
        paramMap.put("coherenceMin", Double.parseDouble(coherenceMin.getText().trim()));
        paramMap.put("coherenceLooks", Integer.parseInt(coherenceLooks.getText().trim()));
        paramMap.put("regWeight", Double.parseDouble(regWeight.getText().trim()));
        paramMap.put("condThreshold", Double.parseDouble(condThreshold.getText().trim()));
        paramMap.put("outputResiduals", outputResiduals.isSelected());
        paramMap.put("outputClosurePhase", outputClosurePhase.isSelected());
        paramMap.put("outputVelocity", outputVelocity.isSelected());
    }

    private JComponent createPanel() {
        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        addSectionHeader(contentPane, gbc, "Network");
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Reference Epoch (ddMMMyyyy):", referenceEpochDate);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Coherence Threshold:", coherenceMin);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Coherence Looks:", coherenceLooks);

        gbc.gridy++;
        addSectionHeader(contentPane, gbc, "Regularisation");
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Tikhonov Weight:", regWeight);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Condition Threshold:", condThreshold);

        gbc.gridy++;
        addSectionHeader(contentPane, gbc, "Outputs");
        gbc.gridy++;
        gbc.gridx = 1;
        contentPane.add(outputVelocity, gbc);
        gbc.gridy++;
        contentPane.add(outputClosurePhase, gbc);
        gbc.gridy++;
        contentPane.add(outputResiduals, gbc);
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

    private void setBoolCheck(final JCheckBox box, final String key, final boolean defaultVal) {
        final Object v = paramMap.get(key);
        box.setSelected(v == null ? defaultVal : (Boolean) v);
    }
}
