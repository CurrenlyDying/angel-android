# Angel 1.1 stabilization handoff

Updated 2026-11-07. Read this first when continuing in GitHub Copilot, Claude or Kimi without the original computer/phone. This branch is work in progress, not a published release. See [RELEASE_PLAN.md](RELEASE_PLAN.md) for the full tracker and [BUILDING.md](BUILDING.md) for commands.

## Objective and constraints

Prepare the owner's existing Projects/Skills/GitHub-MCP changes, the local-model fixes and V1 artwork for a GitHub update, then F-Droid submission. Preserve the owner's work and phone data. Fix confirmed bugs; distinguish a code review suspicion from a reproduced failure.

- Candidate: `com.bruh.angel`, version `1.1`, code `2`, ARM64, min API 26, target API 37. Current public 1.0 release uses tag `APK`; the original source baseline is commit `8024a87`.
- The supplied V2 editable identity (initially called V1 in chat) is integrated into launcher resources, chat branding and fastlane. Keep editable SVG/ORA masters, font licenses and provenance. The six capability icons are intentionally not assigned to existing controls yet.
- **Do not publish artwork prompt backups.** The owner specifically excludes the text prompts used to create the vector synthesis pipeline. Ignore rules cover prompt files in branding/app assets. The owner identified the actual source as `/home/puto/codebase/cws/angel-branding/v2/`; comparison confirmed it was already incorporated. No prompt backups were found in that folder or the publishable tree. The selected wording is SYNTHESIS; alternate wordmarks remain source alternatives only. See `branding/angel-identity/review/INTEGRATION.md`. Do not remove the app's intentional runtime `assets/skills/reverse-engineering/SKILL.md` as a supposed backup.
- Never expose API keys, passwords or private keys. No signing material belongs in GitHub. No paid/live cloud-provider checks have been claimed.
- Do not run `connectedDebugAndroidTest` against the owner's app: connected cleanup can uninstall it and invalidate encrypted credentials. Use `install -r` and direct instrumentation. Do not uninstall/clear the main app to resolve a signature mismatch.
- Vendored llama.cpp is an unmodified pinned upstream snapshot. Put app adaptations in `angel_llama.cpp`/Kotlin/CMake outside the vendored tree. Native code must stay optimized in debug builds too.

## Completed work and evidence

Testing used a Pixel 10 Pro Fold, Android API 37, SDK ADB over Wi-Fi, and Shizuku as uid 2000. This access is **not available to a GitHub web agent**. The following results are recorded observations, not instructions to rerun inaccessible hardware.

| Area | Observed result |
| --- | --- |
| JVM/build | 76 tests pass; debug app/test APK assembly passes. Latest full lint: zero errors, 53 warnings, two hints (including unused V1 resource variants) |
| Broader device suite | Earlier run: 43 reported = 35 passed + eight opt-in skips, excluding three appearance tests. Two new real-ViewModel denial regressions also pass separately. Final combined rerun passes: 48 reported = 40 passed + eight opt-in skips, including all three appearance checks and both denial regressions |
| Local inference | 17 focused native tests pass: Gemma 4, DeepSeek-labeled Qwen3.5, LFM3, Qwen3-0.6B and Qwen3.5 coder imports. Exact owner files load and generate; borrowed-FD closure, Stop and following-turn recovery are covered |
| Cancellation | Instrumented load cancellation 329–473 ms; prompt/generation cancellation 3–12 ms. These bounded test settings do not promise identical timing for every model/device |
| Gemma at saved settings | Greeting passes with 16K context, eight threads, temperature 1.2, thinking enabled, 4096 reply tokens. Reply took 84 seconds after loading, 238 seconds for the whole cold test. Large-model startup remains slow |
| Local tools | Actual LFM3 chat discovers Filesystem tools, requests approval, reads a disposable file and returns its exact marker. Project instructions remain in the answer |
| Approval | Deny stops the entire request/batch. Always allow + Deny does not persist Allow. Two isolated ViewModel regressions and actual Filesystem UI verification pass |
| Project/Git UI | Clone succeeds with exact URL; unsaved skill/connector choices survive rotation; stage/unstage preserves edits; diff shows staged and unstaged versions; rejected commit and rotation preserve draft; successful commit clears it; deleting a disposable untracked file succeeds |
| GitHub MCP | Fresh UI installations from `modelcontextprotocol/servers`, branch `main`: Python `src/git` starts with 14 tools (server 2026.11.4); npm `src/sequentialthinking` starts with one tool |
| Skills/RE | Pasted a disposable SKILL.md, attached it, and actual LFM3 answer obeyed both project and skill markers. Detachment was saved; the model's subsequent answer was verbose, so absence behavior is not established as a complete acceptance pass. RE setup through UI completes, reports tools ready, and its new radare2 connector is Ready with 45 tools |
| Owned APK analysis | apktool decodes the test APK. jadx emits a readable MainActivity, but reports four global errors/exit 1: partial success, not a clean full-APK decompilation claim |
| F-Droid/reproducibility | Metadata lint, source scan (zero fatal findings, including V1 artwork), APK scan and a full local recipe build pass with fdroidserver 2.4.5. At an earlier source checkpoint, independent unsigned APKs match byte for byte and copying the original signature recreates the exact signed APK. Repeat for final source; dedicated F-Droid build server remains unverified |

### Fixes to understand before editing

- Full llama.cpp upgrade v0.5.0 → **v0.8.0/b11960**, commit `8642541b255bfb5dc39cd351a178be09013a55d6`. Provenance is in `app/src/main/cpp/llama.cpp/VENDORED.md`; all 516 retained upstream files matched its tarball. Upstream handles absent optional Qwen3.5 MTP tensors and supports LFM3. The DeepSeek filename did not identify its actual `qwen35` architecture.
- JNI now uses the updated chat/parser APIs, preserves generated token IDs, owns a duplicate of imported document descriptors, reports load/prompt progress, and supports native abort/cache recovery. A targeted CPU buffer override avoids expensive repacking of Gemma 4's Q6_K shared embedding; other matrix optimizations remain. The owner's Gemma file is mixed quantization, named Q4_K_M, not an entirely Q6 model.
- Missing/truncated downloads are unavailable; each download owns a unique file. Imported SAF files load from Android's granted descriptor instead of trying to reopen a forbidden filesystem path.
- Project commands/terminals reject missing working directories. Git parsing preserves exact names and both rename paths; initial-commit unstage works. Installer readers/timeouts cannot hang indefinitely.
- RE setup independently verifies compiler/Make/pkg-config/headers even when r2pm already exists. Latest upstream r2mcp failed against the installed radare2 API, and r2pm masked the compile failure. The recipe now builds **r2mcp 1.8.0**, verifies commit `ad8e484a769b42c50737a6a97dbbaac5061f5ee0`, stops on errors and checks its executable. Keep this compatibility pin until a newer pair is tested.
- GitHub Python setup previously launched the first venv executable (`cffi-gen-src`, a dependency). `McpRecipes.githubPythonStartSelector` uses pip's source-directory metadata to choose the project's own console entry, with a quoted script fallback; three JVM regressions cover this.
- Tool refusal throws an internal stop signal in `ChatViewModel`; persistent Allow is written only after affirmative approval. `ToolApprovalTest` exercises the real loop with a scripted model and isolated storage, without calling a cloud API.
- Reproducibility uses JDK 21 (no automatic downloader), native prefix maps/`-g0`, pinned native version headers and release `vcsInfo.include=false`. The OpenMP build was retaining absolute debug paths. Do not re-enable these accidentally.

## Pick up here: priorities for an agent without ADB

1. **Branding check completed:** V2 resources, manifests and store assets match; SYNTHESIS is the owner-selected wording. No prompt backups are included. Preserve this choice and the original editable masters in subsequent work.
2. **New-chat bug fixed in source:** `ChatViewModel.openProjectInNewChat` now backs the project editor's “Open in a new chat” button: it persists the old chat and always starts a fresh one, even when that project is already active (`setActiveProject`'s same-project early return was the bug). Busy guards are retained. Regression: `ProjectChatContextTest.openInNewChatWithActiveProjectStartsFreshChatAndKeepsOldOne` (instrumented, scripted model, isolated storage). CI passed (JVM tests, lint, debug/release + test-APK assembly), which compile-checks the test, but the regression itself has **not been executed on a device yet**.
3. **Mid-request project-edit context concern addressed in source:** a request now captures its shell `RequestContext` (distro + workdir) at send time and `runTool`/`execute` use it, so saving a project edit while a tool awaits approval can no longer redirect that request's commands. A `shellExecutor` seam allows tests to record the context a command runs in. Regression: `ProjectChatContextTest.editingActiveProjectDuringPendingApprovalKeepsOriginalExecutionContext` — instrumented, CI-compile-checked, **device run pending**; on the pre-fix code it would record the redirected directory/distro. Remaining scrutiny (unverified, lower risk): MCP tool calls still resolve their server/distro through `McpManager`'s live lambdas, and deleting the active project mid-request leaves `activeProject` stale until the next refresh. Neither has been reproduced as a user-facing failure; shell execution was the confirmed vector.
4. Use GitHub Actions (`.github/workflows/android.yml`, enabled for `release/**`) or an actual build runner to run JVM tests/lint/assembly. A web chat without execution access must not claim it ran Gradle. No need to repeat the hours of native model tests unless native/runtime behavior changes.
5. Review the complete diff and docs. The owner-added features and our fixes are substantial. Remaining acceptance includes two-project/distro context isolation, hosted HTTP/SSE authentication/reconnect, fresh/upgrade release-mode smoke and older API compatibility. Cloud-provider tests use fake responses; no live credentials are available to the web agent. Mark hardware-dependent work explicitly for an owner/device runner.
6. After remaining fixes, repeat final clean release/F-Droid build, APK/source scans and independent rebuild/signature-copy comparison. Prior APK hashes are **stale candidates**, not hashes to announce as the final release. Review `RELEASE_PLAN.md`, then prepare coherent final commits/tag/release.

Do not mark the candidate release-ready merely because compilation passes. Do not create a public version tag or publish an APK/F-Droid submission while blockers above remain.

## Build, signing and F-Droid

Toolchain: OpenJDK 21, Gradle 9.6.0, AGP 9.4.1, SDK **`platforms;android-37.0`** (not `android-37`), build-tools 36.0.0, NDK 30.0.16248370, CMake 4.1.2. `BUILDING.md` contains portable install/test commands. CI signs nothing.

The owner recovered the original release keystore locally and verified it against public 1.0. It is **not accessible in a GitHub web chat**. The public signer SHA-256 is `773df628227f9ee4d2e24e7505f0f3b68a65b4eb97cafe467dcf366c58d11058`. `scripts/build-release.sh` takes external file paths through environment variables and verifies that certificate before publishing a local artifact. Ask the owner to run signing locally; never generate a replacement production key or request passwords in chat. The existing debug key also matches published 1.0 debug; preserve a matching debug update path for those users.

`fdroid/com.bruh.angel.yml` is a **draft** for proposed tag `v1.1` and the matching GitHub release asset. `fdroid/REVIEW.md` records licenses, executable-download opt-in, proposed `NonFreeNet`/`NonFreeAdd` labels and eligibility. Native/JVM/artwork notices are bundled and viewable in Settings. F-Droid's usual three-month development-history requirement may need its established-user exception. Local build success is not F-Droid acceptance. Do not advertise the app as available there yet.

## Phone-only follow-up

The original phone installation and user models/profiles/conversations were preserved. Local model selection to preserve is **LFM3 Q6_K**; saved settings are 16K context/eight threads/temperature 1.2/max 4096, thinking **enabled**. Thinking was temporarily disabled for tool tests and restored before the final combined device run. Rotation was restored to its original setting.

Disposable fixtures have explicit `Release QA` names: project `Release QA A`, imported skill `Release QA marker`, servers `QA Git Python`/`QA Git Npm`, an owned Linux project folder and a shared Download subfolder. Do not delete original `Reverse Engineering`/`test` projects, original MCP configurations or unrelated downloads. RE setup added a working connector beside an older uninstalled radare2 configuration; review that duplication separately rather than deleting the owner's original configuration. The local handoff in ignored `.codex/release-handoff-local.md` has exact fixture paths and log locations for an agent on the original computer.

## Final combined device rerun

The final safe instrumentation run completed in 66.8 seconds: **48 reported, 40 passed, eight opt-in skips, zero failures**. It includes appearance rendering/settings, provider contracts, storage/security, Shizuku, workspace fixtures and both denial regressions. APKs were installed with `adb install -r`; app data was not cleared. No model-specific instrumentation options were passed, so this does not repeat the 17 separate native checks. The debug app/test APKs contain the latest setup-disclosure wording; that wording-only change followed the last 76-test/lint run.
