---
name: Reverse Engineering
description: Analyze and reverse-engineer Android apps and binaries on the device itself — pull and decompile APKs over Shizuku, inspect native libraries, and report findings with evidence. Use for authorized security review, malware triage, app behavior analysis, and CTF work.
---

# Reverse Engineering

You are reverse-engineering software **on the phone itself**. Angel has device shell access through
Shizuku (uid 2000, the ADB shell user) and a Linux userland, so you can analyze installed apps and
binaries without a separate computer.

## Authorized use only

Only analyze software the user owns or is explicitly authorized to review (their own apps, samples
they provide, CTF targets, or engagements they name). If a target's provenance is unclear, ask
before continuing. There is **no real root**: you can do static analysis and dynamic analysis of
apps you repackage yourself, but you cannot attach a debugger to, or hook, an arbitrary third-party
app. Don't exfiltrate data or secrets you discover — report them to the user.

## Tools you have

- `android_shell` — one command as uid 2000 on Android (package manager, pulling APKs, reading
  device state). This is the key advantage: the target apps are already installed here.
- `linux_shell` — one command in the project's Linux working directory. The RE CLI tools live here:
  `radare2` (`r2`), `binwalk`, `file`, and on Kali also `apktool` and `jadx`.
- `read_screen` — the current UI tree with tap coordinates, for observing an app's behavior.
- If the radare2 MCP connector is attached, prefer its structured tools for binary analysis over
  raw `r2` command lines.

## Workflow

### 1. Locate and pull the target (Android apps)

```
# find the package
android_shell: pm list packages | grep <name>
# resolve every APK (apps are often split: base + config splits)
android_shell: pm path <package>
# copy each APK into the project working directory (it is the cwd for linux_shell)
android_shell: cp /data/app/.../base.apk <workdir>/base.apk
```

`pm path` prints one `package:/path/to.apk` line per split. Pull `base.apk` first; pull the
`split_config.*` APKs too when you need resources or native libraries for a specific ABI.

### 2. Triage

- **Manifest & components:** `apktool d -f -s base.apk -o apk_src` (decodes `AndroidManifest.xml`,
  resources, and smali). Read the manifest for `package`, `minSdk`/`targetSdk`, requested
  permissions, and **exported** activities/services/receivers/providers (attack surface).
- **Strings & secrets:** `strings -n 8 base.apk` and grep the decoded tree for URLs, API endpoints,
  tokens, and crypto constants: `grep -rIn -E 'https?://|api[_-]?key|secret|BEGIN .*PRIVATE' apk_src`.
- **Native libraries:** `unzip -o base.apk 'lib/*'` then, per `.so`, `file lib/arm64-v8a/*.so` and
  `r2 -q -c 'aaa; afl; iz' lib/arm64-v8a/libfoo.so`.

### 3. Decompile

- **Java/Kotlin:** `jadx -d jadx_out base.apk` (full project) or `jadx --no-res base.apk` for code
  only. Read the entry points named in the manifest first, then follow the logic that handles the
  exported components and any networking.
- **Native:** drive `radare2` — `aaa` to analyze, `afl` to list functions, `pdf @ <sym>` to
  decompile-ish disassemble, `axt <sym>` for cross-references. With the radare2 MCP connector,
  use its open/analyze/decompile tools instead.

### 4. Arbitrary binaries (not APKs)

`file <bin>` → then `binwalk <firmware_or_blob>` to carve, or `r2 -A <elf>` to analyze. `binwalk -e`
extracts embedded filesystems/archives.

## Reporting

Present findings as **claim → evidence → confidence**, and keep a short list of **open questions**
you could not resolve. For each claim, cite the concrete artifact (a manifest line, a function
address, a decompiled snippet, a matched string) so the user can verify it. State what you did *not*
check. Don't overclaim: distinguish "the code contains" from "the app does at runtime".

## Notes & limits

- `apktool` and `jadx` need a JVM and are packaged on **Kali** (and installable on Debian/Ubuntu from
  some repos), but not on Alpine — prefer a Kali or Debian project for APK work.
- Terminal sessions are pipes, not a TTY: no interactive tools (no `less`/`vim`); add `-q`/`--no-pager`
  flags and write output to files you then read.
- For deeper native decompilation (Ghidra-class), the **REA** project (`github.com/morluto/rea`) can
  be installed as an MCP server from Angel's *Install from GitHub* flow; its full power needs native
  decompilers that aren't on the phone yet, so treat it as an advanced, optional add-on.
