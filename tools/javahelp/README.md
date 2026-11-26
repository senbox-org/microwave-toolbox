# JavaHelp tooling

## `check_javahelp.py`: help integrity check

```
python -I tools/javahelp/check_javahelp.py                 # full check, uses ../snap-desktop etc. if present
python -I tools/javahelp/check_javahelp.py --external none  # this repo only (CI); external links are skipped
python -I tools/javahelp/check_javahelp.py --only link,html # selected categories
```

The script needs Python 3 and uses only the standard library. It exits with status 1 when any error remains. Every comparison is case-sensitive, so problems that only appear inside the packaged JAR or on Linux are also caught on Windows.

| Category | What it checks |
|---|---|
| `registration` | Every `help.hs` is registered exactly once, either with `@HelpSetRegistration` in a `package-info.java` or with a `helpset.xml` that `layer.xml` references. |
| `map` | Every `map.jhm` url exists. No map ID is defined twice, appears in two helpsets, or collides with an ID in SNAP core or the optical toolbox. Colliding IDs merge into one help system, where either page may open. |
| `toc` | Every `toc.xml` target exists in its helpset's map. |
| `orphan` | Every page is mapped, and every mapped page can be reached from a TOC entry. |
| `helpid` | Every `helpId` in `layer.xml` and every `HelpCtx`/`helpId` string in Java resolves to a map ID. |
| `link` | Every relative `href`/`src` and every `nbdocs://<code-name>/<path>` link resolves. |
| `html` | One `<title>`, inside `<head>`; `<head>` comes before `<body>`; a single `<html>` element. |
| `params` | Every generated parameter block (`<!-- params:begin op="..." -->`) still matches the operator source, every alias in `params_pages.txt` names an existing operator, and (with `../snap-engine`) `external_constants.json` is current. It parses every operator, so it runs only without `--only` or when `--only` names `params`. See `gen_params.py` below. |
| `refs` | Every operator page (`*.html` under a `docs/operators/` folder, plus the `sar-op-analysis-ui` `docs/rcp` and `docs/wave` pages, never `target/`) has a References section outside the generated `params` and `footer` blocks: a heading (`h1`-`h4`, or a `<b>`/`<strong>` line) containing "Reference", followed by at least one `<li>` or a `<p>` with a 4-digit year. Pages that implement no published method are listed in `refs_exempt.txt` as `path|reason` (repo-relative, forward slashes, `#` comments). A stale exemption (page missing) is an error; an exempt page that has References is allowed. Runs only without `--only` or when `--only` names `refs`. |
| `style` | Outdated wording and dead references in the visible text of every help page (`*.html` under a `docs/` folder, never `target/`). **S1** `master`, `masters`, `slave`, `slaves`, `mst`, `slv` as whole words (case-insensitive). **S2** dead or legacy domains and names: `sentinel.esa.int`, `earth.esa.int/pub`, `envisat.esa.int`, `nest.array.ca`, `directory.eoportal.org`, `xftp.jrc.it`, `desktopdefault.aspx`, `s1tbx`, `.s1tbx`, `NEST4A`, `C:\Program Files\NEST`; also matched inside `href`/`src` values. **S3** stale statements: `SRTM 3Sec (Auto Download)`, "default DEM"/"default is SRTM" within 80 characters of "SRTM", `Sentinel-1B` followed within 80 characters by "present" or "operational", `Windows XP`. Not scanned: HTML comments, `<code>`, `<tt>`, `<pre>`, `<kbd>`, generated `params` blocks, attribute values other than `href`/`src`. Runs without `--only` or when `--only` names `style`. See "Style allowlists" below. |
| `index` | Every `help.hs` declares a `javax.help.IndexView` view whose data file exists and is well-formed, every `indexitem` target in that `index.xml` is a map ID of the same helpset (JavaHelp resolves index targets against the index's own helpset only, so IDs of other helpsets fail even after merging), and every `index.xml` is what `gen_index.py` would write now. Runs without `--only` or when `--only` names `index`. See `gen_index.py` below. |

### nbdocs links

The format is `nbdocs://<module code name>/<resource path>`. The code name is the `groupId` and the `artifactId` with `-` replaced by `.`. For example, `sar-op-insar-ui` in `eu.esa.microwavetbx` becomes `nbdocs://eu.esa.microwavetbx.sar.op.insar.ui/eu/esa/sar/insar/docs/operators/WarpOp.html`.

### Tests

```
python -I tools/javahelp/test_check_refs.py
python -I tools/javahelp/test_check_search.py
python -I tools/javahelp/test_check_style.py
python -I tools/javahelp/test_gen_index.py
```

### Allowlist

`allowlist.txt` holds intentional exceptions, one per line in the form `category|key|reason`. Every entry must name the plan task that will remove it; see `docs/plans/2026-11-12-javahelp-overhaul-plan.md`. Do not allowlist new pages; fix them instead.

### Style allowlists

Two mechanisms, both for the `style` category:

- `style_allow.txt` lists literal phrases, one `phrase|reason` per line (`#` comments). A finding is suppressed when it lies inside an occurrence of an allowed phrase (case-sensitive; whitespace and markup between words are ignored). Use it for identifiers and UI labels that must stay literal, such as `MultiMasterInSAR`, `Output Extent = Master` or `Auto-coregister GSLC slaves`; the reason should cite the Java source.
- `allowlist.txt` accepts `style|<key>|reason`. The key is `<page>:<rule>` (suppresses a rule for the whole page) or `<page>:<rule>:<hash>`, where `<hash>` is the 8-hex-digit line hash printed at the end of every finding as `[S1:1a2b3c4d]` (suppresses that one line, and stops matching if the line text changes). `<page>` is the repo-relative path with forward slashes.

Every finding reads `page:line: RULE 'phrase' in "...context..." [RULE:hash]`.

### CI

The repo's `Jenkinsfile` only triggers the external `snap-ci-nightly` job. To gate on help integrity, add a step there that runs the `--external none` form (about 15 s) on an agent that has `python3`.

## `gen_params.py`: parameter reference tables

```
python -I tools/javahelp/gen_params.py --list               # alias | class | page | #params | #unresolved, then a summary
python -I tools/javahelp/gen_params.py --show Speckle-Filter  # print one table
python -I tools/javahelp/gen_params.py --write [ALIAS ...]  # refresh existing blocks (all, or the named operators)
python -I tools/javahelp/gen_params.py --insert [ALIAS ...] # refresh, and add a block to pages that have none
python -I tools/javahelp/gen_params.py --check              # exit 1 when a block or the constant cache is stale
python -I tools/javahelp/gen_params.py --refresh-external   # rewrite external_constants.json (needs ../snap-engine)
```

`--write` and `--insert` skip, and name, every operator that has an unresolved expression; add `--allow-unresolved` to write those too. An unknown alias on the command line is an error (exit 2). `--list` warns about operator aliases defined twice.

The generator reads the `@Parameter` annotations in the Java source with the standard library only. It does not run `gpt -h`, so it documents the source version and needs no SNAP install. The table it writes is an addition to the hand-written page and lives in its own block:

```html
<!-- params:begin op="Speckle-Filter" -->
<h3>Processing parameters (reference)</h3>
<p>GPT operator: <code>Speckle-Filter</code>. Generated from the operator source; ...</p>
...generated table...
<!-- params:end -->
```

When one page carries several blocks (for example ObjectDetection.html, which documents two operators), each heading ends with ` &#8212; <alias>`.

Only the text between the markers is rewritten. The page's encoding and its CRLF or LF line endings are kept. `--insert` puts a new block before `<!-- footer:begin -->` when the page has one, otherwise before the last `<hr>` ahead of `</body>`, otherwise before `</body>`. Internal operators (`internal = true`) are listed but never inserted.

Columns: the GPT name (`alias` if the parameter has one, else the field name), the Java type, the default, the allowed values (`valueSet`, else `interval`), the unit and the description (else the label). Parameters whose label or description starts with the word "Internal" are left out. The default is `defaultValue`; without one, a primitive shows GPF's zero value (`0`, `0.0`, `false`, `\u0000`, which overrides the field initializer) and an object type shows its field initializer when that is a literal or a constant.

**What is parsed.** Multi-line annotations, string concatenation, nested braces and comments; `//` and `/* */` comments are stripped first, so commented-out parameters are ignored. Parameters inherited from a superclass are included, base class first. Only fields declared directly in the operator class count; `@Parameter` fields of nested helper classes do not. Classes that are abstract, lack `@OperatorMetadata` or extend nothing (such as `MuLog`, a speckle-filter helper) are not operators.

**Constants.** Annotation values may be constants in the same or an outer class, in another class (`Other.FIELD`, `pkg.Other.FIELD`, nested classes), inherited, statically imported, enum constants (shown by name), or `"a" + CONST` concatenations. They are resolved across this repo and `../snap-engine` when it is present. An expression that cannot be resolved is shown as written and counted under `#unresolved`.

**External constant cache.** `external_constants.json` holds the snap-engine constants that this repo's annotations reference, as `{"SimpleClass.FIELD": "value"}`. The resolver uses it only when the class is not found in the sources, so without `../snap-engine` (as on CI) every table still comes out exactly as with it, and `--check` compares every block. Where `../snap-engine` is present, `--check` (and the `params` category) also reports cache entries that are missing, no longer referenced or stale; run `--refresh-external` and commit the file. `--refresh-external` fails when one `SimpleClass.FIELD` resolves to different values in different classes.

**Operator to page.** Each `layer.xml` action with both `operatorName` and `helpId` maps the operator to the page its help ID has in `map.jhm`. Operators reached through graph actions or custom action classes are mapped in `params_pages.txt` (`alias|page path relative to the repo root`). These overrides win over `layer.xml`.

**Tests.**

```
python -I tools/javahelp/test_gen_params.py
python -I -m unittest discover -s tools/javahelp -p "test_gen_params.py"
python -m unittest tools/javahelp/test_gen_params.py      # without -I: -I's safe-path mode keeps the repo root off sys.path
```

The fixtures in `test_fixtures/` are small Java snippets adapted from real operators.

## `gen_missions.py`: Supported Missions page

```
python -I tools/javahelp/gen_missions.py           # print mission | reader | extensions | help page
python -I tools/javahelp/gen_missions.py --write   # rewrite the table in sar-io/.../docs/SupportedMissions.html
python -I tools/javahelp/gen_missions.py --check   # exit 1 when the table is stale, a reader has no override, or a help page is missing
python -I tools/javahelp/test_gen_missions.py
```

The generator reads every `META-INF/services/org.esa.snap.core.dataio.ProductReaderPlugIn` file in the repo and parses each PlugIn's Java (plus its in-repo superclasses and `*Constants` classes) with regular expressions for the format names, default extensions and description. Nothing is compiled or instantiated. The table is written between `<!-- missions:begin -->` and `<!-- missions:end -->`; the rest of the page is hand-written.

`missions_overrides.json` gives each reader its mission name and help page (`readers` keyed by PlugIn class), lists registered readers that are not mission readers (`skip`), and adds rows for SNAP-core readers that have a page here (`extra`). When you add a reader, add its entry there and run `--write`.

## `gen_index.py`: keyword index (Index tab)

```
python -I tools/javahelp/gen_index.py           # entry counts per helpset
python -I tools/javahelp/gen_index.py --show    # every entry: helpset | text | target
python -I tools/javahelp/gen_index.py --write   # rewrite every index.xml and add the Index view to help.hs
python -I tools/javahelp/gen_index.py --check   # exit 1 when an index.xml or help.hs is stale
python -I tools/javahelp/test_gen_index.py
```

Every helpset gets a generated `index.xml` (JavaHelp Index DTD 2.0, UTF-8, CRLF) next to its `help.hs`, and `help.hs` declares it after the TOC view:

```xml
<view mergetype="javax.help.SortMerge">
    <name>Index</name>
    <label>Index</label>
    <type>javax.help.IndexView</type>
    <data>index.xml</data>
</view>
```

The view name `Index` and `javax.help.SortMerge` match the Index view in SNAP's master helpset (`masterHelpSet.xml` in the `eu.esa.snap.netbeans:snap-help-system` module). That view is commented out in version 1.0.3 of the module (the one the installed SNAP ships), and `JHelp` only merges a module view into a navigator the master already has, so the Index tab appears in SNAP only once the master view is enabled there.

The entries are flat (no letter groups) and sorted case-insensitively:

- every mapped page under its `<title>` without the `Help - ` prefix, on one map ID per page (the first ID the TOC uses, else the first in `map.jhm`);
- every TOC label that differs from the title, as a synonym. When several pages of a helpset share a title (the Sentinel-1 operator pages are all titled "Sentinel-1"), only their TOC labels are used. A map ID whose url has an `#anchor` gets its TOC label only;
- every GPT operator alias whose page the helpset maps, as `<alias> (GPT operator)`, using `gen_params.page_map` (`layer.xml` plus `params_pages.txt`);
- in the helpset that maps `Concept_Glossary` only, every glossary term (`<p><b>Term.</b>` in `concepts/glossary.html`), pointing at the glossary page. The glossary has no per-term anchors, so the entries open the page itself. A term that is also the text of another entry gets ` (glossary)` appended.

Every target is a map ID of the same helpset. JavaHelp creates an index item with `Map.ID.create(target, hs)` on the index's own helpset, whose combined map holds only that helpset's IDs; an ID of another module's helpset is dropped even when the helpsets are merged.

Run `--write` after changing page titles, `map.jhm`, `toc.xml`, operator help IDs or the glossary; the `index` category of `check_javahelp.py` reports a stale `index.xml`.
