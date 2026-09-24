package eu.esa.sar.sentinel1.gpf.ui;

import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.graphbuilder.gpf.ui.BaseOperatorUI;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Sweeps every {@code *OpUI} in this module through the lifecycle a freshly opened operator dialog
 * uses, and requires each to survive it.
 *
 * <p><b>The condition being reproduced.</b> A dialog opened from scratch hands its OperatorUI an
 * EMPTY parameter map. {@link BaseOperatorUI#initializeOperatorUI} then calls
 * {@code propertySet.setDefaultValues()}, and ceres-binding NEVER writes a <b>primitive</b>
 * parameter whose default equals the type's zero value ({@code boolean false}, {@code int 0},
 * {@code double 0.0}) into a map-backed map: {@code Property.getValue()} returns the primitive zero
 * for an absent key, so {@code setValue(FALSE)} short-circuits on {@code equalObjects}. The key
 * simply never appears, and a UI that unboxes it straight out of {@code paramMap} throws.</p>
 *
 * <p><b>WHAT THIS TEST CATCHES.</b> Exactly one thing: a UI that <i>throws</i> out of
 * {@code CreateOpTab} / {@code initParameters} / {@code updateParameters} on the no-source-product,
 * defaults-only path. {@code TOPSARSplitOpUI} line ~135 ({@code (int) paramMap.get("firstBurstIndex")})
 * is a live example it protects: it NPEs the moment either burst-index default becomes 0.</p>
 *
 * <p><b>WHAT IT DOES NOT CATCH — read this before trusting a green run.</b></p>
 * <ul>
 *   <li><b>The flag-gated variant, which is the one that actually shipped twice.</b> The historical
 *       CrossCorrelationOpUI defect sat behind {@code if (isComplex)}, and {@code isComplex} is
 *       false precisely when there is no source product — so the throwing line was never reached
 *       and this sweep would have passed green on it. Defects of that shape need a targeted test;
 *       see {@code TestCrossCorrelationOpUI}.</li>
 *   <li><b>Anything that does not throw.</b> Controls silently left disabled, or an explicit
 *       {@code null} written back into the parameter map, are invisible here because the written
 *       map is never inspected. Both are live today: {@code DEMAssistedCoregistrationOpUI} and
 *       {@code BackGeocodingOpUI} read a primitive/false key (absent, so null) and put that null
 *       straight back into the graph.</li>
 *   <li><b>A UI that swallows its own construction error.</b> {@code TOPSARSplitOpUI} builds a
 *       world-map pane whose layer type is absent from the test classpath; the resulting NPE is
 *       caught inside {@code NestWorldMapPane}, so that UI is counted as swept while being only
 *       half-constructed. In sar-op-sentinel1-ui that swallow also boots SNAP Desktop inside the
 *       surefire JVM, which is why this sweep costs ~20 s there against ~2 s here.</li>
 *   <li>Only the {@code sourceProducts == null} branch runs, so roughly half of a typical
 *       {@code initParameters()} body is never executed.</li>
 * </ul>
 *
 * <p>Copy into another {@code *-ui} module by changing {@link #ANCHOR} and {@link #EXPECTED_UI_COUNT}.</p>
 */
public class TestOperatorUIDefaults {

    /** Any class in the module whose UIs should be swept. */
    private static final Class<?> ANCHOR = BackGeocodingOpUI.class;

    /** Update when a UI is added to or removed from this module - the count is asserted. */
    private static final int EXPECTED_UI_COUNT = 5;

    /**
     * UIs that cannot be built in a plain surefire JVM.
     *
     * <p>TOPSARSplitOpUI builds a WorldMapUI whose BlueMarble layer type is absent from this
     * module's test classpath. NestWorldMapPane catches the resulting NPE and routes it to
     * SnapApp.handleError, which boots the entire NetBeans platform inside the test JVM and ends
     * in a modal DialogDisplayer - observed hanging this module's build indefinitely. Sweeping it
     * was also vacuous, since the swallow meant a half-constructed UI still counted as "survived".
     * Re-include it by adding a blue-marble-worldmap test dependency and -Djava.awt.headless=true.</p>
     */
    private static final List<String> REQUIRES_APP_CONTEXT = List.of("TOPSARSplitOpUI");

    private static List<Class<?>> operatorUIClasses() throws Exception {
        final URL loc = ANCHOR.getProtectionDomain().getCodeSource().getLocation();
        final Path root = new File(loc.toURI()).toPath();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            final List<Path> files = walk.filter(p -> p.getFileName().toString().endsWith("OpUI.class"))
                    .collect(Collectors.toList());
            final List<Class<?>> classes = new ArrayList<>();
            for (Path p : files) {
                String name = root.relativize(p).toString()
                        .replace(File.separatorChar, '.').replace('/', '.');
                name = name.substring(0, name.length() - ".class".length());
                if (name.contains("$")) {
                    continue;
                }
                final Class<?> c = Class.forName(name, false, ANCHOR.getClassLoader());
                if (BaseOperatorUI.class.isAssignableFrom(c)
                        && !java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
                    classes.add(c);
                }
            }
            return classes;
        }
    }

    /**
     * Operator simple class name -> alias, built once. getOperatorSpis() forces the whole SPI
     * registry to load, so calling it per UI class made this test minutes instead of seconds.
     */
    private static Map<String, String> aliasByOperatorClass() {
        final Map<String, String> m = new HashMap<>();
        for (OperatorSpi spi : GPF.getDefaultInstance().getOperatorSpiRegistry().getOperatorSpis()) {
            final Class<?> opClass = spi.getOperatorClass();
            if (opClass != null) {
                m.putIfAbsent(opClass.getSimpleName(), spi.getOperatorAlias());
            }
        }
        return m;
    }

    private static Map<String, Class<?>> operatorClassByAlias() {
        final Map<String, Class<?>> m = new HashMap<>();
        for (OperatorSpi spi : GPF.getDefaultInstance().getOperatorSpiRegistry().getOperatorSpis()) {
            if (spi.getOperatorClass() != null) {
                m.putIfAbsent(spi.getOperatorAlias(), spi.getOperatorClass());
            }
        }
        return m;
    }

    /**
     * A reference-typed parameter may legitimately be null (String[] pairs, an unset ROI vector).
     * A PRIMITIVE one never can: null means the entry is silently lost.
     */
    private static boolean isPrimitiveParameter(final Class<?> opClass, final String name) {
        for (Class<?> c = opClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name).getType().isPrimitive();
            } catch (NoSuchFieldException ignored) {
                // declared further up the hierarchy
            }
        }
        return false;
    }

    /** CrossCorrelationOpUI -> the alias of the operator whose class is CrossCorrelationOp. */
    private static String operatorAliasFor(final Class<?> uiClass, final Map<String, String> aliases) {
        final String n = uiClass.getSimpleName();
        return aliases.get(n.substring(0, n.length() - "UI".length()));
    }

    /**
     * Every OperatorUI must build from an empty parameter map - the state a freshly opened operator
     * dialog is in - without throwing out of initParameters().
     */
    @Test
    public void everyOperatorUIBuildsFromOperatorDefaults() throws Exception {
        final List<Class<?>> uis = operatorUIClasses();


        final Map<String, String> aliases = aliasByOperatorClass();
        final Map<String, Class<?>> opClasses = operatorClassByAlias();
        final List<String> failures = new ArrayList<>();
        final List<String> nullParams = new ArrayList<>();
        int swept = 0, skipped = 0, excluded = 0;
        for (Class<?> uiClass : uis) {
            if (REQUIRES_APP_CONTEXT.contains(uiClass.getSimpleName())) {
                excluded++;     // deliberate, documented above - not a silent skip
                continue;
            }
            final String alias = operatorAliasFor(uiClass, aliases);
            if (alias == null) {
                skipped++;          // no matching operator on this classpath
                continue;
            }
            swept++;
            try {
                final BaseOperatorUI ui = (BaseOperatorUI) uiClass.getDeclaredConstructor().newInstance();
                final Map<String, Object> paramMap = new HashMap<>();
                ui.CreateOpTab(alias, paramMap, null);   // populates defaults, then initParameters()
                ui.updateParameters();                   // must not drop or trip over absent keys
                // an absent primitive key reads as null; putting that null back is how graph
                // entries silently became "vanished" parameters
                final Class<?> opClass = opClasses.get(alias);
                for (Map.Entry<String, Object> e : paramMap.entrySet()) {
                    if (e.getValue() == null && opClass != null
                            && isPrimitiveParameter(opClass, e.getKey())) {
                        nullParams.add(uiClass.getSimpleName() + " -> " + e.getKey());
                    }
                }
            } catch (Throwable t) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                final StackTraceElement where = root.getStackTrace().length > 0
                        ? root.getStackTrace()[0] : null;
                failures.add(uiClass.getSimpleName() + " (" + alias + "): "
                        + root.getClass().getSimpleName()
                        + (root.getMessage() == null ? "" : ": " + root.getMessage())
                        + (where == null ? "" : "  at " + where));
            }
        }
        System.out.println("TestOperatorUIDefaults: swept " + swept + " OperatorUIs, skipped "
                + skipped + ", deliberately excluded " + excluded);
        // a sweep that quietly stops sweeping is worse than no sweep: pin both counts
        assertEquals("some OperatorUI resolved no operator alias and was silently skipped - the "
                     + "UI-to-operator name convention broke, and that UI is now untested",
                     0, skipped);
        assertEquals("the number of OperatorUIs swept changed; if you added or removed one, update "
                     + "EXPECTED_UI_COUNT - if you did not, discovery is broken and this test was "
                     + "about to pass without exercising anything",
                     EXPECTED_UI_COUNT, swept);
        if (!nullParams.isEmpty()) {
            fail("OperatorUI(s) wrote a null over a PRIMITIVE operator parameter - it is "
                 + "silently lost:" + System.lineSeparator() + "  "
                 + String.join(System.lineSeparator() + "  ", nullParams));
        }
        if (!failures.isEmpty()) {
            fail("OperatorUI(s) failed to build from operator defaults - an absent primitive "
                 + "parameter key is the usual cause:\n  " + String.join("\n  ", failures));
        }
    }
}
