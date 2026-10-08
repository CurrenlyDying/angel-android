# Angel — Android agent

Native Kotlin/Compose app for the Pixel 10 Pro Fold (works on any ARM64 Android 8+). Android Studio owns run configuration; terminal builds use the Gradle wrapper. No root, `su`, or bootloader changes: privileged actions go through Shizuku as the ADB shell user (uid 2000).

## Features

- **Chat agent** for OpenAI, Google Gemini, Anthropic and DeepSeek (native APIs, tool calling). Replies render Markdown (code blocks with copy buttons, pipe tables, lists, bold/italic, inline code, safe web links); every agent message has a copy button.
- **Interface**: a quiet, full-screen conversation with a readable width when unfolded, a simplified toolbar, and editable starter tasks. Consecutive tool calls are grouped into one activity card with status per step and a live command tail; tapping a step opens its full command/output sheet (wrap, copy, share). Approvals remain explicit and editable. New steps do not pull you away from older messages; **Latest** returns to the end.
- **Appearance** (Settings → Your workspace → Appearance): system/light/dark mode, five accent palettes or a custom six-digit hex color, tonal/solid/glass-inspired surfaces, glass opacity, corner roundness, text scaling, and ambient tint. A live preview reflects changes, which save automatically on this phone. Accent tones adapt for readable contrast. High contrast removes translucency and background tint; reduce motion disables decorative pulses and screen transitions. Reset affects appearance only. Glass uses layered translucency and highlights, not native Apple Liquid Glass blur/refraction. System font scaling still applies.
- **Tools the model can use**
  - `android_shell` – one command on the phone as uid 2000 (dumpsys, pm/cmd, am, settings, `input tap/swipe/text`…).
  - `read_screen` – compact list of on-screen UI elements with tap coordinates (uiautomator dump), so the agent can see an app, tap, and verify.
  - `linux_shell` – one command in the selected Linux distro (Alpine, Debian, Ubuntu or Kali, ARM64 under proot), files persisted in `/root`, phone storage at `/sdcard`.
- **MCP servers** (Settings → MCP servers): pick a server from the **Browse** tab and Angel installs it into the selected Linux distro (npm into `node_modules`, Python into a venv under `/root/.angel/mcp/<id>`, missing Node/Python added via apt/apk), starts it over stdio and offers its tools to the model as `mcp__<server>__<tool>`. Browse lists a hand-checked **Featured** set (Fetch, DuckDuckGo, Filesystem, Memory, Sequential Thinking, Time, Context7) plus search of the official MCP Registry (community npm/PyPI servers, shown with a third-party-code warning). **Connect to hosted server** takes a hostname/IP (Streamable HTTP or legacy SSE, optional bearer token/headers); plain `http://` is accepted only for private addresses (LAN, localhost, Tailscale, `.local`), everything else needs `https://`. **Custom command** runs any stdio server in Linux or the Android shell. Each server has its own Ask/Allow/Block policy, per-tool switches, a log, and encrypted credentials; calls obey the same approval sheet (arguments are editable) and the per-command Kill button. Servers keep running in the distro they were installed into.
- **Permissions per tool: Ask / Allow / Deny** (Settings → Agent). *Ask* shows each request; you can **edit the command before running it**, and tick *Always allow* to switch that tool to Allow.
- **Terminal page** (chat toolbar → Workspace options → Terminal, or Settings → Open terminal): persistent Android and Linux shell sessions (they survive fold/unfold and leaving the page), Ctrl-C / Ctrl-D / Tab keys, command history, quick-command chips, clear, copy-all, font size (overflow menu). Lines you typed are highlighted. Commands you type yourself run without approval.
- **Conversations** are saved on the phone (history icon): open, continue, share as text, delete. Model context resumes when the provider/model match; otherwise the visible transcript is passed to the new model as context.
- **Provider settings**: switching provider is saved immediately; model/key edits survive fold/unfold and ask before being discarded. **Test & fetch models** checks the key (free, no tokens) and lists models your key can use. Warnings appear if a model ID or key looks like it belongs to another provider, with a one-tap fix.
- **Custom instructions** appended to the system prompt; the model is also told the phone model, Android version, Shizuku status and Linux status.
- The header shows the active model when ready, or a setup shortcut when configuration is incomplete; tap it to open Settings. One-line notices appear as snackbars.

## On-device models (GGUF from Hugging Face)

Settings → **On-device** → **Manage local models**.

- **Download from Hugging Face**: search (or paste `owner/repo`), pick a single-file GGUF quantization. Each file shows its size and whether it fits this phone's RAM. Downloads run in Android's DownloadManager (background, notification, Wi-Fi only unless allowed) and are checked against Hugging Face's published SHA-256 and the GGUF header before use. A Hugging Face token (optional, encrypted) unlocks gated models; it is only sent to huggingface.co, never to the download CDN.
- **Add a .gguf file** you already have (e.g. in Downloads): it's loaded in place through the file picker's permission, not copied.
- **Use** a model to make it the active provider. It loads on the first message and stays in RAM; *Unload* frees it.
- Inference runs with **llama.cpp v0.5.0** (vendored in `app/src/main/cpp/llama.cpp`, see `VENDORED.md`), CPU-only, with runtime selection of the best ARM kernels (ARMv8.0 → ARMv9.2 variants: dotprod, i8mm, SVE2). Each model's own chat template is used, including its native tool-call format, so local models can use `android_shell`, `linux_shell` and `read_screen` like cloud models. Replies stream; the prompt cache is reused between turns.
- Settings: context size, CPU threads, max reply length, temperature, and "let reasoning models think".
- Good starting points: instruction-tuned models of 0.6–4B parameters in Q4_K_M / Q4_0 (e.g. Qwen3, Llama 3.2 3B, Gemma, Phi mini). Tiny models (<1B) call tools but reason weakly.
- Limits: one model loaded at a time; no GPU/NPU offload; split (multi-part) GGUF files and vision projectors aren't supported yet; models larger than ~70% of RAM are refused.

## Fixes in this version

- **Settings "resetting"**: running `connectedDebugAndroidTest` uninstalled the app after the test run, wiping the encrypted keys. `gradle.properties` now sets `android.injected.androidTest.leaveApksInstalledAfterRun=true`, and the recommended test command below never uninstalls. The provider choice is now persisted as soon as you pick it, and unsaved edits no longer vanish on fold/unfold.
- **DeepSeek HTTP 400**: `deepseek-chat`/`deepseek-reasoner` were discontinued by DeepSeek on 2026-07-24. Saved DeepSeek profiles using them are migrated to `deepseek-flash` automatically. Errors now name the provider and include its reason (API key redacted), and assistant messages are sent back with only the fields each API accepts.
- Settings entered under the wrong provider (e.g. a DeepSeek model and key saved in the Google profile) are flagged in Settings and in failed-request messages.

## Device setup

1. Start Shizuku using its wireless-debugging/ADB flow (not root mode).
2. Angel → Settings → **Connect / grant Shizuku**. Status must read `Connected: uid 2000 (ADB shell)`.
3. Under **Linux environment**, pick a distro (Alpine about 4 MB, Debian about 30 MB, Ubuntu about 35 MB, Kali NetHunter about 131 MB and 1 GB free), **Install** it (SHA-256 verified), then **Test**. Distros install side by side; switching the selection changes what the agent and the terminal use. Kali has no real root, so raw sockets and monitor mode are unavailable but TCP-connect scanning works; use it only on systems you are authorized to test.
4. Pick a provider, enter its API key, tap **Test & fetch models**, choose a model, **Save**.
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
| `mcp/` | Server catalog and registry client, install recipes, stdio / Streamable HTTP / SSE transports, JSON-RPC client, `McpManager` lifecycle and tool bridge |
| `ui/SettingsScreen.kt`, `ui/HistoryScreen.kt`, `ui/terminal/`, `ui/models/` | Settings cards, grouped conversation history with search, terminal, on-device models |

The launch window theme (`res/values/themes.xml`, `values-night/`) matches the Compose background so there is no flash on start. `dev.sh` rebuilds and redeploys on save.

## Build and test

```bash
export JAVA_HOME=/home/puto/codebase/android-studio/jbr
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"   # the SDK adb, not a distro adb
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
| `LocalModelTest` | Hugging Face listing parsing; real download (DownloadManager + SHA-256) of SmolLM2-135M, JNI load, streamed generation, UTF-8, prompt-cache reuse, output parsing. Opt-in `-e realModel true`: Qwen3-0.6B end-to-end tool call (`android_shell`) and answer |
| Unit tests | Terminal buffer (ANSI stripping, CR/backspace, scrollback), Markdown block parsing, provider sanity checks |

## Linux download provenance

Exact HTTPS URLs and SHA-256 pins are in `linuxenv/LinuxDistro.kt` (root filesystems: Alpine 3.23.6 minirootfs from `dl-cdn.alpinelinux.org`, Debian 13 slim from the official `debuerreotype` artifacts, Ubuntu Base 26.04.1 from `cdimage.ubuntu.com`, Kali 2026.2 NetHunter minimal from `kali.download`, whose hash matches Kali's published SHA256SUMS) and `linuxenv/LinuxArtifacts.kt` (Termux ARM64 proot 5.1.107.96, libtalloc 2.5.0, libandroid-shmem 0.7 (`packages.termux.dev`). Hashes are verified before extraction; archives with unsafe paths or links are rejected; activation is smoke-tested and rolled back on failure. The environment lives in `/data/local/tmp/angel-linux` (owned by uid 2000, mode 0700) and can survive app uninstall. Proot is **not a security sandbox**.

## Limits

- Terminal sessions are pipes, not a TTY: full-screen programs (vim, top, less) and password prompts don't work. Ctrl-C interrupts the running command (all processes in the session except the shell); closing a session kills everything it started.
- Agent tool calls: 60-second timeout, 48 KB output, up to 12 tool rounds per message.
- `read_screen` uses the accessibility tree; apps that draw their own UI (games, some video/maps views) expose little. No screenshot/vision input yet.
- MCP: tools only (no resources, prompts, sampling or OAuth sign-in; use a token header). At most 12 tools are offered to on-device models. Installing Node-based servers under proot takes minutes. Server output is untrusted text.
- Responses are not streamed. Conversations and settings are stored only on this phone (backups disabled).
- Approved command output and screen text are sent to the selected provider.
- Shizuku runs the service again after each app install (the service version is the APK install time).
