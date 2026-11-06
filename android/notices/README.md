# Offline software notices

The app packages a catalog for the selected artifact-bearing debug/release runtime components. Settings → Software notices searches original library names and Maven coordinates, then opens complete original notice text. Large documents are split into bounded virtualized text items; concatenating the items reproduces the original decoded UTF-8 document. A localized end marker follows the complete text, and Back stays outside the scrolling document. Library/document rows use small corners and padding so large, multi-line names stay inside the visible row. No browser or network service is needed to read them. Geographic data keeps its separate existing license viewer.

`runtime-lock.json` pins every selected external component's published POM digest and artifact digests. `sources.json` pins the supplemental upstream materials in `upstream/`, including the Apache 2.0 text, the resolved MapLibre Android 13.4.1 commit, the Gestures 0.0.4 commit and Guava's published parent-POM evidence. These are correspondence/provenance checks, not a declaration that legal review is complete.

The Gradle `preDebugBuild`/`preReleaseBuild` tasks generate the catalog and documents under `app/build/generated/runtime-notices/`. The disposable mapQa app uses the production debug notice set; its test-only manifest dependency is outside that product catalog. The generator preserves every embedded notice payload, including Google SDK JSON offset indexes and empty files, but does not present those indexes or empty payloads as readable licenses. The complete companion texts remain available. Known Apache POM declarations receive the canonical full license, and pinned supplemental MapLibre texts retain upstream bundled notices. No source URL is downloaded during asset generation or runtime viewing.

After a dependency change:

1. Regenerate the selected runtime inventories using the commands in [third-party notices](../THIRD_PARTY_NOTICES.md#resolved-runtime-inventory).
2. Inspect changed POMs, artifact-embedded documents and exact-version upstream/bundled dependencies. Obtain any needed source documents, preserve their original bytes, and record immutable source URLs and digests. Do not treat a publisher name, an absent embedded file or a generic POM label as complete licensing evidence.
3. Update the lock only after reviewing those changed inputs. A lock update alone cannot supply missing offline documents; the package generator still fails if a selected artifact-bearing component has no readable notice.
4. Run the collector/package/verifier tests, app builds and native reader/navigation cases. Verify the actual APK/AAB against its generated set:

```sh
python3 -m unittest discover -s scripts -p 'test_*runtime_notice*.py'
./gradlew :app:assembleDebug :app:bundleRelease --no-daemon
python3 scripts/verify_runtime_notice_package.py app/build/outputs/apk/debug/app-debug.apk app/build/generated/runtime-notices/debug
python3 scripts/verify_runtime_notice_package.py app/build/outputs/bundle/release/app-release.aab app/build/generated/runtime-notices/release
```

The release inventory and packaged provenance explicitly retain incomplete legal-review status. Actual packaged-native correspondence is checked separately; the remaining upstream individual-file/toolchain review still needs completion; broader legal/editorial, TalkBack/RTL, API 29 and release performance acceptance remain open. The older signed QA bundle is not a current release candidate.


## Native correspondence

`verify_native_provenance.py` rejects drift from the runtime lock and compares all packaged native files with selected direct AAR JNI entries. If packaging changed bytes, it must reproduce the exact pinned NDK strip output. The NDK revision is shared with the app build through the version catalog; a different revision fails. The report includes tool/source/output hashes, never host paths, and a failed verification removes a prior passing report. No artifact is extracted to a publisher-provided filesystem path. Prefab and inspection JAR copies do not become extra shipped-native claims.

```sh
python3 -m unittest discover -s scripts -p test_native_provenance.py
python3 scripts/verify_native_provenance.py app/build/outputs/apk/debug/app-debug.apk app/build/reports/runtime-notices/debug/resolved-inputs.json notices/runtime-lock.json --ndk-root "$ANDROID_HOME/ndk/$(python3 scripts/verify_native_provenance.py --print-ndk-version)" --output app/build/reports/native-provenance/debug.json
```

Use the release AAB and release inputs for the unsigned release check. Read [the source review](NATIVE_SOURCE_REVIEW.md) alongside package provenance. The MapLibre aggregate is supplemented by nine complete vendor originals plus full Apache terms for Vulkan Headers. Upstream/source and publisher toolchain reproducibility, individual-file copyright coverage and legal/editorial acceptance remain open.

The adaptive-workspace increment adds WindowManager 1.5.1 and WindowManager Core. The selected graph adds three POM/component identities (including metadata-only `window-core`) with two shipped AARs. Their Apache declarations and exact embedded license originals were inspected; existing locked identities remain unchanged, neither AAR contains native libraries, and `window-testing` remains test-only. Current packaging has 118 software entries and preserves 99 embedded notice entries in the same 38 distinct files. This correspondence review does not complete legal/editorial approval.
