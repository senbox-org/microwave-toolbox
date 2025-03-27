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
package eu.esa.sar.insar.gpf.ui.coregistration;

import eu.esa.sar.insar.gpf.InSARStackOverview;
import eu.esa.sar.insar.gpf.coregistration.CreateStackOp;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.datamodel.VirtualBand;
import org.esa.snap.core.dataop.resamp.ResamplingFactory;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.gpf.InputProductValidator;
import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.esa.snap.graphbuilder.gpf.ui.OperatorUIUtils;
import org.esa.snap.graphbuilder.gpf.ui.UIValidation;
import org.esa.snap.graphbuilder.rcp.utils.DialogUtils;
import org.esa.snap.rcp.util.Dialogs;
import org.esa.snap.ui.AppContext;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * User interface for CreateStackOp
 */
public class CreateStackOpUI extends BaseOperatorUI {

    private final JList refBandList = new JList();
    private final JList secBandList = new JList();

    private final List<Integer> defaultReferenceBandIndices = new ArrayList<>(2);
    private final List<Integer> defaultSecondaryBandIndices = new ArrayList<>(2);

    private final JLabel referenceProductLabel = new JLabel();
    private final JComboBox resamplingType = new JComboBox(ResamplingFactory.resamplingNames);

    private final JComboBox initialOffsetMethod = new JComboBox(new String[]{CreateStackOp.INITIAL_OFFSET_ORBIT,
            CreateStackOp.INITIAL_OFFSET_GEOLOCATION});

    private final JComboBox extent = new JComboBox(new String[]{CreateStackOp.MASTER_EXTENT,
            CreateStackOp.MIN_EXTENT,
            CreateStackOp.MAX_EXTENT});
    private final JButton optimalReferenceButton = new JButton("Find Optimal Reference");
    private Product referenceProduct = null;

    @Override
    public JComponent CreateOpTab(String operatorName, Map<String, Object> parameterMap, AppContext appContext) {

        initializeOperatorUI(operatorName, parameterMap);
        final JComponent panel = createPanel();
        resamplingType.addItem("NONE");

        initParameters();

        return new JScrollPane(panel);
    }

    @Override
    public void initParameters() {

        if (referenceProduct == null && sourceProducts != null && sourceProducts.length > 0) {
            referenceProduct = sourceProducts[0];
        }

        //enableOptimalReferenceButton();
        //updateReferenceSecondarySelections();

        if (referenceProduct != null) {
            referenceProductLabel.setText(referenceProduct.getName());
        }
        resamplingType.setSelectedItem(paramMap.get("resamplingType"));

        initialOffsetMethod.setSelectedItem(paramMap.get("initialOffsetMethod"));

        extent.setSelectedItem(paramMap.get("extent"));
    }

    private static List<Integer> getSelectedIndices(final String[] allBandNames,
                                                    final String[] selBandNames,
                                                    final List<Integer> defaultIndices) {
        final List<Integer> bandIndices = new ArrayList<>(2);
        if (selBandNames != null && selBandNames.length > 0) {
            int i = 0;
            for (String bandName : allBandNames) {
                for (String selName : selBandNames) {
                    if (bandName.equals(selName)) {
                        bandIndices.add(i);
                    }
                }
                ++i;
            }
        }

        if (bandIndices.isEmpty())
            return defaultIndices;
        return bandIndices;
    }

    @Override
    public UIValidation validateParameters() {

        if (resamplingType.getSelectedItem().equals("NONE") && sourceProducts != null) {
            try {
                CreateStackOp.checkPixelSpacing(sourceProducts);
            } catch (OperatorException e) {
                return new UIValidation(UIValidation.State.WARNING, "Resampling type cannot be NONE" +
                        " pixel spacings are different for reference and secondary");
            } catch (Exception e) {
                //ignore
            }
        }
        return new UIValidation(UIValidation.State.OK, "");
    }

    @Override
    public void updateParameters() {

        //OperatorUIUtils.updateParamList(refBandList, paramMap, "referenceBandNames");
        //OperatorUIUtils.updateParamList(secBandList, paramMap, "secondaryBandNames");

        paramMap.put("resamplingType", resamplingType.getSelectedItem());

        paramMap.put("initialOffsetMethod", initialOffsetMethod.getSelectedItem());

        paramMap.put("extent", extent.getSelectedItem());
    }

    private JComponent createPanel() {

        final JPanel contentPane = new JPanel();
        contentPane.setLayout(new GridBagLayout());
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

   /*     contentPane.add(new JLabel("Reference Bands:"), gbc);

        gbc.fill = GridBagConstraints.BOTH;
        gbc.gridx = 1;
        contentPane.add(new JScrollPane(refBandList), gbc);

        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0;
        gbc.gridy++;

        contentPane.add(new JLabel("Secondary Bands:"), gbc);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.gridx = 1;
        contentPane.add(new JScrollPane(secBandList), gbc);
        gbc.gridx = 0;
        gbc.gridy++;        */


        DialogUtils.addComponent(contentPane, gbc, "Reference:", referenceProductLabel);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Resampling Type:", resamplingType);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Initial Offset Method:", initialOffsetMethod);
        gbc.gridy++;
        DialogUtils.addComponent(contentPane, gbc, "Output Extents:", extent);
        gbc.gridy++;

        optimalReferenceButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (sourceProducts != null) {
                    try {
                        referenceProduct = InSARStackOverview.findOptimalMasterProduct(sourceProducts);
                        referenceProductLabel.setText(referenceProduct.getName());
                    } catch (Exception ex) {
                        Dialogs.showError("Error finding optimal reference: " + ex.getMessage());
                    }
                }
                updateReferenceSecondarySelections();
            }
        });
        gbc.fill = GridBagConstraints.VERTICAL;
        contentPane.add(optimalReferenceButton, gbc);
        gbc.gridy++;

        DialogUtils.fillPanel(contentPane, gbc);

        return contentPane;
    }

    private void enableOptimalReferenceButton() {
        if (sourceProducts == null) return;

        for (Product prod : sourceProducts) {
            final InputProductValidator validator = new InputProductValidator(prod);
            if (!validator.isComplex()) {
                optimalReferenceButton.setEnabled(false);
                return;
            }
        }
    }

    private void updateReferenceSecondarySelections() {
        final String bandNames[] = getBandNames();
        OperatorUIUtils.initParamList(refBandList, bandNames);
        OperatorUIUtils.initParamList(secBandList, bandNames);

        OperatorUIUtils.setSelectedListIndices(refBandList, getSelectedIndices(bandNames,
                                                                               new String[]{}, //String[])paramMap.get("referenceBandNames"),
                defaultReferenceBandIndices));
        OperatorUIUtils.setSelectedListIndices(secBandList, getSelectedIndices(bandNames,
                                                                               new String[]{}, //(String[])paramMap.get("secondaryBandNames"),
                                                                               defaultSecondaryBandIndices));
    }

    @Override
    protected String[] getBandNames() {
        if (sourceProducts == null) {
            return new String[]{};
        }

        defaultReferenceBandIndices.clear();
        defaultSecondaryBandIndices.clear();

        if (sourceProducts.length > 1) {
            for (int i = 1; i < sourceProducts.length; ++i) {
                if (sourceProducts[i].getDisplayName().equals(referenceProduct.getDisplayName())) {
                    referenceProduct = null;
                    return new String[]{};
                }
            }
        }

        final List<String> bandNames = new ArrayList<>(5);
        boolean referenceBandsSelected = false;
        for (Product prod : sourceProducts) {
            if (sourceProducts.length > 1) {

                final Band[] bands = prod.getBands();
                for (int i = 0; i < bands.length; ++i) {
                    final Band band = bands[i];
                    bandNames.add(band.getName() + "::" + prod.getName());
                    final int index = bandNames.size() - 1;

                    if (!(band instanceof VirtualBand)) {

                        if (prod == referenceProduct && !referenceBandsSelected) {
                            defaultReferenceBandIndices.add(index);
                            if (band.getUnit() != null && band.getUnit().equals(Unit.REAL)) {
                                if (i + 1 < bands.length) {
                                    final Band qBand = bands[i + 1];
                                    if (qBand.getUnit() != null && qBand.getUnit().equals(Unit.IMAGINARY)) {
                                        defaultReferenceBandIndices.add(index + 1);
                                        bandNames.add(qBand.getName() + "::" + prod.getName());
                                        ++i;
                                    }
                                }
                            }
                            referenceBandsSelected = true;
                        } else {
                            defaultSecondaryBandIndices.add(index);
                        }
                    }
                }
            } else {
                bandNames.addAll(Arrays.asList(prod.getBandNames()));
            }
        }

        return bandNames.toArray(new String[0]);
    }

}
