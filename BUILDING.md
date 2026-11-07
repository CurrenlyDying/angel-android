# Building and checking Angel

Angel targets ARM64 Android 8+ (API 26), with compile/target SDK 37. Native code is optimized in both debug and release variants.

## Toolchain

The release toolchain uses an installed OpenJDK 21, Gradle 9.6.0, AGP 9.4.1, Android build tools 36.0.0, NDK 30.0.16248370 and CMake 4.1.2. Kotlin and dependencies are pinned in `gradle/libs.versions.toml`; the wrapper download has a SHA-256 pin. Automatic JDK downloads are disabled.

Set `JAVA_HOME` to OpenJDK 21 and `ANDROID_HOME` to your Android SDK. Android Studio is optional:

```sh
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" \
  "ndk;30.0.16248370" "cmake;4.1.2"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

Release assembly is unsigned by default and needs no signing secrets. CI runs these checks; it does not sign or publish.

## Device tests

Use the SDK's `platform-tools/adb`:

```sh
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -r com.bruh.angel.test/androidx.test.runner.AndroidJUnitRunner
```

Do not use `connectedDebugAndroidTest` on a phone containing data you want to keep: connected-test cleanup can uninstall the app and invalidate encrypted credentials. Integration suites need Shizuku permission and the relevant Linux distribution installed. Provider contract tests use fake responses.

Real local-model checks are opt-in. `LocalModelTest` accepts `realModel=true` for Qwen3-0.6B and `modelId` for an already registered model. `loadCancellationModelId` enables load cancellation; `benchmarkModelId` enables weight-buffer timing. For `importedAgentAnswersGreeting`, `useSavedLocalParams=true` exercises the saved context, sampling, token limit and thinking setting without changing preferences. Large-model tests can take several minutes. They preserve model selection and imported files.

For a separate test installation, build with `-Pangel.applicationIdSuffix=.releasecheck`. This produces `com.bruh.angel.releasecheck`, with separate app data and Shizuku permission. Do not publish that APK.

## Maintainer signing

Keep the existing keystore and password files outside this repository. The published 1.0 release's certificate SHA-256 is:

```text
773df628227f9ee4d2e24e7505f0f3b68a65b4eb97cafe467dcf366c58d11058
```

The signing helper reads password files directly through `apksigner`. Their contents stay out of command arguments and Gradle configuration:

```sh
export ANGEL_KEYSTORE=/path/to/existing-release.jks
export ANGEL_KEYSTORE_PASSWORD_FILE=/path/to/password-file
# Optional: ANGEL_KEY_ALIAS, ANGEL_KEY_PASSWORD_FILE, ANGEL_APKSIGNER
./scripts/build-release.sh
```

It checks the public certificate, preserves existing 1.0 artifacts, and writes a signed APK, certificate report and version-specific SHA-256 manifest under `app/build/outputs/distribution/`. It defaults to build-tools 34's signer for F-Droid signature-copy compatibility; install `build-tools;34.0.0` or supply a compatible signer path.

Use the separate test application ID when the phone contains a debug installation signed with a different key. Existing release users need updates signed by the original release key.

## F-Droid

Store text and images live under `fastlane/metadata/android/en-US/`. Packaging and submission status are tracked in `RELEASE_PLAN.md`. Upstream-signed F-Droid publication also needs an independent rebuild matching the signed APK; local assembly alone does not establish that.

Native paths are normalized across checkout directories. Runtime provenance is recorded in `app/src/main/cpp/llama.cpp/VENDORED.md`. Native/Shizuku notices are bundled in `app/src/main/assets/THIRD_PARTY_NOTICES.txt` and accessible in Settings. The notices also retain the release JVM dependencies’ license/notice texts, XZ’s 0BSD license and OkHttp’s Public Suffix List notice/MPL-2.0 license. Model downloads and externally installed tools have separate licenses; the release plan records the remaining catalog/artwork review.
