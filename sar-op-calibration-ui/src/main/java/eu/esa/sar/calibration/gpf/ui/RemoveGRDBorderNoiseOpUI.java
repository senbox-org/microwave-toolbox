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

import eu.esa.sar.calibration.gpf.RemoveGRDBorderNoiseOp;
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
import java.util.Map;

/**
 * User interface for S-1 RemoveGRDBorderNoise operator
 */
public class RemoveGRDBorderNoiseOpUI extends BaseOperatorUI {

    private final JList<String> polList = new JList<>();
    private final JTextField borderLimit = new JTextField("");
    private final JTextField trimThreshold = new JTextField("");
    private final JComboBox<String> borderMethod = new JComboBox<>(new String[]{
            RemoveGRDBorderNoiseOp.METHOD_AUTO, RemoveGRDBorderNoiseOp.METHOD_ESA,
            RemoveGRDBorderNoiseOp.METHOD_BIDIRECTIONAL});
    private final JCheckBox processRecentProducts = new JCheckBox("Also process products from IPF 2.90 and later");

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();
        initParameters();
        borderMethod.addActionListener(e -> updateEnablement());
        updateEnablement();
        return new JScrollPane(panel);
    }

    @Override
    public void initParameters() {

        if (sourceProducts != null && sourceProducts.length > 0) {
            final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(sourceProducts[0]);

            OperatorUIUtils.initParamList(polList, Sentinel1Utils.getProductPolarizations(absRoot),
                    (String[])paramMap.get("selectedPolarisations"));
        }

        if(paramMap.get("borderLimit") != null) {
            borderLimit.setText(String.valueOf(paramMap.get("borderLimit")));
        }
        if(paramMap.get("trimThreshold") != null) {
            trimThreshold.setText(String.valueOf(paramMap.get("trimThreshold")));
        }
        final Object method = paramMap.get("borderMethod");
        borderMethod.setSelectedItem(method != null ? method : RemoveGRDBorderNoiseOp.METHOD_AUTO);
        final Boolean recent = (Boolean) paramMap.get("processRecentProducts");
        processRecentProducts.setSelected(recent != null && recent);
        updateEnablement();
    }

    /**
     * The threshold is only used by the ESA method (also chosen by Auto); the IPF 2.90 option only changes Auto, since an
     * explicit Bidirectional choice always runs and ESA is refused from IPF 2.90 on.
     */
    private void updateEnablement() {
        final Object method = borderMethod.getSelectedItem();
        trimThreshold.setEnabled(!RemoveGRDBorderNoiseOp.METHOD_BIDIRECTIONAL.equals(method));
        processRecentProducts.setEnabled(RemoveGRDBorderNoiseOp.METHOD_AUTO.equals(method));
    }

    @Override
    public UIValidation validateParameters() {
        final String limit = borderLimit.getText().trim();
        if (!limit.isEmpty()) {
            final Integer value = parseInteger(limit);
            if (value == null || value < RemoveGRDBorderNoiseOp.MIN_BORDER_LIMIT) {
                return new UIValidation(UIValidation.State.ERROR, "Border margin limit must be a whole number of at least " +
                        RemoveGRDBorderNoiseOp.MIN_BORDER_LIMIT + " pixels");
            }
        }
        final String threshold = trimThreshold.getText().trim();
        if (!threshold.isEmpty()) {
            final Double value = parseDouble(threshold);
            if (value == null || value.isNaN() || value.isInfinite() || value < 0) {
                return new UIValidation(UIValidation.State.ERROR, "Threshold must be a non-negative number");
            }
        }
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        OperatorUIUtils.updateParamList(polList, paramMap, "selectedPolarisations");
        // invalid text is reported by validateParameters; the previous value is kept
        final Integer limit = parseInteger(borderLimit.getText().trim());
        if (limit != null) {
            paramMap.put("borderLimit", limit);
        }
        final Double threshold = parseDouble(trimThreshold.getText().trim());
        if (threshold != null) {
            paramMap.put("trimThreshold", threshold);
        }
        paramMap.put("borderMethod", borderMethod.getSelectedItem());
        paramMap.put("processRecentProducts", processRecentProducts.isSelected());
    }

    private static Integer parseInteger(final String text) {
        try {
            return text.isEmpty() ? null : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(final String text) {
        try {
            return text.isEmpty() ? null : Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        DialogUtils.addComponent(contentPane, gbc, "Polarisations:", polList);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Border margin limit [pixels]:", borderLimit);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Threshold:", trimThreshold);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Border detection method:", borderMethod);
        gbc.gridy++;
        gbc.gridx = 0;
        contentPane.add(processRecentProducts, gbc);

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }
}
