# Angel release plan

Checkpoint: 2026-11-07. This records the current coverage and proposed release gates; unchecked items are not completed or verified.

## Current position

- Local changes remain uncommitted. Nothing has been pushed during this testing work.
- Debug build passes. The latest JVM results contain 76 passing tests. The final broader device run passed 40 checks with eight opt-in model cases skipped (48 reported), including the three appearance checks and two denial regressions. All 17 focused native checks pass across Gemma, DeepSeek, LFM3 and both Qwen imports. Gemma also passed a greeting with the owner’s saved 16K context, temperature 1.2 and thinking enabled: 84 seconds after loading, 238 seconds for the cold test overall.
- Lint reports no errors, 53 warnings and two hints after V1 assets (including intentionally unassigned capability icons). Triage relevant warnings; dependency upgrades are not automatically required for this release.
- Imported model access, missing download detection, download ownership, inference cancellation and interrupted-cache recovery have fixes and regression coverage. Gemma now answers through the actual chat UI. Cold model loading/prompt processing still takes substantial time. Loading is now cancellable, with verified cancellation/reload recovery.
- Git path handling, missing project directories, installer timeouts and per-distro skill setup have focused coverage. Rotation reproduced lost unsaved project skill/connector selections; those selections now use saved state, verified by rotating again and retaining both toggles. This does not establish that every new feature works through its complete UI workflow.
- Candidate identity is `com.bruh.angel`, version name `1.1`, version code `2`. The app is ARM64-only; the root license is Unlicense. GitHub already has a signed 1.0 release under tag `APK`. Its existing release key was recovered and its certificate verified against the public APK. Fastlane text/icon metadata, changelog, privacy/build docs and a pinned CI workflow are now prepared locally.

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
| Git filename/rename handling and unstage before the first commit | Fixed, verified in fixtures | Unusual names, both rename paths and index behavior tested; final Changes UI workflow ongoing |
| Skill setup can report success after a failed connector install or use the wrong distro | Fixed, verified in fixtures | Failure propagation, per-distro readiness and owned-connector retry checks; full setup UI ongoing |
| Installer timeout waits on blocked output readers | Fixed, verified | Real device timeout returns promptly and the next command works |
| Unsaved project skill/connector choices disappear on rotation | Fixed, verified | Reproduced on phone; after fix both toggles survive rotation |
| Keyboard autocorrect changes GitHub clone URLs; invalid URL can create an unusable project | Fixed, verified | URI keyboard/no autocorrection, inline validation; exact repository URL successfully cloned through UI |
| RE connector skips compiler/headers when radare2 already exists | Fixed, verified | Regression test plus real installation of compiler/development dependencies on Kali |
| Latest r2mcp requires an unavailable radare2 API and its installer masks the compile failure | Fixed, verified through app setup | Pinned 1.8.0 builds/runs in direct protocol checks; full app setup now reports tools ready with its connector Ready (45 tools). Direct build stops on errors and checks executable |
| Denying a tool allows the model to continue other commands; Always allow can be persisted by Deny | Fixed, verified in two ViewModel regressions and actual MCP approval UI | Observed a Linux command after a denied MCP call. Denial and blocked policies now stop the current request; persistent Allow is applied only on Run. Added real ViewModel tests with a scripted two-tool batch and isolated storage. Actual Filesystem Always-allow + Deny stops immediately with no further commands |
| GitHub Python installation launches a dependency CLI instead of the server | Fixed, verified through full UI reinstall | Real installation selected cffi-gen-src. Source-directory metadata now selects the project’s own entry point, with a script fallback and three JVM regressions. Fresh UI installation now initializes the real Git MCP server and displays 14 tools |
| Git commit draft disappears on rotation or after a rejected commit | Fixed, verified on device | Rotation and a rejecting hook retain the draft; a subsequent successful UI commit clears it. Unstage preserves the working file; disposable-file deletion also passes |
| “Open in a new chat” retains the conversation when the same project is already active | Open, reproduced | Explicit action calls setActiveProject, whose same-project early return keeps the old history. See HANDOFF.md for the next fix |
| Local build embeds checkout paths/Git state, preventing binary matching | Fixed, verified | Separate unsigned builds match byte for byte; F-Droid signature copy produces identical signed APK |

New findings will be added here. Unverified suspicions are kept out of this list.

## Recent additions and their remaining acceptance tests

| Feature | Coverage so far | Remaining acceptance test |
| --- | --- | --- |
| Projects and workspace context | Directory browser opened on device; working directories and persistence helpers tested | Create/edit/delete projects (creation and repository clone now pass); switch between two distros and directories; verify chat instructions, attached skills and connectors stay with the correct project; restart and fold/unfold during edits |
| Repository clone and Changes | Exact Git filenames, rename handling and staging helpers tested in isolated repositories | Clone a public repository through the UI; inspect diffs, stage/unstage and commit; exercise discard only on disposable fixtures; verify failure/retry and missing Git behavior |
| Skills and Reverse Engineering template | Parsing, state and setup failure/retry helpers tested | Import SKILL.md; attach/detach it and verify actual prompt behavior; successfully install the RE toolchain and optional connector; analyze a small owned test APK; check partial success is reported accurately |
| MCP installation from GitHub | URL/recipe generation and failure paths tested | Install and start one real npm server and one real Python server; branch/subdirectory selection, install logs, startup errors, interruption and retry |
| On-demand MCP discovery for local models | Fixture coverage plus actual LFM3 → discovery → Filesystem approval → real file read → exact result in final answer, with project instructions retained | Finish project switching and subsequent turns; Always-allow + Deny UI check and denial/block batch regressions pass |

On-demand MCP discovery is the newest addition made during stabilization. Its purpose is to keep large connector schemas out of every local-model greeting.

## Ordered work

### 0. Resolve the reported DeepSeek and LFM3 load failures

- [x] DeepSeek GGUF: reproduced `check_tensor_dims: tensor 'blk.32.attn_norm.weight' not found`. The file declares 32 trunk layers plus an absent optional MTP layer. Upstream llama.cpp v0.8.0 handles this case; the exact original file now loads and generates on the phone without modification.
- [x] LFM3 GGUF: reproduced `unknown model architecture: 'lfm3'`. Upgraded the complete pinned upstream snapshot from v0.5.0 to v0.8.0, which supports LFM3; the exact original file now loads and generates on the phone. Source URL, SHA-256 and trimming are recorded in `app/src/main/cpp/llama.cpp/VENDORED.md`; upstream source is unmodified.
- [x] Re-test both exact files on the phone: successful load, a short reply, Stop and the next reply. Re-run Gemma/Qwen regressions after any native-runtime change. Keep either model issue open if inference is not demonstrated.
- Native loading now reports weight-loading progress and supports cancellation; Gemma's load cancellation returned Idle in 473 ms. A targeted CPU buffer override avoids repacking Gemma 4's large Q6_K shared token/output embedding, with other matrix optimizations retained. Gemma passed greeting, prompt Stop, generation Stop/two following turns, and load cancellation/reload (four focused device checks). All 17 focused checks passed: Gemma (4), DeepSeek (4), LFM3 (4), Qwen3-0.6B (4) and Qwen3.5 coder (1). Measured load cancellation was 329–473 ms; prompt/generation cancellation was 3–12 ms. These are instrumented checks with bounded test settings; final UI behavior at the owner’s saved settings remains to be checked.

### 1. Check F-Droid feasibility early

- [ ] Finish the model/catalog review. V1 artwork provenance is checked: source hashes match; font files retain OFL notices; the Moon credit points to its NASA SVS source and public-domain reuse FAQ. V1 branding passes the source scan. Prompt backup files are excluded from publishing.  Checked all 516 retained upstream source files against the pinned tarball (no differences). Inspected published POM licenses for the release dependency graph and retained JVM/native license and notice texts, including XZ 0BSD and OkHttp’s MPL-2.0 Public Suffix List. Bundled artwork credits are included. Downloaded model weights and executable tools need a separate review from the app’s own Unlicense.
- [x] Audit every built-in executable download path: Linux setup, package managers, Node, MCP recipes and automatic RE setup. Ensure informed opt-in, an equally easy decline path, and an explanation that downloaded code bypasses F-Droid checks. Inspect existing prompts before deciding which UI changes are needed.
- Installer UI audit found automatic RE setup lacked a review step and existing dialogs lacked the bypass disclosure. Shared disclosure and explicit review are implemented for Linux, MCP, skill setup and Git installation flows; clone/GitHub/skill review dialogs have been exercised on device; final wording and remaining setup checks continue.
- [x] Assess applicable Anti-Features. The draft proposes NonFreeNet for cloud/hosted services and NonFreeAdd for restricted-license model suggestions; provenance/download review is in `fdroid/REVIEW.md`. Final labels remain subject to maintainer review.
- [x] Check development-history eligibility. The visible repository history is roughly one month old; F-Droid normally asks for three months of active development, with an established-user exception. The existing public release may support an exception; maintainer acceptance is pending.
- [ ] Verify the pinned Gradle/AGP/JDK/SDK/NDK/CMake combination can be provided in the F-Droid build environment.

References: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/), [Anti-Features](https://f-droid.org/docs/Anti-Features/). Eligibility and labeling remain subject to maintainer review.

### 2. Finish the new feature workflows

- [ ] Complete every acceptance test in the table, starting with successful RE setup and real GitHub MCP installations.
- [ ] Fix confirmed bugs and retain focused regressions for meaningful failures. Reproduced the optional radare2 connector failing to build because the installed core tools included r2pm but no compiler/Make/headers. Its setup now independently checks and installs build dependencies, with a shell-script regression. The upstream installer also masked an incompatible latest-source compile failure; setup now builds pinned r2mcp 1.8.0 directly, stops on build errors and checks the executable. The pinned build runs and completed MCP initialization/tool listing (32 tools) on Kali; the complete app setup UI now succeeds and reports tools ready.
- [ ] Verify tool approvals, edited arguments, denial, cancellation and output handling through the complete chat UI. Existing provider contracts use fake HTTP responses; the local tool-format test supplies a canned result.

Python/npm GitHub installs, RE setup and attaching a pasted skill now pass through the UI. See `HANDOFF.md` for the latest evidence and the remaining new-chat bug/project-edit context concern.

Gate: every advertised new workflow has a demonstrated successful path and understandable failure/retry behavior.

### 3. Exercise lifecycle and failure cases

- [ ] Local models: cold/warm chats, stop during loading/prefill/generation, repeated model switching, long conversations/context limits, background/foreground, memory pressure and unsupported model errors.
- [ ] Model files/downloads: revoked or moved SAF imports, deleted/truncated files, disk space, network interruption, cancel/retry and restart while downloading. Publish a small compatibility list based on models actually exercised.
- [ ] First-use behavior without Shizuku permission or an installed distro; denied permission, service death/reboot and recovery. Check offline use of an already imported model.
- [ ] Real cloud-provider smoke tests using credentials entered on the device; authentication errors, network failures and tool results. Verify hosted MCP authentication/reconnection over both supported transports.
- [ ] Fold/unfold, background restoration, project editor drafts, large font scaling and narrow layouts. Add an older ARM64/API-level check before retaining broad Android 8+ compatibility claims.
- [ ] Fresh install and upgrade with representative saved profiles, projects, skills and conversations. Use isolated installations/fixtures; preserve the owner's phone data.

Gate: no unresolved crashes, hangs, lost data, wrong-project execution or approval bypasses. Remaining compatibility limits are documented.

### 4. Produce and verify the release build

- [ ] Build from a clean checkout with documented portable toolchain setup; verify native code remains optimized in every variant.
- [x] Add CI for JVM tests, lint and debug/release assembly. Keep device tests as a separately documented job with their prerequisites.
- [ ] Inspect the final release APK and test release-mode startup, inference, Shizuku, Linux execution and an upgrade. Review relevant lint findings, dependencies, secrets, generated artifacts and debug logging.
- [x] Select the next release version/code and recover the existing signing identity. Version 1.1/code 2 is configured; a signed candidate verifies against the published 1.0 certificate. The signing helper reads external password files without adding secrets to Gradle or Git. An offline key backup remains the owner’s follow-up.
- [x] Configure upstream-signed reproducible APKs using the existing release key. Independent local unsigned builds match byte for byte and F-Droid signature copying produces an identical signed APK. The current debug key also matches the published 1.0 debug APK. Dedicated F-Droid build-server verification remains part of submission.

Reference: [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/).

### 5. Prepare the GitHub update

- [ ] Update README/build instructions and limitations to match verified behavior; correct any test descriptions that imply live service or full UI coverage.
- [ ] Add a changelog, privacy/data-flow explanation and third-party notices. Explain cloud transmission of prompts/tool output, local storage, Shizuku privileges and that proot is not a security sandbox.
- [ ] Add store descriptions, icon, sanitized screenshots and version-code changelog under `fastlane/metadata/android/en-US/`.
- [ ] Review the complete tracked and untracked change set, including local-only editor/assistant files; split it into coherent commits.
- [ ] Re-run the agreed checks for the final candidate, push the reviewed commits, and publish a version tag and signed GitHub release.

Gate: GitHub release ready once feature/lifecycle acceptance and release-build checks pass. F-Droid review can continue separately if packaging or eligibility takes longer.

### 6. Submit to the main F-Droid repository

- [x] Prepare `fdroid/com.bruh.angel.yml` and validate a copy in a separate fdroiddata workspace. It targets the proposed `v1.1` tag; that tag and release asset are not public yet.
- [ ] Finish the full F-Droid recipe build and resulting-APK checks. Metadata formatting/lint, the source scan (zero fatal findings) and candidate APK scan already pass with fdroidserver 2.4.5. Removed the automatic JDK downloader and pinned installed JDK 21; SDK 37.0, build tools 36.0.0, NDK 30.0.16248370 and CMake 4.1.2 build locally. Unsigned APKs rebuilt in separate checkout directories now match byte for byte, and copying the original signature onto the rebuild produces an identical, verifiable signed APK. A complete local F-Droid recipe run using a source-only Git fixture and the installed toolchain passed source and APK scans and built successfully. It must be repeated for the final source after remaining bug fixes; the dedicated build-server environment remains unverified.
- [ ] Submit a Request for Packaging or fdroiddata merge request, then handle maintainer feedback. Inclusion/publication happens after their review and build pipeline.

References: [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [fdroiddata contributing guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md).
