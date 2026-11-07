# Validation — 7 November 2026

- Rendered square, portrait and wide artwork, with the linked Moon image resolved for PNG export; visually inspected the square and feature graphic, portrait layout, marks, masks and a particle sprite.
- Checked the OpenRaster ZIP structure, stored first `mimetype` entry, layer references and PNG files. Recomposition of its 11 layers matches the merged preview with mean differences below 0.013 per 8-bit color channel (rounding at translucent edges).
- Reviewed primary and alternative marks at native 16/24/32/48/64px, plus common adaptive-mask and themed-color previews.
- `aapt2 compile --dir android/res`: passed for the standalone asset resources.
- In the Android project: `./gradlew :app:processDebugResources :app:compileDebugKotlin --offline --console=plain`: **BUILD SUCCESSFUL**. See `gradle-validation.log`.
- Compared every integrated file against `integration-manifest.json`. Verified that the current ChatScreen differs from the saved pre-branding copy only in the two drawable references.

No instrumentation tests, APK installation, signing or publication were performed. The OpenRaster file was validated structurally and by recomposition; it was not opened through GIMP's live UI during this work.
