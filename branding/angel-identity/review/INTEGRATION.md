# Identity integration check — 2026-11-07

The owner identified the original editable artwork directory as `angel-branding/v2` in the earlier workspace. Its files were compared with this repository before the stabilization handoff.

- All supplied Android resources match `app/src/main/res` byte for byte: adaptive foreground/background/monochrome, chat mark, six capability vectors and legacy launcher icons.
- Both chat branding slots reference `ic_angel_mark`.
- The fastlane 512px store icon matches the supplied Prism export. The 1024×500 feature graphic matches the supplied export and was visually inspected.
- All entries in `asset-manifest.json` match their SHA-256 hashes. Editable SVG/ORA masters, Moon reference and licensed fonts match the supplied files.
- **The selected wording is SYNTHESIS.** The square, portrait and wide default SVGs contain SYNTHESIS, and the active feature graphic displays it. INFERENCE/THESIS wordmarks remain optional source alternatives; they are not used for the selected store artwork. The builder's default is SYNTHESIS.
- The six capability vectors are supplied for future UI integration; existing functional controls intentionally retain their icons, as described by the original artwork README.
- The repository README retains its corrected NASA source credit. The supplied compiled-resource ZIP and Python cache are excluded from source publication.
- No text-prompt backups were found in the supplied Angel V2 directory or publishable repository. Prompt-backup ignore rules remain in place. The runtime Reverse Engineering SKILL.md is an intentional app feature.

No asset regeneration or APK changes were needed for this comparison. The existing debug build and device acceptance results apply to the same resources. This check does not close the release blockers in `HANDOFF.md`.
