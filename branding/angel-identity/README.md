# ANGEL — editable Lunar Terminal identity

The V1 atmosphere is now rebuilt as editable source artwork: a round Moon, petrol night sky, blue ocean, rose display type and three independent extruded-pink `7` particles. The prism remains a separate compact mark. No character, body or face is used as the app identity.

## Wording

**Default: `>CRUEL / >ANGEL / >SYNTHESIS`.** Synthesis retains **thesis** inside the word and connects to generative synthesis and program synthesis. It preserves the sound of the reference while giving it a technical meaning.

**Alternative: `>CRUEL / >ANGEL / >INFERENCE`.** The clearest direct AI meaning, with less phonetic similarity. The original THESIS version is also preserved. All three are provided as editable wordmarks; no app name or UI body copy was changed to these taglines.

## Start here

| File | Purpose |
|---|---|
| `source/angel-square-editable.svg` | Square master, editable text and named SVG layers |
| `source/angel-square-layered.ora` | Layered GIMP-compatible OpenRaster document |
| `source/angel-square-outlined.svg` | Same design with text converted to vector paths |
| `source/angel-portrait-editable.svg` | Reflowed portrait composition |
| `source/angel-wide-editable.svg` | Reflowed wide composition |
| `source/wordmarks/` | Standalone transparent wordmarks, all three taglines |
| `source/particles/` | Three separate editable/outlined 7-particle sprites |
| `exports/` | Rendered PNG artwork and transparent sprites |
| `icons/angel-prism.svg` | Primary editable vector app mark |
| `icons/angel-lunar-gate.svg`, `icons/angel-threefold.svg` | Alternative mark directions |
| `icons/capabilities/` | Models, terminal, projects, connectors, skills and reverse-engineering symbols |
| `review/mark-comparison.png` | Large marks and native 16–64px checks |
| `review/adaptive-masks.png` | Circle, rounded, squircle and themed previews |
| `android/res/` | Adaptive foreground/background/monochrome, UI vectors and legacy launcher resources |

## Editing

The SVG masters have separate named layers for the sky, abstract facets, round Moon, orbit, ocean/reflections, **each 7**, and each of the three text lines. Move or recolor a group without disturbing the composition. The Moon itself is a linked raster texture, while the geometry, wave shapes and typography are vector elements.

Keep `source/` beside `references/`; the Moon image is linked as `../references/nasa-full-moon.png`. Text masters use the bundled **Bodoni Moda** and **IBM Plex Mono** fonts. Those original font files have also been installed into the current user's font directory for local editing. Font-independent `outlined.svg` counterparts preserve the glyph shapes for other machines.

Open the `.ora` file in GIMP for a layer-based raster workflow. Each text line and each 7 is a separate raster layer there; edit the SVG text masters when changing the actual wording or typeface. The ORA contains a merged preview and all layer pixels, so it does not need the linked Moon file.

The 7 particle groups contain an extrusion, bevel and face. Move the whole group to change its position or orientation; recolor its face gradient for another material. Exactly three particle groups appear in the main artwork.

The primary mark uses three broad planes and a terminal-like opening. The 16/24/32px optical variants brighten the small teal facet; the silhouette remains consistent. The monochrome variant uses one continuous even-odd silhouette to avoid seams between facets.

## Android integration

The candidate Prism Gate mark is integrated into the local app source:

- The adaptive launcher XML uses dedicated foreground, background and monochrome resources.
- Legacy mdpi through xxxhdpi launcher icons and round icons are replaced with matching exports.
- `ic_angel_mark.xml` replaces the two previous sparkle brand marks in the empty-chat and message-brand slots. It follows the existing Material theme tint.
- Six additional capability vectors are supplied with the `ic_angel_` prefix. Existing functional controls retain their current icon references.
- Store metadata receives a 512px icon and a 1024×500 feature graphic.

The 108dp adaptive foreground keeps the mark within the central 66dp safe region. Its drawn tips are 30dp from center; the colored layers are unmasked. Separate mock previews illustrate common masks, rather than baking those masks into the adaptive resources. [Android adaptive-icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)

Local integration is recorded in `integration-manifest.json`, with original-file backups under `references/app-before-branding/`. Only the targeted resource files and two drawable references were changed by the installer. Existing unrelated source changes were preserved. The build checks process resources and compile Kotlin; this workflow does not install an APK, sign or publish a release.

## Rebuild

```sh
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python scripts/build_assets.py
```

The builder regenerates the named sources and exports from its editable geometry/type parameters. Save a separate version of manually edited SVGs before running it again. CairoSVG is used for raster export, HarfBuzz for glyph placement, and FontTools for outlined variants. Pillow is used for format conversions, legacy round masks and contact sheets.

To copy a rebuilt candidate into an app checkout:

```sh
.venv/bin/python scripts/install_app_assets.py --project /path/to/angel-android
```

The installer preserves the first pre-branding copy of changed files and retains their original hashes on later runs. It does not reset the repository or replace unrelated source files.

## Sources and licenses

- Moon: [NASA SVS, Moon Phase and Libration](https://svs.gsfc.nasa.gov/5048), Full Moon still `phase_full.1571.tif`, NASA Scientific Visualization Studio / Ernie Wright (USRA). The original transparent TIFF is retained in `references/`; the PNG is a format conversion of that file. NASA SVS lists its content as public domain unless marked otherwise in its [reuse FAQ](https://svs.gsfc.nasa.gov/help/). The round outline and lunar markings are retained.
- Display face: [Bodoni Moda](https://github.com/google/fonts/tree/main/ofl/bodonimoda), Owen Earl / Indestructible Type. Original variable font files and their OFL license are in `fonts/bodonimoda/`.
- Mono face and numeral particles: [IBM Plex Mono](https://github.com/IBM/plex), IBM. Original font files and their OFL license are in `fonts/ibmplexmono/`.
- [OpenRaster file specification](https://www.openraster.org/baseline/file-layout-spec.html) and [layer-stack specification](https://www.openraster.org/baseline/layer-stack-spec.html).

`references/download-manifest.json` records source URLs, Git blob identities where available, and SHA-256 hashes. `asset-manifest.json` records generated artwork/resources. The V2 artwork is native SVG authoring, with an attributed NASA image; it does not use the flattened V1 generation as its editable master.
