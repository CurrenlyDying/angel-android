# F-Droid packaging draft

`com.bruh.angel.yml` is a candidate fdroiddata metadata file for published Angel 1.1.1 (code 3). The active packaging request is [RFP #4762](https://gitlab.com/fdroid/rfp/-/work_items/4762), submitted on 2026-11-08. Original [RFP #4751](https://gitlab.com/fdroid/rfp/-/work_items/4751) is closed as superseded. A request is not evidence of inclusion. Current follow-up status is in `SUBMISSION.md` and `../RELEASE_PLAN.md`.

The recipe requests ARM64 native compilation from the checked-in llama.cpp sources and the pinned Android toolchain. Upstream signing is configured with the verified 1.0 certificate so existing release installations can receive compatible updates. Final local independent binary matching and signature copying passed; dedicated F-Droid build-server verification remains part of review.

The 1.1 request's bot correctly identified a Gradle 9.1.0 wrapper JAR alongside a declared 9.6.0 distribution. Version 1.1.1 regenerates the complete wrapper with 9.6.0. `python3 scripts/verify-gradle-wrapper.py` checks the official version-specific JAR and distribution hashes before CI/signing builds. The distribution checksum pin was already present in 1.1.

The full local recipe build from the public v1.1.1 tag, source/APK scans, comparison against the actual published APK and allowed-signer verification passed. The [verification report](https://github.com/CurrenlyDying/angel-android/releases/download/v1.1.1/FDROID-VERIFICATION-1.1.1.txt) states the local toolchain/setup and skipped build-server provisioning explicitly. F-Droid's own review/build is still required.

Copy the YAML into `metadata/` in a current fdroiddata checkout. Run `fdroid readmeta`, `fdroid rewritemeta com.bruh.angel`, `fdroid lint com.bruh.angel`, source scanning and a full recipe build. Do not bypass scanner findings; inspect and resolve them.

Current inclusion questions are tracked in `../RELEASE_PLAN.md`: dependency/asset notices, executable-download consent, model/catalog licensing, build-environment support and development-history eligibility. The public 1.0 release has download activity; F-Droid maintainers decide whether the established-user exception applies.

References: [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [metadata fields](https://f-droid.org/docs/Build_Metadata_Reference/), [reproducible signing](https://f-droid.org/docs/Reproducible_Builds/).
