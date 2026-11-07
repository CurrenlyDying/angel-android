# Privacy and device access

Angel is an Android client. Its source does not include analytics, advertising or automatic crash-reporting services. The developer does not operate an Angel chat backend.

## Conversations and credentials

Conversations, project definitions and settings are stored on this phone. Android backup and device-transfer backup are disabled. Provider API keys, Hugging Face tokens and MCP credentials are encrypted using Android's keystore. Enter credentials in the app; never put them in repository files or chat transcripts.

When a cloud provider is selected, Angel sends the conversation context and tool definitions to that provider. Tool results and screen text can be included in later requests. Commands that receive an Allow policy run without a new approval each time; review these policies before using sensitive data. Provider privacy and retention policies apply to those requests.

On-device inference runs in the app using llama.cpp. An already imported or downloaded model can answer without a cloud provider. Network tools and connectors can still contact external services when used.

## Downloads and connectors

Browsing/downloading models contacts Hugging Face and its download infrastructure. An optional Hugging Face token is sent only to huggingface.co. Model weights have their own licenses and are not bundled with Angel.

Linux setup downloads its runtime and root filesystem from the sources recorded in `linuxenv/LinuxArtifacts.kt` and `LinuxDistro.kt`, verifying pinned hashes. MCP and skill installation can additionally contact GitHub, package registries and the selected Linux distribution's repositories, then run downloaded code. These downloads are outside F-Droid's build review. The installation screens identify sources and require an explicit installation action.

Hosted MCP servers receive requests and arguments for their tools. Locally installed MCP servers run as external processes and can access files and networks available to that environment. Their own privacy policies and behavior apply. Only install/connect to servers you trust.

## Shizuku and Linux

With Shizuku permission, Angel can execute commands as Android's ADB shell user, uid 2000. This enables powerful phone operations and reading UI text. It does not grant real root. Screen reading uses Android's UI hierarchy; Angel currently does not send screenshots to models.

The Linux environments run under proot and are not security sandboxes. Their processes can access Linux files and mounted phone storage at `/sdcard`. Other processes with the same shell identity can also access the Linux environment.

## Removing data

Delete conversations in the app or clear its Android app data to remove app-private records and credentials. Exported/shared transcripts remain wherever you saved or sent them. Imported GGUF files stay in their original locations; removing their Angel entries releases the saved document grants. App-owned model downloads are removed with the app.

Linux environments live separately in `/data/local/tmp/angel-linux` and can survive app uninstall. Remove a distribution through Angel's Linux settings before uninstalling if you want to delete its files. Removing app data does not retract information already sent to cloud providers or MCP servers.
