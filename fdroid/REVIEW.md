# F-Droid review notes

Checked 2026-11-07. These notes support the draft recipe; inclusion and final labels are decided by F-Droid's maintainers.

## Licenses and downloads

Angel's own code is Unlicense. The APK contains no model weights, downloaded Linux environments, Node runtime or installed MCP servers. Bundled native/JVM dependencies and artwork have retained notices in `app/src/main/assets/THIRD_PARTY_NOTICES.txt`; the artwork source tree also retains font licenses and source credits.

The model browser searches Hugging Face and imports/downloads the file the user selects. Search suggestions include models with different licenses: [Qwen3-0.6B](https://huggingface.co/Qwen/Qwen3-0.6B/blob/main/LICENSE) is Apache-2.0 and [Phi-4-mini](https://huggingface.co/microsoft/Phi-4-mini-instruct/blob/main/LICENSE) is MIT; [Llama 3.2](https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct/blob/main/LICENSE.txt) and [Gemma 3](https://ai.google.dev/gemma/terms) impose additional usage conditions. A GGUF conversion or fine-tune can add further terms. Downloaded models are not covered by Angel's license. The draft proposes `NonFreeAdd` for these suggestions and `NonFreeNet` for optional proprietary cloud/hosted services. These are packaging judgments for maintainer review, based on the [Anti-Features definitions](https://f-droid.org/docs/Anti-Features/).

The featured MCP packages are separately installed code. Fetch 2026.8.18, Time 2026.8.18 and DuckDuckGo 0.7.0 declare MIT in their PyPI metadata; Context7 4.2.0 declares MIT in npm metadata. The filesystem, memory and sequential-thinking npm releases 2026.8.31 identify [source commit a40bc270](https://github.com/modelcontextprotocol/servers/blob/a40bc270fb5ece62673f8a1196f57116d885c5eb/LICENSE), which retains MIT/Apache-2.0 code terms and CC-BY-4.0 documentation terms. Transitive packages and arbitrary registry/GitHub installations are selected outside the APK and remain the installer's responsibility.

## Executable download entry points

| Flow | Sources and opt-in |
| --- | --- |
| Linux install/reinstall | Pinned Termux runtime artifacts and the selected distro rootfs; explicit installation dialog names sources and the F-Droid-check bypass |
| Featured/registry MCP | npm/PyPI plus distro packages; installation sheet identifies package/version and disclosure, with Cancel |
| GitHub MCP | User-selected repository/ref/subdirectory plus dependencies; explicit Install/Cancel dialog with disclosure |
| Custom MCP command | User-written command/script; Add/Cancel dialog explains downloaded executable code |
| Skills/RE template | Distro packages and pinned radare2-mcp source; review dialog before setup, with Cancel |
| Repository clone/missing Git | Selected repository and distro packages; review dialog before installing Git/cloning |
| Agent tools/terminal | User commands can invoke arbitrary package managers/downloads; tool policies and editable approvals govern agent calls, while manually typed terminal commands run directly |

Node downloads have a SHA-256 pin; distro package managers verify their own repositories. The radare2 connector checkout verifies its pinned commit. Generic GitHub/registry dependencies are not fixed by the app's release recipe. Consent wording implements the executable-download requirement in the [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/); it is not a claim that downloaded software has passed F-Droid review.

## Build and eligibility

Local source/APK scans, metadata lint and a full recipe build pass with fdroidserver 2.4.5 and the documented installed toolchain. Independent unsigned builds and signature copying have also matched. The final application source passed a new full recipe build/source/APK scan; its unsigned APK matches the local build, and signature copying produces an identical signed APK. A dedicated F-Droid build-server run is still required.

Public development history is under three months. The existing public release and its users may support the policy's established-user exception; acceptance is not guaranteed. Submission needs the public version tag and matching signed asset first.
