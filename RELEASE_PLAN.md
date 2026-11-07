# Angel 1.1 release verification

Updated 2026-11-07. This replaces the original exploratory checklist with observed release results and explicit remaining limits. Unperformed checks below are not claimed as passes.

## Local acceptance

- [x] All reported local-model load failures fixed: 17 focused native checks across five imports, plus the saved-settings Gemma greeting.
- [x] 76 JVM tests pass; debug/release/test APKs assemble. Lint: zero errors, 53 warnings, two hints. The remaining warnings include dependency/style suggestions and intentionally unused artwork variants.
- [x] Full safe device suite: 50 reported, 42 pass and eight opt-in model skips. Includes appearance, provider contracts, storage, Shizuku, workspace, tool refusal and project-context regressions.
- [x] Real app UI: clone/diff/stage/unstage/commit/failure/deletion on disposable files; rotation draft recovery; Python/npm GitHub server installs; imported skill instructions; RE setup; local-model Filesystem discovery/approval/read/result; Always-allow + Deny behavior.
- [x] Signing identity matches published 1.0; APK signatures/alignment/versions verified. Existing debug users keep the matching debug key. Main app data preserved during install-r upgrades.
- [x] V2/SYNTHESIS artwork integrated and audited; font/Moon provenance and dependency notices retained. Prompt backups, credentials, weights and APKs excluded from Git.
- [x] Store descriptions, version-code changelog, icon/feature graphic and two sanitized device-rendered screenshots prepared.
- [x] Fresh non-debuggable release APK starts under a separate test application ID with the expected setup UI. All 15 native libraries match the canonical release. Temporary test package removed; main installation preserved.
- [x] Final full local F-Droid recipe build/source/APK scans pass. Independent unsigned APKs match SHA-256 `8f81d24c2de95d8240d2dd92155a24b09757411082a35cb40ee99c6bbfc4d063`; copying the original signature produces an identical signed APK. The local run used the installed toolchain and skipped build-server sudo provisioning.
- Publication records: [PR #1](https://github.com/CurrenlyDying/angel-android/pull/1), [CI](https://github.com/CurrenlyDying/angel-android/actions), [v1.1 upstream release](https://github.com/CurrenlyDying/angel-android/releases/tag/v1.1). Merge and release publication follow the verified CI result.

## Confirmed bug tracker

This combines reported bugs with issues reproduced during device and fixture testing. “Verified” refers to the checks listed; it does not imply that every related feature is complete.

| Bug | Status | Verification / remaining work |
| --- | --- | --- |
| DeepSeek-labeled Qwen3.5 import fails on absent optional MTP tensors | Fixed, verified | Exact original file loads, generates and recovers after Stop |
| LFM3 architecture unsupported | Fixed, verified | Exact original file loads/generates; load, prompt and generation cancellation/recovery pass |
| Stop waits for long native prompt decoding; interrupted cache affects following turns | Fixed, verified | Focused native cancellation/recovery checks across Gemma, DeepSeek, LFM3 and Qwen |
| Gemma cold loading repacks its large shared embedding | Improved, verified; still slow | Targeted native buffer selection; greeting works with saved settings, but a cold run still took nearly four minutes |
| Missing/truncated model downloads remain selectable; retry/cancel can share files | Fixed, verified | JVM and device file-state/download ownership regressions |
| Imported model descriptor is reopened without Android’s granted access | Fixed, verified | Borrowed-descriptor closure followed by successful native load and generation |
| Project commands silently fall back when their working directory is missing | Fixed, verified | Device command/terminal fixtures reject the missing directory |
| Git filename/rename handling and unstage before the first commit | Fixed, verified in fixtures | Unusual names, both rename paths and index behavior tested; clone/diff/stage/unstage/commit/rejected-commit/disposable deletion also pass through the UI |
| Skill setup can report success after a failed connector install or use the wrong distro | Fixed, verified in fixtures | Failure propagation, per-distro readiness and owned-connector retry checks; full RE setup also reports tools ready in the UI |
| Installer timeout waits on blocked output readers | Fixed, verified | Real device timeout returns promptly and the next command works |
| Unsaved project skill/connector choices disappear on rotation | Fixed, verified | Reproduced on phone; after fix both toggles survive rotation |
| Keyboard autocorrect changes GitHub clone URLs; invalid URL can create an unusable project | Fixed, verified | URI keyboard/no autocorrection, inline validation; exact repository URL successfully cloned through UI |
| RE connector skips compiler/headers when radare2 already exists | Fixed, verified | Regression test plus real installation of compiler/development dependencies on Kali |
| Latest r2mcp requires an unavailable radare2 API and its installer masks the compile failure | Fixed, verified through app setup | Pinned 1.8.0 builds/runs in direct protocol checks; full app setup now reports tools ready with its connector Ready (45 tools). Direct build stops on errors and checks executable |
| Denying a tool allows the model to continue other commands; Always allow can be persisted by Deny | Fixed, verified in two ViewModel regressions and actual MCP approval UI | Observed a Linux command after a denied MCP call. Denial and blocked policies now stop the current request; persistent Allow is applied only on Run. Added real ViewModel tests with a scripted two-tool batch and isolated storage. Actual Filesystem Always-allow + Deny stops immediately with no further commands |
| GitHub Python installation launches a dependency CLI instead of the server | Fixed, verified through full UI reinstall | Real installation selected cffi-gen-src. Source-directory metadata now selects the project’s own entry point, with a script fallback and three JVM regressions. Fresh UI installation now initializes the real Git MCP server and displays 14 tools |
| Git commit draft disappears on rotation or after a rejected commit | Fixed, verified on device | Rotation and a rejecting hook retain the draft; a subsequent successful UI commit clears it. Unstage preserves the working file; disposable-file deletion also passes |
| “Open in a new chat” retains the conversation when the same project is already active | Fixed, verified on device | `openProjectInNewChat` always starts a fresh chat and persists the old one; scripted-model regression passes on the phone (`ProjectChatContextTest`) |
| Editing the active project during a pending approval can redirect the request's commands into the new directory/distro | Fixed, verified on device | Confirmed by code review (execute() read workdir/distro live), not reproduced on hardware. Requests now capture their shell context at send time; scripted-model regression passes on the phone. MCP discovery, server distro selection and prompt project context now use the request snapshot too; active-project deletion is blocked while busy |
| Local build embeds checkout paths/Git state, preventing binary matching | Fixed, verified | Separate unsigned builds match byte for byte; F-Droid signature copy produces identical signed APK |

New findings will be added here. Unverified suspicions are kept out of this list.

## Explicit coverage limits and follow-up testing

The original exploratory test matrix was broader than the tests completed. These remain follow-up coverage, not evidence of known defects: older ARM64/API-level hardware; prolonged memory pressure/context-limit stress; every network/storage interruption permutation; two-project/distro UI switching beyond the covered execution fixtures; live cloud-provider credentials; hosted HTTP/SSE authentication/reconnection on a live deployment; and a complete release-signed 1.0-to-1.1 user-data upgrade. Provider tests use fake HTTP responses. The ordinary debug upgrade and isolated fresh-release checks do not replace a release-signed data-migration test.

The owned APK was decoded by apktool; jadx emitted a readable MainActivity but reported four global errors. This is partial decompilation, not a clean whole-APK analysis claim. Large Gemma startup remains slow, documented in README.

## F-Droid publication

- Metadata, dependency/artwork licensing review, executable-download consent and proposed Anti-Features are in `fdroid/`.
- [ ] Dedicated F-Droid build-server verification and maintainer review. A successful local recipe build does not complete this step.
- [ ] Submit the packaging request/metadata after the public tag and signed asset exist.
- Development history is under three months. The existing release/users may support the established-user exception; only F-Droid maintainers can decide.

References: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/), [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [reproducible builds](https://f-droid.org/docs/Reproducible_Builds/).
