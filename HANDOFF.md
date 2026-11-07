# Angel 1.1 continuation notes

Updated 2026-11-07. The stabilization work and owner-provided V2/SYNTHESIS identity are recorded in the PR and upstream release linked from RELEASE_PLAN.md. See `RELEASE_PLAN.md` for recorded results, explicit testing limits and external F-Droid steps; `BUILDING.md` for commands; and `fdroid/REVIEW.md` for packaging policy notes.

## Completed fixes

- llama.cpp v0.8.0/b11960 adds LFM3 and handles the tested Qwen3.5 file with absent optional MTP layers. The full retained upstream snapshot is unmodified; provenance is in VENDORED.md. App/JNI adaptations preserve token IDs, own imported descriptors, report progress and cancel native decoding with cache recovery.
- Model downloads own unique files; missing/truncated downloads are unavailable. Gemma's large shared embedding avoids expensive repacking, but cold startup is still slow.
- Workspace/Git paths, initial/rename unstage, installer timeouts and distro-specific skill setup have regressions. RE setup builds pinned r2mcp 1.8.0 with verified source commit and checks compiler/headers independently.
- Python GitHub installs choose the project's own console entry, not a dependency executable. Actual Python/npm installs and RE setup pass through the app UI.
- Tool denial stops the entire request/batch; Always allow is persisted only on Run.
- Explicit Open in a new chat starts empty and preserves the old chat, including for the already-active project. Requests snapshot directory, distro and project context; MCP discovery/server selection and prompt instructions use that snapshot too. Active-project deletion is blocked during a request.
- Unsaved project selections and commit drafts survive rotation/failure.

## Validation

76 JVM tests pass. The complete safe device suite reports 50 cases: 42 pass and eight opt-in model cases skip; it includes the new-chat and expanded project/MCP-context regression. There are also 17 separate focused native passes across five imports and a saved-settings Gemma greeting. Lint has zero errors, 53 warnings and two hints.

A final full local F-Droid recipe build/source/APK scan passed, with independent unsigned equality and exact signed equality after signature copying. Fresh release-mode startup was checked with a separate application ID. The ordinary debug install was upgraded without clearing app data. A release-signed 1.0 user-data upgrade, live cloud-provider credentials and older ARM64/API hardware are not claimed as tested. The broader follow-up matrix remains documented in RELEASE_PLAN.md.

## Maintain these constraints

- Keep V2 editable SVG/ORA masters, fonts, notices and Moon provenance. SYNTHESIS is selected; INFERENCE/THESIS remain source alternatives only. No artwork prompt backups are included. Six capability vectors are supplied but intentionally not assigned to existing functional controls.
- Never commit credentials, passwords, keys, model weights or APKs. The original release key remains on the owner's computer. Its public SHA-256 certificate is `773df628227f9ee4d2e24e7505f0f3b68a65b4eb97cafe467dcf366c58d11058`.
- Native code stays optimized in all variants. Preserve version/path normalization and the JDK 21/toolchain pins needed for reproducibility. Do not edit vendored upstream files to fix app behavior.
- Never use connectedDebugAndroidTest against the owner's installation. Install APKs with `-r` and use direct instrumentation. Do not uninstall/clear the main app to resolve a signing mismatch.
- Model weights and downloaded tools have separate licenses. Executable downloads require review/opt-in and explicitly disclose that they bypass F-Droid checks.

## F-Droid and later work

The metadata is an upstream-signed packaging proposal, not evidence of inclusion. Publication requires the public version tag and matching signed asset, a matching independent build, and F-Droid's own build-server/maintainer review. Proposed labels are NonFreeNet and NonFreeAdd. The development-history exception is for maintainers to decide. See `fdroid/SUBMISSION.md` for the prepared submission text.

Do not describe unperformed follow-up checks as passed. Keep limitations accurate, fix confirmed bugs, and run focused tests appropriate to subsequent changes. The ignored `.codex/release-handoff-local.md` retains original-machine fixture/log locations for phone cleanup; it is not needed to build the repository.
