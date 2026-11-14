#!/usr/bin/env python3
"""Build-artifact verification/signing only. Never uploads to Play or approves release gates."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

BUNDLETOOL_VERSION = "1.18.3"
BUNDLETOOL_SHA256 = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
BUNDLETOOL_URL = f"https://github.com/google/bundletool/releases/download/{BUNDLETOOL_VERSION}/bundletool-all-{BUNDLETOOL_VERSION}.jar"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SCRIPTS = Path(__file__).resolve().parent


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fingerprint(value):
    normalized = value.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise ValueError("Expected a SHA-256 upload-certificate fingerprint")
    return normalized


def checked(command, diagnostics=False, **kwargs):
    # Signing passwords are passed by environment references, never as argument values.
    result = subprocess.run(command, text=True, capture_output=True, timeout=300, **kwargs)
    if result.returncode:
        detail = ""
        if diagnostics:
            detail = result.stderr + result.stdout
            for name, value in os.environ.items():
                if name.startswith("WHEREDAY_") and value:
                    detail = detail.replace(value, "[redacted]")
            detail = "\n" + detail[-4000:]
        raise RuntimeError(f"{Path(command[0]).name} verification/signing command failed ({result.returncode}){detail}")
    return result.stdout


def password_file(directory, name, value):
    # bundletool supports file: passwords, unlike jarsigner's environment references.
    if not value or any(character in value for character in "\r\n\0"):
        raise ValueError("Bundletool signing passwords must be nonempty single-line values")
    path = Path(directory) / name
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
        stream.write(value + "\n")
    return path


def fetch_bundletool(destination):
    destination = Path(destination)
    if not destination.exists():
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as stream:
            temporary = Path(stream.name)
            try:
                with urllib.request.urlopen(BUNDLETOOL_URL, timeout=60) as response:
                    shutil.copyfileobj(response, stream)
                stream.close()
                if sha256(temporary) != BUNDLETOOL_SHA256:
                    raise ValueError("Downloaded bundletool checksum mismatch")
                temporary.replace(destination)
            finally:
                temporary.unlink(missing_ok=True)
    if sha256(destination) != BUNDLETOOL_SHA256:
        raise ValueError("Pinned bundletool checksum mismatch")
    return destination


def validate_manifest(text, version_code, version_name):
    root = ET.fromstring(text)
    if root.tag != "manifest" or root.get("package") != "com.bontecou.whereday":
        raise ValueError("Release package identity mismatch")
    if root.get(ANDROID + "versionCode") != str(version_code) or root.get(ANDROID + "versionName") != version_name:
        raise ValueError("Release version identity mismatch")
    sdk = root.find("uses-sdk")
    if sdk is None or sdk.get(ANDROID + "minSdkVersion") != "29" or int(sdk.get(ANDROID + "targetSdkVersion", "0")) < 36:
        raise ValueError("Release SDK policy mismatch")
    app = root.find("application")
    if app is None or app.get(ANDROID + "debuggable", "false") != "false" or app.get(ANDROID + "testOnly", "false") != "false":
        raise ValueError("Debug/test application in release")
    if app.get(ANDROID + "allowBackup") != "false" or app.get(ANDROID + "fullBackupContent") != "false" or not app.get(ANDROID + "dataExtractionRules"):
        raise ValueError("Sensitive-data backup exclusions missing")
    for component in app:
        name = component.get(ANDROID + "name", "")
        if any(marker in name.lower() for marker in ("debugdiagnostics", "testactivity", "compose.ui.tooling")):
            raise ValueError("Test/debug component in release")
        if "diagnostics" in component.get(ANDROID + "authorities", "").lower():
            raise ValueError("Diagnostics provider in release")
    permissions = {node.get(ANDROID + "name") for node in root.findall("uses-permission")}
    if "android.permission.FOREGROUND_SERVICE_LOCATION" not in permissions:
        raise ValueError("Location foreground-service declaration missing")
    def location_type(value):
        # bundletool renders compiled flag values numerically (location is bit 0x8).
        try:
            return bool(int(value, 0) & 0x8)
        except ValueError:
            return "location" in value.split("|")
    recording = next((node for node in app.findall("service") if node.get(ANDROID + "name") == "com.bontecou.whereday.platform.RecordingService"), None)
    if recording is None or not location_type(recording.get(ANDROID + "foregroundServiceType", "")):
        raise ValueError("Location foreground-service type missing")
    if recording.get(ANDROID + "exported") != "false" or recording.get(ANDROID + "stopWithTask") != "false":
        raise ValueError("Recording service lifecycle or export policy mismatch")
    return {"package": root.get("package"), "versionCode": version_code, "versionName": version_name,
            "minSdk": 29, "targetSdk": int(sdk.get(ANDROID + "targetSdkVersion")), "permissions": sorted(permissions)}


def sign_and_verify(args):
    bundletool = fetch_bundletool(args.bundletool)
    certificate = fingerprint(args.certificate_sha256)
    if args.version_code not in range(1, 2_100_000_001) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?", args.version_name):
        raise ValueError("Invalid release version")
    if not re.fullmatch(r"[a-f0-9]{40}", args.source_revision):
        raise ValueError("Expected full source commit SHA")
    revision = checked(["git", "rev-parse", "HEAD"]).strip()
    dirty = bool(checked(["git", "status", "--porcelain"]).strip())
    if revision != args.source_revision or (args.require_clean_tree and dirty):
        raise ValueError("Source revision differs or release checkout is dirty")
    for key in ("WHEREDAY_STORE_PASSWORD", "WHEREDAY_KEY_PASSWORD", "WHEREDAY_KEY_ALIAS"):
        if not os.environ.get(key):
            raise ValueError(f"Missing signing input: {key}")
    if not args.keystore.is_file() or not args.bundle.is_file():
        raise ValueError("Signing input file missing")
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError("Release output directory must be empty")
    args.output.mkdir(parents=True, exist_ok=True)
    # Nothing is promoted to output until all signature, manifest and native checks succeed.
    with tempfile.TemporaryDirectory(prefix="whereday-release-") as scratch:
        scratch = Path(scratch)
        signed = scratch / f"whereday-{args.version_name}-{args.version_code}.aab"
        checked(["jarsigner", "-keystore", str(args.keystore.resolve()), "-storepass:env", "WHEREDAY_STORE_PASSWORD",
                 "-keypass:env", "WHEREDAY_KEY_PASSWORD", "-digestalg", "SHA-256", "-signedjar", str(signed),
                 str(args.bundle.resolve()), os.environ["WHEREDAY_KEY_ALIAS"]])
        covered = int(checked(["java", str(SCRIPTS / "VerifyBundleSignature.java"), str(signed), certificate]).strip())
        tool = ["java", "-jar", str(bundletool.resolve())]
        checked(tool + ["validate", f"--bundle={signed}"])
        manifest = checked(tool + ["dump", "manifest", f"--bundle={signed}", "--module=base"])
        identity = validate_manifest(manifest, args.version_code, args.version_name)
        apks = scratch / "release.apks"
        store_password = password_file(scratch, "store-password", os.environ["WHEREDAY_STORE_PASSWORD"])
        key_password = password_file(scratch, "key-password", os.environ["WHEREDAY_KEY_PASSWORD"])
        try:
            checked(tool + ["build-apks", f"--bundle={signed}", f"--output={apks}", "--mode=universal",
                            f"--ks={args.keystore.resolve()}", f"--ks-key-alias={os.environ['WHEREDAY_KEY_ALIAS']}",
                            f"--ks-pass=file:{store_password}", f"--key-pass=file:{key_password}"], diagnostics=True)
        finally:
            store_password.unlink(missing_ok=True)
            key_password.unlink(missing_ok=True)
        apk = scratch / "universal.apk"
        with zipfile.ZipFile(apks) as archive:
            with archive.open("universal.apk") as source, apk.open("wb") as destination:
                shutil.copyfileobj(source, destination)
        native_report = checked(["python3", str(SCRIPTS / "verify_native_page_sizes.py"), str(apk)])
        report = {"artifact": signed.name, "sha256": sha256(signed), "uploadCertificateSha256": certificate,
                  "signedPayloadEntries": covered, "sourceRevision": revision, "sourceDirty": dirty,
                  "bundletool": {"version": BUNDLETOOL_VERSION, "sha256": BUNDLETOOL_SHA256}, "manifest": identity,
                  "nativeAlignment": "passed for generated universal APK", "mappingMetadataPresent": False,
                  "mapping": "not present in this signed bundle",
                  "approval": "Build evidence only. Product/provider/field/Play release gates remain separate."}
        staged = scratch / "artifacts"
        staged.mkdir()
        with zipfile.ZipFile(signed) as archive:
            mapping_name = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"
            if mapping_name in archive.namelist():
                with archive.open(mapping_name) as source, (staged / "mapping.txt").open("wb") as destination:
                    shutil.copyfileobj(source, destination)
                report["mappingMetadataPresent"] = True
                report["mapping"] = "mapping.txt extracted from this signed bundle"
        shutil.copy2(signed, staged / signed.name)
        (staged / "manifest.xml").write_text(manifest)
        (staged / "native-alignment.txt").write_text(native_report)
        (staged / "release.json").write_text(json.dumps(report, indent=2) + "\n")
        files = sorted(staged.iterdir())
        (staged / "SHA256SUMS").write_text("".join(f"{sha256(p)}  {p.name}\n" for p in files))
        for path in staged.iterdir():
            shutil.copy2(path, args.output / path.name)
    print(f"Signed bundle verified: {identity['package']} {args.version_name} ({args.version_code})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundletool", type=Path, required=True)
    parser.add_argument("--fetch-tool-only", action="store_true")
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--certificate-sha256")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    parser.add_argument("--source-revision")
    parser.add_argument("--require-clean-tree", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.fetch_tool_only:
        fetch_bundletool(args.bundletool)
        print(f"bundletool {BUNDLETOOL_VERSION} checksum verified")
    else:
        if any(getattr(args, name) is None for name in ("bundle", "keystore", "certificate_sha256", "version_code", "version_name", "source_revision", "output")):
            parser.error("Signing requires bundle, keystore, certificate, version, source revision and output")
        sign_and_verify(args)


if __name__ == "__main__":
    main()
