# Packaging request preparation

Use the official [Request for Packaging tracker](https://gitlab.com/fdroid/rfp/-/issues/new) or a dedicated fdroiddata merge request. This file is a draft to copy into the applicable current template; no request has been submitted by storing it here.

- Name: Angel
- Application ID: `com.bruh.angel`
- Author/source: [CurrenlyDying/angel-android](https://github.com/CurrenlyDying/angel-android)
- License: Unlicense; bundled dependency/artwork licenses and notices retained separately
- Description: Native Android AI assistant with local GGUF inference, optional cloud providers, Shizuku tools, proot Linux environments, projects, skills and MCP connectors
- Release source/tag: `v1.1` (version code 2)
- Upstream binary: `https://github.com/CurrenlyDying/angel-android/releases/download/v1.1/angel-android-1.1-release.apk`
- Draft metadata: `fdroid/com.bruh.angel.yml`
- Proposed Anti-Features: NonFreeNet (optional proprietary services), NonFreeAdd (restricted-license model suggestions)
- Build: OpenJDK 21 / Gradle 9.6.0 / AGP 9.4.1 / SDK 37.0 / build-tools 36 / NDK 30.0.16248370 / CMake 4.1.2, ARM64-only, no IDE required
- Existing signer: `773df628227f9ee4d2e24e7505f0f3b68a65b4eb97cafe467dcf366c58d11058`; upstream-signed reproducible-build route
- Consent: Linux/MCP/skill/Git install flows identify sources and explain executable downloads bypass F-Droid checks; downloads are opt-in
- Eligibility: development history is under three months; an existing public release has users. Please assess the established-user exception rather than assuming automatic eligibility.

Attach the final validation details from RELEASE_PLAN.md. The local recipe uses an installed toolchain; its skipped sudo provisioning is not proof that the dedicated build server has all packages. Maintainer feedback/build-server verification remains necessary. Do not post signing passwords or private-key files.
