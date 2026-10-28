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

The webhook transport explicitly depends on [OkHttp 4.12.0](https://github.com/square/okhttp/tree/parent-4.12.0) and resolves [Okio 3.6.0](https://github.com/square/okio/tree/3.6.0), distributed by Square and contributors under Apache License 2.0. A license copy and source references ship in `assets/licenses/webhook-network-Apache.txt`. The app-wide notices UI audit remains a release gate. The transport uses platform certificate verification, no automatic redirects or cookie persistence, and a private file stream for each explicit POST. It has no analytics integration. No webhook dispatcher or settings UI is wired into the app yet, and no owner endpoint is configured.
