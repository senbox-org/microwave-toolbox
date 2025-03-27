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
package org.csa.rstb.classification.rcp.dialogs;

import org.csa.rstb.classification.gpf.ui.SupervisedWishartClassificationOpUI;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.util.Settings;
import org.esa.snap.graphbuilder.rcp.utils.DialogUtils;
import org.esa.snap.rcp.SnapApp;
import org.esa.snap.rcp.util.Dialogs;
import org.esa.snap.ui.ModalDialog;

import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.List;

import static org.esa.snap.rcp.SnapApp.SelectionSourceHint.AUTO;

/**
 * Created by IntelliJ IDEA.
 * User: lveci
 * Date: Jun 5, 2008
 * To change this template use File | Settings | File Templates.
 */
public class ProductGeometrySelectorDialog extends ModalDialog {

    private final JComboBox<String> productList;
    private final JComboBox<String> roiProductList;
    private final JList<String> geometries = new JList<>();
    private final JTextField savePath = new JTextField();
    private final JButton browseButton = new JButton("...");
    private boolean ok = false;

    public ProductGeometrySelectorDialog(final String title) {
        super(SnapApp.getDefault().getMainFrame(), title, ModalDialog.ID_OK_CANCEL, null);

        final String[] productNames = SnapApp.getDefault().getProductManager().getProductDisplayNames();

        productList = new JComboBox<>(productNames);

        roiProductList = new JComboBox<>(productNames);
        roiProductList.addItemListener(event -> updateGeometryList());

        geometries.setFixedCellWidth(200);
        geometries.setMinimumSize(new Dimension(50, 4));
        geometries.setVisibleRowCount(6);

        // set default selection to selected product
        final Product selectedProduct = SnapApp.getDefault().getSelectedProduct(AUTO);
        if (selectedProduct != null) {
            productList.setSelectedItem(selectedProduct.getDisplayName());
            roiProductList.setSelectedItem(selectedProduct.getDisplayName());
            updateGeometryList();
        }

        // set default save path to training dataset folder
        final String defaultSavePath = getDefaultSaveLocation().getAbsolutePath();
        savePath.setText(defaultSavePath);
        savePath.setColumns(40);

        browseButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                final File file = Dialogs.requestFileForSave("Classification Training Dataset", false, null, ".txt",
                                                             defaultSavePath, null,
                                                             SupervisedWishartClassificationOpUI.LAST_TRAINING_DIR);
                savePath.setText(file.getAbsolutePath());
            }
        });

        getJDialog().setMinimumSize(new Dimension(200, 100));

        setContent(createPanel());
    }

    private void updateGeometryList() {
        final String name = (String) roiProductList.getSelectedItem();
        final Product product = SnapApp.getDefault().getProductManager().getProductByDisplayName(name);
        final String[] geometryNames = product.getMaskGroup().getNodeNames();
        geometries.removeAll();
        geometries.setListData(geometryNames);
    }

    private JPanel createPanel() {
        final JPanel content = new JPanel(new BorderLayout(2, 2));
        final GridBagLayout gridBagLayout = new GridBagLayout();

        final JPanel srcPanel = new JPanel(gridBagLayout);
        srcPanel.setBorder(BorderFactory.createTitledBorder("Source Product"));
        final GridBagConstraints gbc = DialogUtils.createGridBagConstraints();

        DialogUtils.addComponent(srcPanel, gbc, "Quad Pol Product:", productList);
        gbc.gridy++;
        DialogUtils.addComponent(srcPanel, gbc, "Product with ROIs:", roiProductList);

        gbc.gridy++;
        DialogUtils.addComponent(srcPanel, gbc, "Training ROIs:", new JScrollPane(geometries));

        final JPanel dstPanel = new JPanel(gridBagLayout);
        dstPanel.setBorder(BorderFactory.createTitledBorder("Saved Training Dataset"));

        gbc.gridx = 0;
        gbc.gridy = 0;
        dstPanel.add(new JLabel("File name:"), gbc);
        gbc.gridx = 1;
        dstPanel.add(savePath, gbc);
        gbc.gridx = 2;
        dstPanel.add(browseButton, gbc);

        content.add(srcPanel, BorderLayout.CENTER);
        content.add(dstPanel, BorderLayout.SOUTH);

        return content;
    }

    public Product getProduct() {
        return SnapApp.getDefault().getProductManager().getProductByDisplayName(
                (String) productList.getSelectedItem());
    }

    public Product getRoiProduct() {
        return SnapApp.getDefault().getProductManager().getProductByDisplayName(
                (String) roiProductList.getSelectedItem());
    }

    public String[] getSelectedGeometries() {
        final List<String> list = geometries.getSelectedValuesList();
        return list.toArray(new String[0]);
    }

    public File getSaveFile() {
        return new File(savePath.getText());
    }

    private static File getDefaultSaveLocation() {
        File folder;
        try {
            folder = new File(Settings.instance().getAuxDataFolder(), "classifiers"+ File.separator +"Wishart");
        } catch (Exception e) {
            folder = FileSystemView.getFileSystemView().getRoots()[0];
        }
        return new File(folder, "training_cluster_centers.txt");
    }

    private boolean validate() {
        final String[] geometryNames = getSelectedGeometries();

        if (geometryNames == null || geometryNames.length < 1) {
            Dialogs.showError("Please select the product geometries to use");
            return false;
        }

        final File file = getSaveFile();
        if (file.exists()) {
            return Dialogs.requestDecision("File exists", "File " + file.getAbsolutePath() +
                                                          "\nalready exists. Would you like to overwrite it?", false, null) == Dialogs.Answer.YES;
        }
        if (!file.getParentFile().exists()) {
            if(!file.getParentFile().mkdirs()) {
                SystemUtils.LOG.severe("Unable to create folders in "+file.getParentFile());
            }
        }
        return true;
    }

    protected void onOK() {
        if (validate()) {
            ok = true;
            hide();
        }
    }

    public boolean IsOK() {
        return ok;
    }

}
