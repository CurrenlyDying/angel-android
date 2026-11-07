# Angel — Android agent

Angel is a native Kotlin/Compose assistant for ARM64 Android 8+ phones, currently tested on a Pixel 10 Pro Fold. Run GGUF models locally or connect a cloud provider, then use phone/Linux tools, projects, skills and MCP connectors from chat. Privileged actions go through Shizuku as the ADB shell user (uid 2000), without root, `su` or bootloader changes.

[Download the latest release](https://github.com/CurrenlyDying/angel-android/releases/latest) · [Privacy](PRIVACY.md) · [Build instructions](BUILDING.md)

Choose the release APK for the release signing identity, or the debug APK if updating an existing debug installation. Use the matching variant to preserve app data; the two signatures are different.

## Feature guide

This inventory covers the app's user-facing screens, settings and agent capabilities. Availability depends on the selected model, Shizuku permission, installed Linux environment and configured connectors. The [tested models and compatibility](#tested-local-models-and-compatibility) section distinguishes implemented features from hardware/service coverage.

| Area | Where to find it | Capabilities |
| --- | --- | --- |
| [Models and providers](#models-and-providers) | Settings → Model | Four cloud providers, separate saved profiles, model/key checks and local inference |
| [On-device models](#on-device-models) | Settings → On-device → Manage local models | Hugging Face search/download, GGUF import, model management and inference tuning |
| [Chat and responses](#chat-and-responses) | Chat | Starter prompts, Markdown, streaming local replies, progress, copy and Stop |
| [Tools and approvals](#tools-and-approvals) | Chat / Settings → Tool permissions | Android shell, Linux shell, screen reading, editable approvals and command cancellation |
| [Linux environments](#linux-environments) | Settings → Linux environment | Alpine, Debian, Ubuntu and Kali installation, selection, testing and removal |
| [Projects](#projects) | Workspace options → Projects | Chat workspaces with directories, distros, instructions, skills and connectors |
| [Git Changes](#git-changes) | Project → View changes | Clone/init, status/diffs, stage/unstage, discard/delete and local commits |
| [Skills and reverse engineering](#skills-and-reverse-engineering) | Settings → Skills / Projects | SKILL.md import, project attachments and RE toolchain/template |
| [MCP connectors](#mcp-connectors) | Settings → MCP servers | Featured/registry/GitHub installs, hosted servers, custom commands and tool controls |
| [Terminal](#terminal) | Workspace options → Terminal | Separate persistent Android/Linux sessions, history, control keys and output tools |
| [Conversation history](#conversation-history) | Conversations icon | Search, date groups, resume, share, individual deletion and bulk deletion |
| [Appearance](#appearance) | Settings → Appearance | Theme, accent, materials, reading/accessibility controls and editable artwork |
| [Privacy and data controls](#privacy-and-data-controls) | Settings / installation dialogs | Encrypted credentials, local storage, download consent and license notices |

### Models and providers

- Use **OpenAI, Google Gemini, Anthropic or DeepSeek** through their native APIs, or select an **on-device GGUF model**. Agent tools and MCP connectors are available to cloud and local models; the model's ability to use them varies.
- Basic text chat does not require Linux or Shizuku; phone/shell tools require Shizuku, and Linux tools also need an installed distro.
- Each cloud provider keeps its own model ID and encrypted API key. Switch providers without re-entering the other profiles; type a model ID directly or choose from **Test key & list models**. The check lists accessible models without sending a chat/completion request.
- Show/hide the key, save or discard model/key edits, and handle the save/discard/keep-editing prompt when leaving. Drafts survive fold/unfold. Switching the provider saves the selection immediately.
- Model/key mismatch hints can suggest the appropriate provider and offer **Save these as … instead**. Known retired DeepSeek IDs are migrated to the configured replacement. API errors include the provider's reason with key redaction.
- Save global custom instructions (up to 4,000 characters). New model contexts also receive device/Android details, local time, Shizuku/Linux status and the active project's context. Tap the chat header to open settings or complete setup.

### On-device models

- Search **Hugging Face** by query, use suggested searches, or enter an `owner/repo` ID/repository URL. Open a repository's GGUF list; inspect quantization, file size and RAM-fit warnings before confirming a download.
- Download in the background through Android DownloadManager, with progress and notifications. Downloads follow Android's metered-network policy; **Allow downloads over mobile data** enables metered transfers. A saved encrypted Hugging Face token supports gated repositories after their license/access requirements are met; the token is sent to huggingface.co, not the download CDN.
- Check the GGUF header, expected size when known and published SHA-256 when available. The model list shows downloading, verifying, ready and failed states; downloads can be cancelled and failed entries removed. Missing/truncated downloaded files are marked unavailable, and separate download files isolate retry/cancel operations.
- **Add a .gguf file from this phone** through Android's document picker. Imported files load in place through the granted descriptor, without copying their weights. Removing an imported entry leaves its original file; removing an app-owned download deletes its file.
- **Use** chooses the model and local provider. The first message loads it; it stays in RAM between turns until unloaded/replaced. The model screen also shows engine state, load errors and native backend information. **Unload** frees the loaded model. Loading, prompt reading and generation have separate progress/status feedback; **Stop** can interrupt them and resets interrupted cache state for a later reply.
- Tune context size (**2K/4K/8K/16K**), CPU threads (**Auto/2/4/6/8**), reply token limit (**512/1024/2048/4096**), temperature (**0–1.5**) and **Let reasoning models think first**. Context/thread changes apply when the model next loads.
- Inference uses **llama.cpp v0.8.0**, CPU-only, with runtime ARM kernel selection (including dotprod, i8mm and SVE2 variants). Each GGUF's chat template and native tool format are used; responses stream and prompt state is reused between completed turns. Local models discover MCP tools on demand to keep connector schemas out of ordinary greetings.

### Chat and responses

- A readable conversation column adapts to unfolded screens. The empty-chat setup card links to missing model/Shizuku setup and optional Linux installation.
- Four starter tasks cover battery checks, storage use, reading the screen and building in Linux. Tapping one fills the composer so its text can be reviewed/edited before sending.
- Render selectable Markdown: headings, paragraphs, ordered/unordered lists, block quotes, bold/italic, inline code, fenced code blocks, pipe tables and HTTP(S) links. Copy an entire agent response or a code block.
- Follow loading/prompt/generation activity and grouped consecutive tool calls with per-step status and a live output tail. Open a step to inspect its command/arguments and output, toggle wrapping, copy the command/output or share output through Android.
- Reading earlier messages does not force a jump to each new step; **Latest** returns to the end. **New chat** starts a fresh conversation and preserves a nonempty prior chat. **Stop** ends the current agent request; completed command side effects are not undone.

### Tools and approvals

| Built-in tool | What it exposes |
| --- | --- |
| `android_shell` | One Android command as Shizuku's ADB shell user (uid 2000), such as package/device queries, app launching, settings changes or `input tap/swipe/text` |
| `linux_shell` | One command in the selected/project Linux distro and working directory, with phone storage mounted at `/sdcard` |
| `read_screen` | A compact UI-hierarchy summary: text, accessibility descriptions, element IDs/state and tap coordinates; an optional text filter narrows the result |

- Configure each built-in tool as **Ask / Allow / Deny**. Ask opens a review sheet; Android/Linux commands and MCP JSON arguments can be edited before **Run**. The screen-reading sheet displays its filter.
- **Always allow** applies only after Run is approved. **Deny and stop**, dismissing an approval, or a blocked tool ends the current request and its remaining calls. MCP servers have their own Ask/Allow/Block policy and individual tool switches.
- **Kill command** cancels the active tool call while allowing the agent to continue with its cancellation result. **Stop** cancels the whole request. Activity cards distinguish running, successful, failed, denied and cancelled work.
- Connect/grant Shizuku and run Android/Linux smoke tests from Settings. No real root, `su` or bootloader changes are required. Native command access remains as powerful as ADB; manual Terminal commands run directly without agent approval.

### Linux environments

- Install **Alpine, Debian, Ubuntu or Kali** side by side. Settings selects the default environment; a project can select its own distro. Installed environments keep separate files, with Linux home at `/root` and shared phone storage at `/sdcard`.
- Review download sources and size, explicitly **Download and verify**, follow installation progress, and **Test** the environment. Rootfs/runtime hashes and archive paths are checked; failed activation is rolled back.
- Open the terminal, reinstall a selected distro, or remove it with confirmation. Removal deletes that distro's files; other distros and Android data are separate. These environments can survive app uninstall, so remove them in Settings when appropriate.
- Linux uses ARM64 **proot**, which simulates root and is not a security sandbox. Kali provides security packages but cannot supply real-root features such as raw sockets, monitor mode or packet injection.

### Projects

- Create/edit a named project with a working directory, Linux distro, instructions, attached skills and selected MCP connectors. **General** holds conversations without a project. Project rows show their directory, distro and chat count.
- Browse Linux folders, jump to `/root` or `/sdcard`, go up a directory, create a folder and choose it as the workspace. Missing working directories are reported instead of silently executing somewhere else.
- Add an optional GitHub repository when creating a project; review the clone action and any required Git installation. **Open in a new chat** starts empty even for an already-active project, while opening its saved chat restores that project's context.
- Project instructions augment global instructions, and its directory/distro apply to Linux commands and the terminal. A running request keeps its original directory, distro, project prompt and MCP connector selection after project edits; its active project cannot be deleted until the request stops.
- Delete a project with confirmation: its chats return to General and its workspace files remain. Unsaved skill/connector selections survive rotation.

### Git Changes

- Clone a GitHub repository into a project (`--depth 1`), install Git on demand, or **Initialize repository** for an existing workspace. Clone/setup feedback and readable errors are shown.
- **View changes** separates staged, unstaged and untracked files. Refresh/retry status, inspect a file's staged or working-tree diff, and stage or unstage it. Exact filenames and both rename paths are preserved; unstage also works before the first commit.
- Discard tracked changes or delete an untracked file with explicit confirmation. Commit staged files with a message; the draft survives rotation or a failed commit and clears on success.
- These are **local Git operations**. Branch management, pull/push, merges and other Git commands can be performed through the Linux terminal/agent shell; there are no dedicated buttons for them.

### Skills and reverse engineering

- Import a skill by pasting a **SKILL.md** with a leading front-matter block containing `name:` and `description:`, followed by its instructions. View skill instructions, enable/disable skills globally, attach/detach them per project and delete imported skills. Deleting an imported skill detaches it from projects; built-ins cannot be deleted.
- Only enabled, attached skill bodies are injected into project model contexts, up to **five** bodies; imported bodies are capped at **8,000 characters**. A skill provides instructions, not automatic execution of every command it describes.
- **Set up tools / Re-run setup** reviews and installs a built-in skill's CLI tools and MCP connector into the active environment. Readiness is tracked per distro, failed connector installs do not count as ready, and retries reuse the skill's connector configuration where applicable.
- The built-in **Reverse Engineering** skill and **New Reverse Engineering project** template provide a Kali-oriented workflow for inspecting authorized Android APKs and native binaries: locate/pull APKs through Shizuku, inspect manifests/permissions/strings, decode with apktool, decompile with jadx and analyze with radare2/binwalk/file. APK-tool availability depends on distro packages; Kali includes apktool/jadx, while Alpine setup omits them. RE setup also builds a pinned radare2 MCP connector. Tools can report partial analysis/decompilation failures; the instructions call for evidence and limitations in the report.

### MCP connectors

- **Browse** combines a featured catalog with search of the official MCP Registry, retry/error feedback and **Load more** pagination. Featured entries include **Fetch, DuckDuckGo search, Filesystem, Memory, Sequential Thinking, Time & timezones and Context7 docs**. Available tools and required credentials vary by server.
- Install featured/registry **npm or PyPI** packages into Linux, adding Node/Python dependencies if missing. Review package/version, sources and inputs first; entries supporting both local installation and hosting offer that choice.
- **Install from GitHub** accepts a repository URL, optional display name, **branch/tag and subdirectory**. It clones, detects npm or Python, installs/builds dependencies and generates a launch script. The detected entry point appears in the installation log; there is no dedicated post-install command editor.
- **Connect to hosted server** supports **Streamable HTTP and legacy SSE**, optional bearer tokens and custom headers. Bare private hostnames/IPs are normalized to HTTP; public endpoints require HTTPS. Supported private addresses include LAN, localhost, Tailscale and `.local`; Android's local-network permission is requested when needed.
- **Custom command** accepts a stdio launch command for Linux or the Android shell, an optional Linux install script and environment variables. Use this form when a server needs a custom launch configuration.
- Inspect installed server state, tool count/descriptions, installation/startup errors and recent logs. Enable/disable servers, restart, install/reinstall, cancel installation, edit encrypted environment/header credentials, and remove servers. Ready/failed servers restart after credential changes. Catalog removal also removes its installed files; custom/GitHub server removal removes its Angel configuration.
- Choose each server's **Ask me / Allow / Block** policy and switch individual tools on/off. Projects select their connector subset. Installed Linux servers retain the distro in which they were installed. Local models use capability discovery to expose up to four matching connector tools at a time; cloud models receive the enabled tool definitions.

### Terminal

- Separate persistent **Android** and **Linux** shell sessions survive fold/unfold and navigating away while the app remains alive. The Linux session follows the selected/project distro and directory; changing either restarts that session in its new context.
- Type commands directly or prefill the input from Android/distro-specific quick-command chips. Use command-history **↑/↓**, **Ctrl-C**, **Ctrl-D**, **Tab** insertion and convenience keys for pipes/paths. Ctrl-D closes stdin; **Restart session** opens a new shell.
- Select/copy output or **Copy all output**, clear the screen, change text size and jump back to the latest output after scrolling. Typed lines are visually distinguished from shell output, and a status indicator shows offline/starting/live/stopped state.
- Sessions are pipes, not a TTY. Full-screen programs and interactive password prompts are unsupported; Tab inserts a tab rather than providing terminal completion. Commands run without the agent's approval sheet.

### Conversation history

- Chats are saved locally with title, time, model and message count. Browse date groups and search **titles or model IDs**; this is not full-transcript search.
- Open/resume a chat, share its transcript as text through Android or delete it with confirmation. Opening a project's chat activates that project. Provider-native context resumes when the provider/model match; otherwise the visible transcript is carried into the new model context.
- Start a fresh chat from the toolbar/history screen. Settings → Data offers **Delete all conversations** with confirmation. Storage retains the newest **200 conversations**.

### Appearance

- Choose system/light/dark mode, **Mint/Iris/Ocean/Ember/Rose** accents or a custom six-digit hex color. Accent tones adapt for readable contrast.
- Choose tonal, solid or glass-inspired surfaces; tune glass opacity, ambient background tint, corner roundness and text scale while respecting system font size. A live preview shows changes, which save automatically. Glass appearance uses layered translucency and highlights.
- **High contrast** strengthens text/borders and removes transparency/ambient tint. **Reduce motion** disables decorative pulses and screen transitions. Confirm **Reset appearance** to restore styling without resetting models, keys, conversations or tool permissions.
- V2 Prism artwork includes adaptive/monochrome and legacy launcher icons plus the in-chat mark. **SYNTHESIS** is the selected store artwork. [Editable SVG/ORA masters, fonts, sources and additional icon assets](branding/angel-identity/README.md) are retained; the supplied capability icons are not all assigned to functional controls.

### Privacy and data controls

- Provider API keys, Hugging Face tokens, MCP credentials, project definitions and skills are stored using Android-keystore-backed encryption. Conversations remain app-private on the phone; Android backup/device transfer is disabled. Settings and MCP screens use Android's secure-window flag, and credential fields are masked by default.
- Local inference can run offline with an available model, but tools/connectors can still contact networks. Cloud providers receive conversation context and approved tool/screen output. No analytics, advertising or automatic crash-reporting backend is bundled.
- Linux, MCP, Git and skill installation flows require an explicit review/installation action and explain sources and executable downloads outside F-Droid checks. Locally installed tools can access their Linux files and mounted phone storage.
- Settings exposes **Third-party licenses** with selectable bundled notices. See [PRIVACY.md](PRIVACY.md) for storage, transmission, deletion and the separate Linux environment's lifetime.

## Tested local models and compatibility

Device validation used a Pixel 10 Pro Fold on Android API 37. The tested imports were LFM3-1.7B Q6_K, a DeepSeek-labeled Qwen3.5 Q8_0, Qwen3.5 coder Q4_K_M, Qwen3-0.6B Q4_0 and Gemma 4 E4B (a mixed-quantization Q4_K_M file). Loading, generation and cancellation/recovery passed for these files; this does not establish compatibility with every conversion or quantization. Actual connector execution was also demonstrated with LFM3. Tiny-model answers and tool choices can be unreliable.

Gemma's saved-settings greeting took 84 seconds after loading, and about four minutes for the whole cold test. Stop is cancellable, but large CPU-only models remain slow. Android 8 is the minimum SDK; older Android versions and devices have not received the same hardware coverage. Live cloud-provider credentials were not used in release validation; adapter contracts use fixture responses.

## Fixes in this version

- Downloaded local models whose files were removed or truncated are marked unavailable. Each new download owns a separate file, so retrying or cancelling one cannot delete another model.
- Imported GGUF files load directly from the file picker's granted descriptor, preserving Android's access permission without copying the model. Native load errors include the specific cause (for example, an unsupported architecture or a missing tensor).
- The pinned llama.cpp runtime is updated to v0.8.0 for LFM3 support and upstream handling of missing optional Qwen3.5 MTP tensors. This fixes loading the tested LFM3 Q6_K and DeepSeek-labeled Qwen3.5 Q8_0 imports without changing their files. Streamed replies retain their original token IDs for the updated reasoning/tool-call parser.
- Local inference shows loading, prompt-reading progress and response generation separately. Stop aborts CPU decoding, including a running prompt batch, and clears interrupted cache state before the next reply. The local system prompt is smaller; MCP connectors and their full tool schemas are discovered on demand instead of being loaded for every greeting.
- Project commands and terminals stop if their working directory is missing, instead of letting proot fall back to `/`. Git Changes preserves exact file names (including Unicode, quotes and newlines), unstages new files before the first commit, and unstages both sides of a rename.
- Skill setup tracks the environment it completed in, verifies connector installation before reporting readiness, and reuses its connectors on retries. Skill setup, repository cloning and Git installation kill stalled scripts when their timeout expires.
- **Settings "resetting"**: running `connectedDebugAndroidTest` uninstalled the app after the test run, wiping the encrypted keys. `gradle.properties` now sets `android.injected.androidTest.leaveApksInstalledAfterRun=true`, and the recommended test command below never uninstalls. The provider choice is now persisted as soon as you pick it, and unsaved edits no longer vanish on fold/unfold.
- Saved DeepSeek profiles using `deepseek-chat`/`deepseek-reasoner` are migrated to the configured replacement, `deepseek-flash`, automatically. Errors now name the provider and include its reason (API key redacted), and assistant messages are sent back with only the fields each API accepts.
- Settings entered under the wrong provider (e.g. a DeepSeek model and key saved in the Google profile) are flagged in Settings and in failed-request messages.

## Device setup

1. For phone/Linux tools, start Shizuku using its wireless-debugging/ADB flow (not root mode).
2. Angel → Settings → **Connect / grant Shizuku**. Status must read `Connected: uid 2000 (ADB shell)`.
3. Optionally, under **Linux environment**, pick a distro (Alpine about 4 MB, Debian about 30 MB, Ubuntu about 35 MB, Kali NetHunter about 131 MB and 1 GB free), **Install** it (SHA-256 verified), then **Test**. Distros install side by side; switching the selection changes what the agent and the terminal use. Kali has no real root, so raw sockets and monitor mode are unavailable but TCP-connect scanning works; use it only on systems you are authorized to test.
4. For cloud chat, pick a provider, enter its API key, tap **Test key & list models**, choose a model and **Save**. For local chat, choose **On-device → Manage local models**, download/import a GGUF and tap **Use**.
5. Try a suggested prompt. Review each command before tapping **Run**.

API keys must be entered on the device, never in source code or chat with a coding assistant.

## UI structure

| File | Role |
|---|---|
| `ui/theme/` | `AngelTheme`: color schemes (dark/light), `AngelColors` for status/terminal colors, typography, shapes |
| `ui/components/Components.kt` | Shared pieces: `SectionCard`, `InfoRow`, `HealthDot`, `Pill`, `Callout`, `EmptyState` |
| `ui/AngelApp.kt` | Screen switching with transitions, snackbar notices, approval sheet |
| `ui/chat/ChatScreen.kt` | Header, empty state with setup checklist, message list, composer |
| `ui/chat/ToolActivityCard.kt`, `ToolDetailSheet.kt`, `ApprovalSheet.kt`, `ToolCalls.kt` | Tool-call grouping, detail sheet, approval sheet, status mapping |
| `ui/chat/Markdown.kt` | Lightweight Markdown renderer (prose, code, tables) |
| `ui/mcp/` | MCP screen (Installed / Browse), install sheet, hosted-server and custom-server dialogs |
| `ui/project/`, `ui/skills/` | Projects (list, editor, directory browser, Changes/git); Skills library (view, import, set up) |
| `mcp/` | Server catalog and registry client, install recipes, stdio / Streamable HTTP / SSE transports, JSON-RPC client, `McpManager` lifecycle and tool bridge |
| `model/Skill.kt`, `model/SkillCatalog.kt` | Skill model + SKILL.md parser; built-in skills (assets `skills/<id>/SKILL.md`) with setup scripts and connector bundles |
| `ui/SettingsScreen.kt`, `ui/HistoryScreen.kt`, `ui/terminal/`, `ui/models/` | Settings cards, grouped conversation history with search, terminal, on-device models |

The launch window theme (`res/values/themes.xml`, `values-night/`) matches the Compose background so there is no flash on start. `dev.sh` rebuilds and redeploys on save.

## Build and test

See [BUILDING.md](BUILDING.md) for the pinned toolchain, portable setup, isolated device checks and maintainer signing.

```bash
# Set JAVA_HOME to OpenJDK 21 and ANDROID_HOME to your Android SDK.
export PATH="$ANDROID_HOME/platform-tools:$PATH"   # the SDK adb, not a distro adb
./gradlew installDebug
adb shell am start -n com.bruh.angel/.MainActivity

./gradlew testDebugUnitTest lintDebug                 # JVM unit tests + lint
./gradlew installDebug installDebugAndroidTest         # device tests without uninstalling anything:
adb shell am instrument -w -r com.bruh.angel.test/androidx.test.runner.AndroidJUnitRunner
```

Device tests need Shizuku running with permission granted to Angel and Linux installed. They never call paid APIs (provider tests use in-memory responses).

| Suite | Covers |
|---|---|
| `ProviderContractTest` | All four adapters, tool round trips, sanitized assistant history, DeepSeek reasoning passback, Gemini thought signatures, `read_screen`, model listing, readable-but-redacted errors |
| `StorageSecurityTest` | Encrypted profiles, persisted selection, retired-model migration, agent prefs, conversations, screen-dump summarizing, archive traversal/link rejection, symlink-safe cleanup |
| `ShizukuIntegrationTest` | uid 2000, Linux execution and `/sdcard`, cancellation, corrupt-download rejection, Android and Linux terminal sessions incl. Ctrl-C, reading the screen |
| `LocalModelTest` | Real GGUF download/verification, JNI/FD loading, UTF-8, streamed output, cache reuse and cancellation/recovery. Opt-in `realModel=true`: Qwen3-0.6B tool formatting and canned-result round trip; model-id options exercise imported files and load cancellation |
| `ProjectChatContextTest` | New-chat persistence and request directory/distro/MCP context isolation across project edits; active-project deletion guard |
| `ToolApprovalTest` | Denial/block stops a tool batch; Always allow is not persisted by Deny |
| `AppearanceScreenTest`, `ThemeStoreTest` | Theme controls, readable text, rendered variants and saved appearance |
| `WorkspaceIntegrationTest` | Project working directories, missing-directory rejection, script timeouts, exact Git paths, unstaging before the first commit and after renames, failed MCP setup and explicit distro selection (isolated fixtures) |
| Unit tests | Terminal buffer (ANSI stripping, CR/backspace, scrollback), Markdown block parsing, provider sanity checks, model file availability/download isolation, project and skill state, Git status parsing |

## Linux download provenance

Exact HTTPS URLs and SHA-256 pins are in `linuxenv/LinuxDistro.kt` (root filesystems: Alpine 3.23.6 minirootfs from `dl-cdn.alpinelinux.org`, Debian 13 slim from the official `debuerreotype` artifacts, Ubuntu Base 26.04.1 from `cdimage.ubuntu.com`, Kali 2026.2 NetHunter minimal from `kali.download`, whose hash matches Kali's published SHA256SUMS) and `linuxenv/LinuxArtifacts.kt` (Termux ARM64 proot 5.1.107.96, libtalloc 2.5.0, libandroid-shmem 0.7 (`packages.termux.dev`). Hashes are verified before extraction; archives with unsafe paths or links are rejected; activation is smoke-tested and rolled back on failure. The environment lives in `/data/local/tmp/angel-linux` (owned by uid 2000, mode 0700) and can survive app uninstall. Proot is **not a security sandbox**.

## Limits

- Terminal sessions are pipes, not a TTY: full-screen programs (vim, top, less) and password prompts don't work. Ctrl-C interrupts the running command (all processes in the session except the shell); closing a session kills everything it started.
- Android/Linux agent commands: 60-second timeout and 48 KB output. MCP tool calls: 120-second timeout and 30,000-character result cap. The agent allows up to 12 tool rounds per message.
- `read_screen` uses the accessibility tree; apps that draw their own UI (games, some video/maps views) expose little. No screenshot/vision input yet.
- MCP: tools only (no resources, prompts, sampling or OAuth sign-in; use a token header). On-device models discover connector tools on demand, with up to four matching tools exposed at a time alongside the built-ins. Installing Node-based servers under proot takes minutes. Server output is untrusted text.
- Cloud replies are non-streaming; on-device replies stream. Conversations and settings are stored only on this phone (backups disabled).
- Approved command output and screen text are sent to the selected provider.
- Shizuku runs the service again after each app install (the service version is the APK install time).
