# Writing SAR concept pages for the Microwave Toolbox help

Concept pages explain the *why* behind the tools. Write them for an engineer or scientist who knows remote sensing in general but is new to SAR. Each one should be finished in about 5 minutes of reading.

## Location and IDs

| What | Where |
|---|---|
| Pages | `microwavetbx-rcp/src/main/resources/eu/esa/microwave/dat/docs/concepts/<file>.html` |
| Images | `…/docs/concepts/images/<file>_<n>.png` (lower-case, `.png`, exact case in links) |
| Figure sources | `tools/javahelp/figures/<file>_<n>.py`, one script per figure, using `figstyle.py` |
| Map ID | `Concept_<Name>`, as fixed in the plan. The controller adds `map.jhm`/`toc.xml` entries; writers don't edit those files. |
| Skeleton | Start from `tools/javahelp/templates/concept_template.html` |

## Structure (fixed order)

1. **Header table:** the existing SNAP header, plus `<h1>`.
2. **Lead (`<p class="lead">`, 2–3 sentences):** what it is and why it matters for the user's result.
3. **Body sections (`<h2>`):** explain the concept in plain language, then give the equation(s) that matter, then typical numbers. Use a table for numbers across missions: Sentinel-1 C-band, TerraSAR-X X-band, ALOS-2 L-band, NISAR L/S-band, and BIOMASS P-band where relevant.
4. **"In SNAP" (`<h2>In SNAP</h2>`):** how the concept shows up in the toolbox. Cover band names, units, which operator produces or uses it, important defaults and pitfalls. Every SNAP-specific statement must be checked against the code: grep the operator, read its `@Parameter` and band-naming code.
5. **"Common mistakes" (`<h2>`):** 3–6 bullets, e.g. "averaging in dB", "running Thermal Noise Removal on SLC before InSAR".
6. **"Used by" (`<h2>`):** links to the operator pages that depend on this concept. Use nbdocs links (format below).
7. **"Further reading" (`<h2>References</h2>`):** 3–8 *foundational* references in full: authors, year, title, journal, vol(issue), pages, DOI. Verify each against its DOI or publisher page before you write it. Prefer the paper that introduced the method plus one review or textbook. Never cite something you have not verified. If you can't verify a reference you want, leave it out and say so in your report.

## Length and tone

- **Length:** 600–1200 words of prose, plus figures and tables. Split anything longer into two pages.
- **Tone:** plain, direct and active. Define every symbol where it first appears.
- **No marketing, no history lessons, no padding.**
- **Terminology:** write Reference/Secondary, never master/slave. Literal identifiers go in `<code>`.
- **Numbers:** give units and orders of magnitude, e.g. "one fringe = λ/2 ≈ 2.8 cm of line-of-sight motion at C-band".

## HTML rules (Java Swing HTML 3.2 renderer)

- Allowed: `p, h1–h3, ul/ol/li, table/tr/td/th, img, a, b, i, sub, sup, br, pre, code`. Use only the classes in `../style.css` plus `lead`, `eq` and `figcap`, which the template defines inline.
- Not allowed: CSS3, flex/grid, JavaScript, MathML, SVG, `<figure>`, `<meta charset>`.
- **Non-ASCII:** use entities only (`&sigma;`, `&beta;`, `&gamma;`, `&theta;`, `&lambda;`, `&phi;`, `&Delta;`, `&deg;`, `&#8722;` for minus, `&le;`, `&ge;`, `&asymp;`, `&times;`, `&radic;`, `&sup2;`, `&perp;`). Save files as ASCII.
- **Equations:** one equation per `<p class="eq">`, built from text, entities, `<sub>` and `<sup>`. Use a PNG only if the equation can't be written that way (render it with matplotlib mathtext in the figure script).
- **Images:** `<img src="images/x.png" width="W" height="H" alt="...">`. Give the real pixel size. Keep width ≤ 560 px. Put a `<p class="figcap">` caption under each image.
- **Line endings:** CRLF, like the rest of the help.

## Figures

- Generate every figure with a script in `tools/javahelp/figures/`. Import `figstyle` from the same folder and call `figstyle.save(fig, "<file>_<n>")`, which writes the PNG into `concepts/images/`.
- Figures are diagrams, not decoration: geometry sketches, distributions, schematic spectra, a burst layout. Label the axes and give units.
- Keep text in figures to ≥ 9 pt at the saved size. Use the colour-blind-safe palette from `figstyle.COLORS`, on a white background.

## Links

- To another concept page: `<a href="tops.html">TOPS</a>`. Operator pages link back to concept pages through the help map, so keep file names stable.
- To an operator page, use the form `nbdocs://<code-name>/<resource path>`:

| Module | Link prefix |
|---|---|
| sar-op-sar-processing-ui | `nbdocs://eu.esa.microwavetbx.sar.op.sar.processing.ui/eu/esa/sar/sar/docs/operators/<Page>.html` |
| sar-op-calibration-ui | `nbdocs://eu.esa.microwavetbx.sar.op.calibration.ui/eu/esa/sar/calibration/docs/operators/<Page>.html` |
| sar-op-insar-ui | `nbdocs://eu.esa.microwavetbx.sar.op.insar.ui/eu/esa/sar/insar/docs/operators/<Page>.html` |
| sar-op-sentinel1-ui | `nbdocs://eu.esa.microwavetbx.sar.op.sentinel1.ui/eu/esa/sar/sentinel1/docs/operators/<Page>.html` |
| sar-op-feature-extraction-ui | `nbdocs://eu.esa.microwavetbx.sar.op.feature.extraction.ui/eu/esa/sar/fex/docs/operators/<Page>.html` |
| sar-io-ephemeris-ui | `nbdocs://eu.esa.microwavetbx.sar.io.ephemeris.ui/eu/esa/sar/orbits/docs/operators/<Page>.html` |
| jlinda-nest-ui | `nbdocs://org.jlinda.jlinda.nest.ui/org/jlinda/nest/docs/operators/<Page>.html` |
| rstb-op-polarimetric-tools-ui | `nbdocs://org.csa.rstb.rstb.op.polarimetric.tools.ui/org/csa/rstb/polarimetric/docs/operators/<Page>.html` |
| rstb-op-classification-ui | `nbdocs://org.csa.rstb.rstb.op.classification.ui/org/csa/rstb/classification/docs/operators/<Page>.html` |
| rstb-op-soil-moisture-ui | `nbdocs://org.csa.rstb.rstb.op.soil.moisture.ui/org/csa/rstb/soilmoisture/docs/operators/<Page>.html` |

- Check every link with `python -I tools/javahelp/check_javahelp.py --external none --only link,html`. Until the controller maps your page, the `orphan` and `map` categories will report it; that is expected.

## Operator-page footer (added later, Task 3.2 footers)

Every operator page ends with this block, placed just before the closing `<hr>` or `</body>`:

```html
<!-- footer:begin -->
<h3>Background</h3>
<ul><li><a href="nbdocs://eu.esa.microwavetbx.microwavetbx.rcp/eu/esa/microwave/dat/docs/concepts/speckle.html">Speckle and multilooking</a></li></ul>
<h3>Prerequisites</h3>
<ul><li>…operator(s) that must run first, with links…</li></ul>
<h3>Next step</h3>
<ul><li>…typical next operator(s)…</li></ul>
<!-- footer:end -->
```

References stay in the page's own References section; Phase 5 completes them.
