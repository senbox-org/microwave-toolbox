/*
 * Copyright (C) 20123 by SkyWatch Space Applications Inc. http://www.skywatch.com
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
package eu.esa.sar.insar.rcp.toolviews.insar_statistics;

import eu.esa.sar.insar.gpf.InSARStackOverview;
import eu.esa.sar.insar.rcp.toolviews.InSARStatisticsTopComponent;
import org.esa.snap.core.datamodel.Band;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.Tile;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class StatETADMeasure implements InSARStatistic {

    private ETADTableModel tableModel;
    private JTable table;
    private final InSARStatisticsTopComponent parent;
    private CachedETADMeasures[] cachedEtadMeasures;
    private Product cachedProduct;

    private final static DecimalFormat df = new DecimalFormat("0.00");
    private final static String sep = ", ";

    public static final String ETAD_ELEM = "ETAD";

    public StatETADMeasure(final InSARStatisticsTopComponent parent) {
        this.parent = parent;
    }

    public String getName() {
        return "Differential Phase";
    }

    public Component createPanel() {
        tableModel = new ETADTableModel();
        table = new JTable(tableModel);
        return new JScrollPane(table);
    }

    public void update(final Product product) {

        if (InSARStatistic.isValidProduct(product)) {
            tableModel.clear();

            CachedETADMeasures[] etadMeasures = getDiffPhaseStats(product);
            if(etadMeasures != null) {
                for (CachedETADMeasures baseline : etadMeasures) {
                    tableModel.addRow(baseline);
                }
            }
            table.repaint();
        } else {
            tableModel.clear();
            table.repaint();
        }
    }

    public CachedETADMeasures[] getDiffPhaseStats(final Product product) {

        if (cachedEtadMeasures == null || cachedProduct != product) {
            try {
                final List<CachedETADMeasures> diffPhaseStats = new ArrayList<>(50);
                DiffPhaseStats stats = new DiffPhaseStats();
                final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
                if (absRoot != null) {
                    final MetadataElement etadElem = absRoot.getElement(ETAD_ELEM);
                    if (etadElem != null) {
                        stats.min = etadElem.getAttributeDouble("min");
                        stats.max = etadElem.getAttributeDouble("max");
                        stats.mean = etadElem.getAttributeDouble("mean");
                        stats.std = etadElem.getAttributeDouble("std");
                    }
                }
                diffPhaseStats.add(new CachedETADMeasures(product.getName(), stats));

                cachedProduct = product;
                cachedEtadMeasures = diffPhaseStats.toArray(new CachedETADMeasures[diffPhaseStats.size()]);
            } catch (Exception e) {
                SystemUtils.LOG.severe("Error getting differential phase statistics: "+ e.getMessage());
            }
        }
        return cachedEtadMeasures;
    }

    public void copyToClipboard() {
        SystemUtils.copyToClipboard(getText());
    }

    public void saveToFile() {
        saveToFile(getText());
    }

    public String getHelpId() {
        return "StatBaselines";
    }

    private String getText() {
        final StringBuilder str = new StringBuilder(300);

        for (int i = 0; i < tableModel.getColumnCount(); ++i) {
            str.append(tableModel.getColumnName(i));
            str.append(sep);
        }
        str.append('\n');

        for (CachedETADMeasures baseline : tableModel.data) {
            str.append(baseline.toString());
            str.append('\n');
        }

        return str.toString();
    }

    private static class ETADTableModel extends AbstractTableModel {

        private final static String[] COLUMN_NAMES = {"Product", "min [rad]", "max [rad]", "mean [rad]", "std [rad]"};

        private final static Class[] COLUMN_CLASSES = {String.class, String.class, String.class, String.class, String.class};

        private final List<CachedETADMeasures> data = new ArrayList<>(50);

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMN_NAMES.length;
        }

        @Override
        public String getColumnName(int columnIndex) {
            return COLUMN_NAMES[columnIndex];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return COLUMN_CLASSES[columnIndex];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int row, int column) {
            CachedETADMeasures etad = data.get(row);
            switch (column) {
                case 0:
                    return etad.productName;
                case 1:
                    return etad.min;
                case 2:
                    return etad.max;
                case 3:
                    return etad.mean;
                case 4:
                    return etad.std;
            }
            return null;
        }

        public void clear() {
            data.clear();
        }

        public void addRow(CachedETADMeasures etad) {
            data.add(etad);
        }
    }

    public static class DiffPhaseStats {
        double min;
        double max;
        double mean;
        double std;
    }

    public static class CachedETADMeasures {
        private final String productName;
        private final String min;
        private final String max;
        private final String mean;
        private final String std;

        public CachedETADMeasures(String productName, DiffPhaseStats diffPhaseStats) {
            this.min = df.format(diffPhaseStats.min);
            this.max = df.format(diffPhaseStats.max);
            this.mean = df.format(diffPhaseStats.mean);
            this.std = df.format(diffPhaseStats.std);
            this.productName = productName;
        }

        public String toString() {
            return productName +
                    sep +
                    min +
                    sep +
                    max +
                    sep +
                    mean +
                    sep +
                    std;
        }
    }
}


