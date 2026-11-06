# Android third-party notices

## Geographic lookup behavior

Country lookup uses the device-provided Android Geocoder only during user-requested analysis with network geocoding enabled. Geographic time zones use bundled boundaries, independently of the phone time zone or a country-to-single-zone shortcut. Successful exact-coordinate results and retry deadlines stay in app-private storage.

## Boundary data and reproducible packaging

The build-only `geography-assets` module pins [Timeshape](https://github.com/RomanIakovlev/timeshape) `net.iakovlev:timeshape:2026b.29`. Its compressed boundary data comes from [timezone-boundary-builder](https://github.com/evansiroky/timezone-boundary-builder), derived from OpenStreetMap contributors, under ODbL 1.0. Timeshape tools are MIT licensed. The original data is available in the same public Maven artifact and upstream boundary releases.

`GenerateBoundaries` repackages every original polygon and ring into individually loadable assets. It preserves all original coordinate values exactly, including holes and vertex order; it does not simplify, clip, snap, interpolate or quantize them. The source protobuf uses 32-bit floating-point coordinates, and the generated files retain those bits before the same exact widening to double used by the original geometry engine. The generated database representation remains ODbL 1.0. All data is included in the APK under `assets/timezones/`, with the complete ODbL text under `assets/licenses/`. Its source hash, version and counts are included in `provenance.json`.

The runtime reads a small bounding-box index and loads candidate polygons for exact intersection checks. Bounding boxes only reject distant polygons; they never assign a zone. A point matching multiple distinct zones remains unresolved instead of choosing one arbitrarily. A conservative geometry cache budget limits retained polygons. Unsupported device time-zone identifiers remain unresolved.

The `geography-assets` fidelity tests compare every source ring and coordinate pair against the packaged data and compare global queries with the original Timeshape engine. The original Timeshape archive, protobuf decoder, desktop compression libraries and Zstandard JNI are **build dependencies only** and are not in the Android runtime.

## Runtime geometry code

[Esri Geometry API for Java](https://github.com/Esri/geometry-api-java) `com.esri.geometry:esri-geometry-api:2.2.4` supplies the same polygon intersection predicate used by Timeshape, under Apache License 2.0. Its full license ships in the app. The boundary-source MIT/ODbL texts and geometry license are accessible from Settings → Geographic data and licenses. Third-party license resources retained by dependencies remain packaged; a complete notice audit is still a release gate as other SDKs are integrated.

Provider coverage, regional accuracy, low-memory/API 29 validation and release latency/memory remain explicit release gates. The individual Android lifetime purchase approved by the owner does not affect free recording, country analysis or manual stays.

## History database driver

Room uses `androidx.sqlite:sqlite-bundled:2.6.2`, matching its existing SQLite dependency version, for one consistent driver and a WAL reader/writer pool across supported Android versions. The AndroidX wrapper is Apache 2.0 licensed; upstream SQLite dedicates its deliverable library code to the public domain. See [AndroidX SQLite](https://developer.android.com/jetpack/androidx/releases/sqlite) and [SQLite copyright](https://www.sqlite.org/copyright.html). The bundled native driver makes no network requests. Its ARM64/x86_64 libraries are included in the APK's native alignment check. A complete app-wide notices review remains a release gate.


## Webhook HTTP transport

The webhook transport explicitly depends on [OkHttp 4.12.0](https://github.com/square/okhttp/tree/parent-4.12.0) and resolves [Okio](https://github.com/square/okio) (currently resolved as `okio-jvm:3.17.0`), distributed by Square and contributors under Apache License 2.0. A license copy and source references ship in `assets/licenses/webhook-network-Apache.txt`. The app-wide notices UI audit remains a release gate. The transport uses platform certificate verification, no automatic redirects or cookie persistence, and a private file stream for each explicit POST. It has no analytics integration. The native settings UI, application-owned coordinator and network-constrained WorkManager dispatcher are wired into the app. Delivery requires explicit configuration/enablement and verified lifetime access. No owner endpoint is configured; production receiver acceptance remains open.

## License viewer

The geographic-license viewer reads only its enumerated bundled MIT, ODbL and geometry Apache documents, preserving the original UTF-8 text. English/Spanish labels and explanatory disclosures are separate from those documents. Selection survives recreation; loading is lifecycle scoped, failures offer an explicit retry, and Back remains outside the scrolling text. Opening a source link is a separate user action. This viewer does not constitute the still-open complete app/dependency notice audit.


## Resolved runtime inventory

The build exports the actual selected `debugRuntimeClasspath` and `releaseRuntimeClasspath`, including metadata-only modules, rather than treating everything in a Gradle cache as shipped code. Published POMs are resolved through the project's configured Maven repositories. The Python collector records exact POM/artifact SHA-256 values, declared licenses and unresolved parent metadata; it preserves embedded notices byte-for-byte, including notices inside AAR `classes.jar`/library JARs and Google SDK `third_party_licenses.json`/`.txt` pairs. Native library entries are flagged for separate upstream review. No license URL is fetched automatically, no license is guessed from a publisher, and no runtime dependency is added by this audit tooling.

```sh
./gradlew :app:writeDebugRuntimeNoticeInventory :app:writeReleaseRuntimeNoticeInventory --no-daemon
python3 scripts/runtime_notices.py app/build/reports/runtime-notices/debug/resolved-inputs.json app/build/reports/runtime-notices/debug/collected
python3 scripts/runtime_notices.py app/build/reports/runtime-notices/release/resolved-inputs.json app/build/reports/runtime-notices/release/collected
python3 -m unittest discover -s scripts -p test_runtime_notices.py
```

As checked October 30, 2026, both variants select the same **162 external components**, with **116 distinct artifacts** and **97 embedded notice entries** (23 distinct byte payloads). Forty-six components are metadata-only; their presence does not mean they contribute a separate executable artifact. **43 artifact-bearing components have no embedded notice found**, **two POMs declare no license locally**, and **five modules contain native libraries requiring upstream bundled-dependency review**. These categories overlap. Missing embedded text is a review finding, not permission to omit attribution; a POM declaration is not proof of a complete set of notices. In particular, the selected MapLibre AAR has no embedded notice, while the pinned upstream [Android license document](https://github.com/maplibre/maplibre-native/blob/android-v13.4.1/platform/android/LICENSE.md) contains additional bundled notices.

The generated `inventory.json` explicitly retains `reviewComplete: false`. CI preserves the collected reports/documents for debug and release; the manual signed-bundle workflow also collects the release report. Generated local cache paths stay in the ignored `resolved-inputs.json` and are not part of the portable collected report. The build now also generates offline assets for Settings → Software notices. The current catalog covers all 116 artifact-bearing product components and preserves all 97 embedded payloads (including three empty files and the Google SDK metadata indexes). Indexes and empty payloads are retained for provenance without presenting them as license text. The complete companion texts, canonical Apache license and pinned MapLibre supplemental texts remain readable. The APK and unsigned AAB are checked against the exact generated 38-file set. The reader verifies each selected document hash/size, virtualizes long text without omitting characters, and keeps Back available. See [notice packaging and dependency updates](notices/README.md). Full upstream/native bundled-dependency and legal/editorial review, complete accessibility/device and release-performance acceptance remain open; packaging coverage does not set legalReviewComplete to true.


## Packaged native binaries and supplemental source review

The selected AAR inventory contains native entries in five modules, including Compose inspection tooling. The package verifier checks direct `jni/<abi>/*.so` inputs against every APK/AAB `.so`, with the reviewed runtime lock protecting artifact/POM identities. Current debug APK and unsigned release AAB contain 16 libraries across four ABIs from four runtime components: Graphics Path, DataStore, AndroidX SQLite and MapLibre. Compose's nested inspection JNI does not ship. Prefab copies are not additional runtime owners. Unattributed, missing, duplicate or modified native inputs fail verification.

The app pins NDK 27.0.12077973 in the version catalog. Eight packaged native files retain their exact AAR bytes; eight reproduce the pinned NDK's `llvm-strip --strip-unneeded` output exactly. The report preserves source artifact, original ELF, packaged ELF and stripping-tool hashes without host cache paths. This proves package correspondence, not the toolchains used by dependency publishers or a complete upstream native rebuild. CI verifies debug and unsigned release packages before the release signing pipeline checks payload/signature integrity.

The actual MapLibre AAR's BuildConfig identifies revision `18545ca10d296704b2cba675b182ff2c8da24588` and the Vulkan flavor. Review of that source's linked/vendor attribution declarations found nine designated originals absent from the pinned Android aggregate: HarfBuzz, unordered_dense, PMTiles, ICU (including its nested notices), jni.hpp, MapLibre Tile Format, Vulkan Headers, Vulkan Memory Allocator and glslang/SPIRV. These complete immutable originals now have separate offline reader entries. Vulkan Headers' short notice is accompanied by the full canonical Apache terms. See [native source review](notices/NATIVE_SOURCE_REVIEW.md) for source associations, exact hashes and remaining individual-file/toolchain/legal uncertainty. Preserving these materials does not certify every vendor-directory file as shipped or set legalReviewComplete to true.
