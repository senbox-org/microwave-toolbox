/*
 * Copyright (C) 2025 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.fex.gpf.ui.changedetection;

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
 * User interface for RPCAOp
 */
public class RPCAOpUI extends BaseOperatorUI {

    private final JList bandList = new JList();
    private final JTextField maskThreshold = new JTextField("");
    private final JTextField lambda = new JTextField("");
    private final JCheckBox includeSourceBandsCheckBox = new JCheckBox("Include source bands");
    private Boolean includeSourceBands = true;

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();
        initParameters();

        includeSourceBandsCheckBox.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                includeSourceBands = (e.getStateChange() == ItemEvent.SELECTED);
            }
        });

        return new JScrollPane(panel);
    }

    @Override
    public void initParameters() {

        OperatorUIUtils.initParamList(bandList, getBandNames());

        maskThreshold.setText(String.valueOf(paramMap.get("maskThreshold")));
        lambda.setText(String.valueOf(paramMap.get("lambda")));

        includeSourceBands = (Boolean) paramMap.get("includeSourceBands");
        if (includeSourceBands != null) {
            includeSourceBandsCheckBox.setSelected(includeSourceBands);
        }

        if (sourceProducts != null && sourceProducts.length > 0) {
            final int sourceImageWidth = sourceProducts[0].getSceneRasterWidth();
            final int sourceImageHeight = sourceProducts[0].getSceneRasterHeight();
            final double lambdaValue = 1.0 / Math.sqrt(Math.max(sourceImageWidth, sourceImageHeight));
            lambda.setText(String.valueOf(lambdaValue));
        }
    }

    @Override
    public UIValidation validateParameters() {
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        OperatorUIUtils.updateParamList(bandList, paramMap, OperatorUIUtils.SOURCE_BAND_NAMES);

        final String maskThresholdStr = maskThreshold.getText();
        if (maskThresholdStr != null && !maskThresholdStr.isEmpty())
            paramMap.put("maskThreshold", Float.parseFloat(maskThresholdStr));

        final String lambdaStr = lambda.getText();
        if (lambdaStr != null && !lambdaStr.isEmpty())
            paramMap.put("lambda", Float.parseFloat(lambdaStr));

        paramMap.put("includeSourceBands", includeSourceBands);
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        contentPane.add(new JTextArea("Source Bands:\n(select two bands)"), gbc);

        gbc.fill = GridBagConstraints.BOTH;
        gbc.gridx = 1;
        contentPane.add(new JScrollPane(bandList), gbc);

        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Mask threshold in (0,1):", maskThreshold);

        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Lambda in (0,1):", lambda);

        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "",
                new JTextArea("Note: Lower lambda value results in more detected targets."));

        gbc.gridy++;
        contentPane.add(includeSourceBandsCheckBox, gbc);

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }
}