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
package eu.esa.sar.io.binary;

import org.esa.snap.core.datamodel.MetadataAttribute;
import org.esa.snap.core.datamodel.MetadataElement;
import org.esa.snap.core.datamodel.ProductData;
import org.esa.snap.core.dataop.downloadable.XMLSupport;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.engine_utilities.util.ResourceUtils;
import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Binary database reader.
 * <p>
 * Reads CEOS-style fixed-width binary records described by an XML field definition.
 * Two caches keep repeated opens cheap:
 * <ul>
 *   <li>{@link #XML_CACHE} — the parsed JDOM {@code Document} per (resource path,
 *       mission, file name) tuple. Each definition file is read and XML-parsed exactly
 *       once per JVM.</li>
 *   <li>{@link #COMPILED_CACHE} — a pre-compiled {@link Step}{@code []} per
 *       {@code Document}. Pre-compilation turns the XML attribute lookups + integer
 *       parses into a flat array of records that {@link #readRecord} just iterates.</li>
 * </ul>
 */
public final class BinaryDBReader {

    private static final int Skip = 0;
    private static final int An = 1;
    private static final int In = 2;
    private static final int B1 = 3;
    private static final int B4 = 4;
    private static final int Fn = 5;
    private static final int B2 = 6;
    private static final int En = 7;
    private static final int B8 = 8;
    private static final int Debug = -1;

    /** Sentinel "type" for a {@link Step} that holds a struct-loop body rather than a field. */
    private static final int STRUCT = -100;

    /** Expected upper bound on attribute count for a single CEOS record. */
    private static final int META_MAP_INITIAL_CAPACITY = 512;

    private final Map<String, Object> metaMap = new HashMap<>(META_MAP_INITIAL_CAPACITY, 1.0f);
    private final Step[] steps;
    private final String recName;
    private final long startPos;

    private static final boolean DEBUG_MODE = false;

    public BinaryDBReader(final Document xmlDoc, final String recName, final long startPos) {
        this.steps = compileIfNeeded(xmlDoc);
        this.recName = recName;
        this.startPos = startPos;
    }

    public void assignMetadataTo(final MetadataElement elem) {
        for (final Map.Entry<String, Object> entry : metaMap.entrySet()) {
            final String key = entry.getKey();
            final Object value = entry.getValue();
            if (value == null || key.isEmpty()) continue;

            if (value instanceof Integer) {
                final MetadataAttribute attrib = new MetadataAttribute(key, ProductData.TYPE_INT32, 1);
                attrib.getData().setElemInt((Integer) value);
                elem.addAttribute(attrib);
            } else if (value instanceof Double) {
                final MetadataAttribute attrib = new MetadataAttribute(key, ProductData.TYPE_FLOAT64, 1);
                attrib.getData().setElemDouble((Double) value);
                elem.addAttribute(attrib);
            } else {
                elem.setAttributeString(key, String.valueOf(value));
            }
        }
    }

    public void readRecord(final BinaryFileReader reader) {
        if (DEBUG_MODE) {
            System.out.print("\nReading " + recName + "\n\n");
        }
        executeSteps(reader, steps);
    }

    private void executeSteps(final BinaryFileReader reader, final Step[] steps) {
        for (final Step step : steps) {
            if (step.type == STRUCT) {
                final int loop = (step.loopName != null) ? getAttributeInt(step.loopName) : step.nloop;
                for (int l = 1; l <= loop; ++l) {
                    final String suffix = " " + l;
                    for (final Step child : step.body) {
                        if (DEBUG_MODE) {
                            decodeFieldDebug(reader, child, suffix);
                        } else {
                            decodeField(reader, child, suffix);
                        }
                    }
                }
            } else {
                if (DEBUG_MODE) {
                    decodeFieldDebug(reader, step, null);
                } else {
                    decodeField(reader, step, null);
                }
            }
        }
    }

    private void decodeField(final BinaryFileReader reader, final Step step, final String suffix) {
        final String name = (suffix == null) ? step.name : step.name + suffix;
        try {
            switch (step.type) {
                case Skip:
                    reader.skipBytes(step.num);
                    break;
                case An:
                    metaMap.put(name, reader.readAn(step.num));
                    break;
                case In:
                    metaMap.put(name, (int) reader.readIn(step.num));
                    break;
                case B1:
                    metaMap.put(name, reader.readB1());
                    break;
                case B2:
                    metaMap.put(name, reader.readB2());
                    break;
                case B4:
                    metaMap.put(name, reader.readB4());
                    break;
                case B8:
                    metaMap.put(name, reader.readB8());
                    break;
                case Fn:
                    metaMap.put(name, reader.readFn(step.num));
                    break;
                case En:
                    metaMap.put(name, reader.readEn(step.num));
                    break;
                case Debug:
                    System.out.print(" = ");
                    for (int i = 0; i < step.num; ++i) {
                        final String tmp = reader.readAn(1);
                        if (!tmp.isEmpty() && !tmp.equals(" "))
                            System.out.print(tmp);
                    }
                    System.out.println();
                    break;
                default:
                    throw new IllegalBinaryFormatException("Unknown type " + step.type, reader.getCurrentPos());
            }
        } catch (Exception e) {
            if (e.getCause() != null)
                SystemUtils.LOG.severe(' ' + e.toString() + ':' + e.getCause().toString() + " for " + name);
            else
                SystemUtils.LOG.severe(' ' + e.toString() + ':' + " for " + name);
        }
    }

    private void decodeFieldDebug(final BinaryFileReader reader, final Step step, final String suffix) {
        final String name = (suffix == null) ? step.name : step.name + suffix;
        try {
            System.out.print(" " + reader.getCurrentPos() + ' ' + (reader.getCurrentPos() - startPos + 1) +
                    ' ' + name + ' ' + step.type + ' ' + step.num);

            switch (step.type) {
                case Skip:
                    reader.skipBytes(step.num);
                    break;
                case An: {
                    final String tmp = reader.readAn(step.num);
                    System.out.print(" = " + tmp);
                    metaMap.put(name, tmp);
                    break;
                }
                case In: {
                    final int tmp = (int) reader.readIn(step.num);
                    System.out.print(" = " + tmp);
                    metaMap.put(name, tmp);
                    break;
                }
                case B1: {
                    final int tmp = reader.readB1();
                    System.out.print(" = " + tmp);
                    metaMap.put(name, tmp);
                    break;
                }
                case B2: {
                    final int tmp = reader.readB2();
                    System.out.print(" = " + tmp);
                    metaMap.put(name, tmp);
                    break;
                }
                case B4: {
                    final int tmp = reader.readB4();
                    System.out.print(" = " + tmp);
                    metaMap.put(name, tmp);
                    break;
                }
                case B8: {
                    final long tmp = reader.readB8();
                    System.out.print(" = " + tmp);
                    metaMap.put(name, tmp);
                    break;
                }
                case Fn: {
                    final double tmp = reader.readFn(step.num);
                    System.out.print(" = " + tmp);
                    metaMap.put(name, tmp);
                    break;
                }
                case En: {
                    final double tmp = reader.readEn(step.num);
                    System.out.print(" = " + tmp);
                    metaMap.put(name, tmp);
                    break;
                }
                case Debug:
                    System.out.print(" = ");
                    for (int i = 0; i < step.num; ++i) {
                        final String tmp = reader.readAn(1);
                        if (!tmp.isEmpty() && !tmp.equals(" "))
                            System.out.print(tmp);
                    }
                    System.out.println();
                    break;
                default:
                    throw new IllegalBinaryFormatException("Unknown type " + step.type, reader.getCurrentPos());
            }
            System.out.println();
        } catch (Exception e) {
            if (e.getCause() != null)
                SystemUtils.LOG.severe(' ' + e.toString() + ':' + e.getCause().toString() + " for " + name);
            else
                SystemUtils.LOG.severe(' ' + e.toString() + ':' + " for " + name);
        }
    }

    private Object get(final String name) {
        final Object obj = metaMap.get(name);
        if (obj == null && DEBUG_MODE) {
            SystemUtils.LOG.info("metadata " + name + " is null");
        }
        return obj;
    }

    public String getAttributeString(final String name) {
        return (String) get(name);
    }

    public Integer getAttributeInt(final String name) {
        final Integer i = (Integer) get(name);
        return i == null ? 0 : i;
    }

    public Double getAttributeDouble(final String name) {
        final Double d = (Double) get(name);
        return d == null ? 0 : d;
    }

    public void set(final String name, final Object o) {
        metaMap.put(name, o);
    }

    // ------------------------------------------------------------------------
    // Definition loading and pre-compilation
    // ------------------------------------------------------------------------

    /**
     * Cache of parsed XML definition Documents, keyed by their resource path. Each
     * definition file is read and XML-parsed exactly once per JVM.
     */
    private static final ConcurrentMap<String, Document> XML_CACHE = new ConcurrentHashMap<>();

    /**
     * Cache of compiled step arrays keyed by the parsed {@link Document}. A
     * {@link WeakHashMap} so that if a Document is ever discarded, the compiled form
     * can be GC'd with it. Wrapped in synchronized access because {@code WeakHashMap}
     * itself is not thread-safe.
     */
    private static final Map<Document, Step[]> COMPILED_CACHE = new WeakHashMap<>();

    /**
     * Read in the definition file.
     *
     * @param mission  sub folder
     * @param fileName definition file
     * @return xml document
     */
    public static Document loadDefinitionFile(final String mission, final String fileName) {
        final String resourcePath = "eu/esa/sar/io/ceos_db/";
        return loadDefinitionFile(resourcePath, mission, fileName, BinaryDBReader.class);
    }

    /**
     * Read in the definition file (cached).
     *
     * @param mission  sub folder
     * @param fileName definition file
     * @return xml document
     */
    public static Document loadDefinitionFile(final String resourcePath, final String mission, final String fileName,
                                              final Class<?> resourceClass) {
        final String key = resourcePath + '|' + mission + '|' + fileName + '|' + resourceClass.getName();
        Document doc = XML_CACHE.get(key);
        if (doc != null) return doc;
        doc = parseDefinitionFile(resourcePath, mission, fileName, resourceClass);
        if (doc != null) {
            // Race-friendly: putIfAbsent guarantees we expose the first-loaded document
            // even if two threads happened to parse it in parallel.
            final Document prior = XML_CACHE.putIfAbsent(key, doc);
            if (prior != null) doc = prior;
        }
        return doc;
    }

    private static Document parseDefinitionFile(final String resourcePath, final String mission, final String fileName,
                                                final Class<?> resourceClass) {
        try (InputStream defStream = getResStream(resourcePath, mission, fileName, resourceClass)) {
            return XMLSupport.LoadXML(defStream);
        } catch (Exception e) {
            SystemUtils.LOG.severe("Unable to open " + fileName + ": " + e.getMessage());
        }
        return null;
    }

    private static InputStream getResStream(final String resourcePath, final String mission, final String fileName,
                                            final Class<?> resourceClass) throws IOException {
        final String path = resourcePath + mission.toLowerCase() + "/" + fileName;
        return ResourceUtils.getResourceAsStream(path, resourceClass);
    }

    /**
     * Look up (or build) the pre-compiled {@link Step}{@code []} for an XML definition.
     * Compilation pre-parses every {@code type}/{@code num} attribute and flattens
     * struct loops into a single nested step, so {@link #readRecord} doesn't touch
     * JDOM on the hot path.
     */
    private static Step[] compileIfNeeded(final Document xmlDoc) {
        if (xmlDoc == null) return new Step[0];
        synchronized (COMPILED_CACHE) {
            final Step[] cached = COMPILED_CACHE.get(xmlDoc);
            if (cached != null) return cached;
            final Step[] compiled = compileChildren(xmlDoc.getRootElement().getChildren());
            COMPILED_CACHE.put(xmlDoc, compiled);
            return compiled;
        }
    }

    private static Step[] compileChildren(final List<Element> children) {
        final java.util.ArrayList<Step> out = new java.util.ArrayList<>(children.size());
        for (final Element child : children) {
            if ("struct".equals(child.getName())) {
                final Attribute loopAttrib = child.getAttribute("loop");
                final int nloop;
                final String loopName;
                if (loopAttrib != null) {
                    loopName = loopAttrib.getValue();
                    nloop = -1;
                } else {
                    final Attribute nloopAttr = child.getAttribute("nloop");
                    nloop = (nloopAttr != null) ? Integer.parseInt(nloopAttr.getValue()) : 0;
                    loopName = null;
                }
                final Step[] body = compileChildren(child.getChildren());
                out.add(Step.struct(body, nloop, loopName));
                // Note: in the original code, after expanding the struct loop, the struct
                // element itself was also passed to DecodeElement. That call was a no-op
                // because struct elements lack name/type/num attributes, so we don't emit
                // a separate field-step for the struct.
            } else {
                final Attribute nameAttrib = child.getAttribute("name");
                final Attribute typeAttrib = child.getAttribute("type");
                final Attribute numAttrib = child.getAttribute("num");
                if (nameAttrib != null && typeAttrib != null && numAttrib != null) {
                    final int type = Integer.parseInt(typeAttrib.getValue());
                    final int num = Integer.parseInt(numAttrib.getValue());
                    out.add(Step.field(type, num, nameAttrib.getValue()));
                }
            }
        }
        return out.toArray(new Step[0]);
    }

    /**
     * Pre-compiled representation of one element of the XML record definition.
     * Either a leaf field ({@link #type} is one of the {@code An}/{@code In}/...
     * constants) or a struct loop ({@link #type} == {@link #STRUCT}, with
     * {@link #body} holding the inner fields).
     */
    private static final class Step {
        final int type;
        final int num;
        final String name;
        final Step[] body;     // non-null only when type == STRUCT
        final int nloop;       // -1 if dynamic (resolved via loopName)
        final String loopName; // non-null if dynamic

        private Step(int type, int num, String name, Step[] body, int nloop, String loopName) {
            this.type = type;
            this.num = num;
            this.name = name;
            this.body = body;
            this.nloop = nloop;
            this.loopName = loopName;
        }

        static Step field(int type, int num, String name) {
            return new Step(type, num, name, null, 0, null);
        }

        static Step struct(Step[] body, int nloop, String loopName) {
            return new Step(STRUCT, 0, null, body, nloop, loopName);
        }
    }
}
