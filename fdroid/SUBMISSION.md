# Packaging submission and follow-up

The active request is [RFP #4762](https://gitlab.com/fdroid/rfp/-/work_items/4762), submitted through Chrome on 2026-11-08 for published Angel 1.1.1. Maintainer review and F-Droid's own build-server verification remain pending.

The first request, [RFP #4751](https://gitlab.com/fdroid/rfp/-/work_items/4751), was submitted on 2026-11-07. Its bot report identified a real wrapper-version mismatch: the official Gradle 9.1.0 JAR was retained while the distribution was pinned to 9.6.0. There was no maintainer rejection in the report reviewed on 2026-11-08. Version 1.1.1 corrects the wrapper and adds an official-checksum CI/signing check. The original request is closed as superseded; the submitting account has no Delete action on it. The new request links the original history.

The [public verification report](https://github.com/CurrenlyDying/angel-android/releases/download/v1.1.1/FDROID-VERIFICATION-1.1.1.txt) records a successful local recipe run from the public tag, source/APK scans, comparison with the published reference APK, and allowed-signer verification. Fresh JVM tests and branch/PR CI also pass. The 1.0 release's public download counts (25 release APK, 11 debug APK as checked on 2026-11-08) are supplied as supporting eligibility evidence, not as unique-user counts.

- Name: Angel
- Application ID: `com.bruh.angel`
- Author/source: [CurrenlyDying/angel-android](https://github.com/CurrenlyDying/angel-android)
- License: Unlicense; bundled dependency/artwork licenses and notices retained separately
- Description: Native Android AI assistant with local GGUF inference, optional cloud providers, Shizuku tools, proot Linux environments, projects, skills and MCP connectors
- Release source/tag: `v1.1.1` (version code 3)
- Upstream binary: `https://github.com/CurrenlyDying/angel-android/releases/download/v1.1.1/angel-android-1.1.1-release.apk`
- Draft metadata: `fdroid/com.bruh.angel.yml`
- Proposed Anti-Features: NonFreeNet (optional proprietary services), NonFreeAdd (restricted-license model suggestions)
- Build: OpenJDK 21 / Gradle 9.6.0 / AGP 9.4.1 / SDK 37.0 / build-tools 36 / NDK 30.0.16248370 / CMake 4.1.2, ARM64-only, no IDE required
- Existing signer: `773df628227f9ee4d2e24e7505f0f3b68a65b4eb97cafe467dcf366c58d11058`; upstream-signed reproducible-build route
- Consent: Linux/MCP/skill/Git install flows identify sources and explain executable downloads bypass F-Droid checks; downloads are opt-in
- Eligibility: development history is under three months; an existing public release has users. Please assess the established-user exception rather than assuming automatic eligibility.

Attach the final validation details from RELEASE_PLAN.md. The local recipe uses an installed toolchain; its skipped sudo provisioning is not proof that the dedicated build server has all packages. Maintainer feedback/build-server verification remains necessary. Do not post signing passwords or private-key files.
