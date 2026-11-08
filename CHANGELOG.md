# Changelog

## 1.1.1

- Regenerate the Gradle wrapper JAR and launch scripts with Gradle 9.6.0 to match the declared distribution. The previous JAR was the official 9.1.0 wrapper.
- Verify the wrapper JAR and pinned distribution against Gradle's published checksums in CI before running Gradle.
- Refresh the F-Droid recipe and release metadata. App features are unchanged from 1.1.

## 1.1

- Projects group chats with their own Linux directory, distribution, instructions, skills and MCP connectors.
- Repository cloning and Changes add diff viewing, staging, unstaging, discarding and local commits.
- Skills support SKILL.md import and a Reverse Engineering project template. MCP servers can be installed from GitHub repositories.
- llama.cpp is updated to v0.8.0 for LFM3 and Qwen3.5 files with absent optional prediction layers. Imported models load through Android's granted file descriptor.
- Local inference reports loading and prompt progress, supports prompt/generation cancellation, recovers after Stop and discovers connector tools on demand.
- Missing or truncated downloaded models are marked unavailable; downloads own separate files to isolate retry/cancel operations.
- Project editor skill/connector drafts survive rotation. GitHub URL fields disable autocorrection and invalid clone URLs are caught before creating a project.
- Project commands reject missing working directories. Git operations preserve exact filenames and handle unstaging before the first commit and after renames.
- Denying a tool stops the current request and its remaining tool calls. The Always allow option takes effect only when Run is approved.
- Installer timeouts terminate stalled commands. Skill readiness tracks its Linux distribution and verifies connector installation. Reverse Engineering installs required compiler/development packages and builds pinned r2mcp 1.8.0 with immediate failure checks.
- Opening a project in a new chat preserves the previous conversation and starts empty. Running requests keep their original directory, distro, project instructions and MCP connector selection after project edits; deleting their active project requires stopping first.
- V2 Prism artwork and SYNTHESIS store graphics replace the previous branding.
- Executable download flows include source disclosures and explicit review before automatic skill/Git setup. Bundled native, JVM and artwork license notices are accessible in Settings.

Validation and remaining compatibility limits are recorded in RELEASE_PLAN.md.

## 1.0

Initial public release: https://github.com/CurrenlyDying/angel-android/releases/tag/APK
