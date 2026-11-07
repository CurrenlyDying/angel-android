# F-Droid packaging draft

`com.bruh.angel.yml` is a candidate fdroiddata metadata file. It is not a submission or evidence of inclusion. The `v1.1` tag and signed asset URL become valid when the reviewed release is published.

The recipe requests ARM64 native compilation from the checked-in llama.cpp sources and the pinned Android toolchain. Upstream signing is configured with the verified 1.0 certificate so existing release installations can receive compatible updates. Independent binary matching remains required before this route can be submitted.

Copy the YAML into `metadata/` in a current fdroiddata checkout. Run `fdroid readmeta`, `fdroid rewritemeta com.bruh.angel`, `fdroid lint com.bruh.angel`, source scanning and a full recipe build. Do not bypass scanner findings; inspect and resolve them.

Current inclusion questions are tracked in `../RELEASE_PLAN.md`: dependency/asset notices, executable-download consent, model/catalog licensing, build-environment support and development-history eligibility. The public 1.0 release has download activity; F-Droid maintainers decide whether the established-user exception applies.

References: [submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [metadata fields](https://f-droid.org/docs/Build_Metadata_Reference/), [reproducible signing](https://f-droid.org/docs/Reproducible_Builds/).
