/*
 * Copyright (C) 2026 by SkyWatch Space Applications Inc.
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
package eu.esa.sar.sar.gpf.ui.geometric;

import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.OperatorSpiRegistry;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.graphbuilder.rcp.actions.OperatorAction;
import org.openide.awt.DynamicMenuContent;
import org.openide.modules.OnStart;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.event.ActionEvent;
import java.util.Map;

/**
 * Release gate for the GSLC (geocode-first InSAR) feature: the SNAP Desktop surfaces — the menu
 * action and the Graph Builder operator list — appear only when the system property
 * {@code ENABLE_GSLC} is set (any value except {@code false}; add {@code -J-DENABLE_GSLC=true} to
 * {@code snap.conf}/desktop options). Without it the feature is invisible in the Desktop.
 * <p>
 * Deliberately Desktop-only: {@code gpt} is a separate process that never runs the {@link OnStart}
 * hook, so command-line graphs, the processing chain (including CreateStack's internal GSLC
 * rebuild on existing GSLC stacks) and the test suite are unaffected by the gate.
 */
public class GSLCFeatureGate {

    public static final String ENABLE_GSLC_PROPERTY = "ENABLE_GSLC";
    private static final String GSLC_OPERATOR_ALIAS = "GSLC-Terrain-Correction";

    public static boolean isEnabled() {
        final String v = System.getProperty(ENABLE_GSLC_PROPERTY);
        return v != null && !"false".equalsIgnoreCase(v.trim());
    }

    /**
     * {@code layer.xml} {@code instanceCreate} factory for the GSLC menu action: the ordinary
     * {@link OperatorAction} when enabled, an action that contributes no menu item at all when not.
     */
    public static Action createAction(final Map<String, Object> properties) {
        if (isEnabled()) {
            return OperatorAction.create(properties);
        }
        return new HiddenAction();
    }

    /** Contributes zero menu presenters — the NetBeans idiom for hiding a layer-registered item. */
    private static final class HiddenAction extends AbstractAction implements DynamicMenuContent {
        @Override
        public JComponent[] getMenuPresenters() {
            return new JComponent[0];
        }

        @Override
        public JComponent[] synchMenuPresenters(final JComponent[] items) {
            return new JComponent[0];
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            // unreachable: the action is never presented
        }
    }

    /**
     * Desktop startup: with the gate closed, deregister the operator SPI in THIS process so the
     * Graph Builder (which lists raw registry aliases with no internal-filter) cannot offer it.
     */
    @OnStart
    public static final class Gate implements Runnable {
        @Override
        public void run() {
            if (isEnabled()) {
                SystemUtils.LOG.info("GSLC feature enabled (" + ENABLE_GSLC_PROPERTY + " is set).");
                return;
            }
            try {
                final OperatorSpiRegistry registry = GPF.getDefaultInstance().getOperatorSpiRegistry();
                final OperatorSpi spi = registry.getOperatorSpi(GSLC_OPERATOR_ALIAS);
                if (spi != null) {
                    registry.removeOperatorSpi(spi);
                    SystemUtils.LOG.info("GSLC feature hidden: menu entry and Graph Builder operator "
                            + "disabled. Set -D" + ENABLE_GSLC_PROPERTY + "=true to enable "
                            + "(gpt graphs are not affected by this gate).");
                }
            } catch (Throwable t) {
                SystemUtils.LOG.warning("GSLC feature gate could not deregister the operator: "
                        + t.getMessage());
            }
        }
    }
}
