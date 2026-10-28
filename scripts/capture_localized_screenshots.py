#!/usr/bin/env python3
"""Build and capture deterministic iPhone, iPad, and Watch localization screenshots."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "IsoMe.xcodeproj"
COPY_PATH = ROOT / "screenshots/localized-copy.json"
OUTPUT_ROOT = ROOT / "screenshots/whereday"
BUNDLE_ID = "com.bontecou.isome"
WATCH_BUNDLE_ID = "com.bontecou.isome.watchkitapp"

LOCALES = {
    "ar-SA": ("ar", "ar_SA"),
    "bn": ("bn", "bn_BD"),
    "en-US": ("en", "en_US"),
    "es-ES": ("es", "es_ES"),
    "fr-FR": ("fr", "fr_FR"),
    "hi": ("hi", "hi_IN"),
    "ja": ("ja", "ja_JP"),
    "pl": ("pl", "pl_PL"),
    "pt-BR": ("pt-BR", "pt_BR"),
    "ru": ("ru", "ru_RU"),
    "zh-Hans": ("zh-Hans", "zh_CN"),
}

IPHONE_STORY = (
    ("01-quiet-map", ("--seed-screenshot-data", "--default-tab=0"), 6.0),
    ("02-total-control", ("--seed-screenshot-data", "--default-tab=2"), 3.0),
    ("03-open-formats", ("--seed-screenshot-data", "--default-tab=1"), 3.0),
    ("04-exact-export", ("--seed-screenshot-data", "--default-tab=1", "--demo-export-filters"), 3.0),
    ("05-your-endpoint", ("--seed-screenshot-data", "--default-tab=1", "--demo-webhook-settings"), 3.0),
)
IPAD_STORY = (
    ("01-welcome", 0),
    ("02-features", 1),
    ("03-permissions", 2),
    ("04-ready", 4),
)


def run(command: list[str], *, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess[str]:
    print("+", " ".join(command), flush=True)
    return subprocess.run(
        command,
        cwd=ROOT,
        check=check,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
    )


def simctl(*arguments: str, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess[str]:
    return run(["xcrun", "simctl", *arguments], check=check, capture=capture)


def find_device(device_name: str, platform: str) -> str:
    result = simctl("list", "devices", "available", "-j", capture=True)
    payload = json.loads(result.stdout)
    candidates: list[tuple[str, str]] = []
    platform_token = f"SimRuntime.{platform}-"
    for runtime, devices in payload["devices"].items():
        if platform_token not in runtime:
            continue
        for device in devices:
            if device["name"] == device_name and device.get("isAvailable", True):
                candidates.append((runtime, device["udid"]))
    if not candidates:
        raise RuntimeError(f"No available {platform} simulator named {device_name!r}")
    return sorted(candidates)[-1][1]


def boot(device_id: str) -> None:
    simctl("boot", device_id, check=False)
    simctl("bootstatus", device_id, "-b")


def build_products(derived_data: Path, skip_build: bool) -> tuple[Path, Path]:
    if not skip_build:
        if derived_data.exists():
            shutil.rmtree(derived_data)
        run([
            "xcodebuild", "build", "-quiet", "-project", str(PROJECT), "-scheme", "IsoMe",
            "-configuration", "Debug", "-destination", "generic/platform=iOS Simulator",
            "-derivedDataPath", str(derived_data), "CODE_SIGNING_ALLOWED=NO",
        ])
        run([
            "xcodebuild", "build", "-quiet", "-project", str(PROJECT), "-scheme", "IsoMeWatch",
            "-configuration", "Debug", "-destination", "generic/platform=watchOS Simulator",
            "-derivedDataPath", str(derived_data), "CODE_SIGNING_ALLOWED=NO",
        ])

    app = derived_data / "Build/Products/Debug-iphonesimulator/IsoMe.app"
    watch_app = derived_data / "Build/Products/Debug-watchsimulator/IsoMeWatch.app"
    if not app.exists() or not watch_app.exists():
        raise RuntimeError(f"Expected simulator products under {derived_data}")
    return app, watch_app


def reinstall(device_id: str, bundle_id: str, app_path: Path) -> None:
    simctl("terminate", device_id, bundle_id, check=False)
    simctl("uninstall", device_id, bundle_id, check=False)
    simctl("install", device_id, str(app_path))


def launch_and_capture(
    device_id: str,
    bundle_id: str,
    language: str,
    apple_locale: str,
    arguments: tuple[str, ...],
    destination: Path,
    wait_seconds: float,
    minimum_entropy: float | None = None,
) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    simctl("terminate", device_id, bundle_id, check=False)
    launch_arguments = [
        "launch", device_id, bundle_id,
        "-AppleLanguages", f"({language})",
        "-AppleLocale", apple_locale,
        *arguments,
    ]
    for attempt in range(3):
        if attempt:
            print(f"Retrying low-entropy capture: {destination} (attempt {attempt + 1})", flush=True)
            simctl("terminate", device_id, bundle_id, check=False)
        simctl(*launch_arguments)
        time.sleep(wait_seconds if attempt == 0 else 2.0)
        simctl("io", device_id, "screenshot", "--type=png", str(destination))
        if minimum_entropy is None or screenshot_entropy(destination) >= minimum_entropy:
            return
    raise RuntimeError(f"Capture remained blank after three attempts: {destination}")


def screenshot_entropy(path: Path) -> float:
    image = Image.open(path).convert("RGB").resize((160, 320))
    return sum(image.getchannel(channel).entropy() for channel in range(3))


def warm_up_iphone(device_id: str, language: str, apple_locale: str) -> None:
    """Allow the first SwiftData/map launch after installation to settle."""
    simctl("terminate", device_id, BUNDLE_ID, check=False)
    simctl(
        "launch", device_id, BUNDLE_ID,
        "-AppleLanguages", f"({language})",
        "-AppleLocale", apple_locale,
        "--seed-screenshot-data", "--default-tab=0",
    )
    time.sleep(6.0)
    simctl("terminate", device_id, BUNDLE_ID, check=False)


def status_bar(device_id: str, *, ipad: bool = False) -> None:
    arguments = [
        "status_bar", device_id, "override", "--time", "9:41",
        "--wifiBars", "3", "--batteryLevel", "100", "--batteryState", "charged",
    ]
    if not ipad:
        arguments += ["--operatorName", "", "--cellularBars", "4"]
    simctl(*arguments, check=False)


def compose_iphone(
    python: str,
    copy: list[dict],
    store_locale: str,
    runtime_locale: str,
    raw_dir: Path,
) -> None:
    final_dir = OUTPUT_ROOT / "appstore" / store_locale / "iphone-67"
    final_dir.mkdir(parents=True, exist_ok=True)
    copy_by_name = {item["name"]: item for item in copy}
    for name, _, _ in IPHONE_STORY:
        item = copy_by_name[name]
        run([
            python,
            str(ROOT / "scripts/compose_white.py"),
            "--verb", item["title"],
            "--desc", item["subtitle"],
            "--screenshot", str(raw_dir / f"{name}.png"),
            "--output", str(final_dir / f"{name}.png"),
            "--locale", runtime_locale,
            "--bg", "#F7F5EF",
            "--frame", "",
            "--preserve-case",
        ])


def capture_locale(
    store_locale: str,
    language: str,
    apple_locale: str,
    app: Path,
    watch_app: Path,
    iphone_id: str,
    ipad_id: str,
    watch_id: str,
    copy: dict,
    python: str,
) -> None:
    print(f"\n=== {store_locale} ===", flush=True)
    raw_root = OUTPUT_ROOT / "raw" / store_locale

    reinstall(iphone_id, BUNDLE_ID, app)
    warm_up_iphone(iphone_id, language, apple_locale)
    for name, arguments, wait_seconds in IPHONE_STORY:
        launch_and_capture(
            iphone_id, BUNDLE_ID, language, apple_locale, arguments,
            raw_root / "iphone" / f"{name}.png", wait_seconds,
            minimum_entropy=5.0,
        )
    compose_iphone(python, copy[store_locale], store_locale, language, raw_root / "iphone")

    reinstall(ipad_id, BUNDLE_ID, app)
    for name, page in IPAD_STORY:
        launch_and_capture(
            ipad_id, BUNDLE_ID, language, apple_locale, (f"--onboarding-page={page}",),
            OUTPUT_ROOT / "appstore" / store_locale / "ipad-129" / f"{name}.png", 2.0,
        )

    reinstall(watch_id, WATCH_BUNDLE_ID, watch_app)
    launch_and_capture(
        watch_id, WATCH_BUNDLE_ID, language, apple_locale, (),
        OUTPUT_ROOT / "appstore" / store_locale / "watch-series-10" / "01-main.png", 2.0,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--locales", default=",".join(LOCALES), help="comma-separated store locales")
    parser.add_argument("--iphone", default="iPhone 17 Pro Max")
    parser.add_argument("--ipad", default="iPad Pro 13-inch (M5)")
    parser.add_argument("--watch", default="Apple Watch Series 11 (46mm)")
    parser.add_argument("--derived-data", type=Path, default=Path("/tmp/isome-localization-screenshot-derived"))
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--python", default=sys.executable, help="Python with Pillow and Arabic shaping dependencies")
    args = parser.parse_args()

    requested = [locale.strip() for locale in args.locales.split(",") if locale.strip()]
    unknown = sorted(set(requested) - set(LOCALES))
    if unknown:
        raise RuntimeError(f"Unknown locales: {', '.join(unknown)}")

    copy = json.loads(COPY_PATH.read_text())
    iphone_id = find_device(args.iphone, "iOS")
    ipad_id = find_device(args.ipad, "iOS")
    watch_id = find_device(args.watch, "watchOS")
    for device_id in (iphone_id, ipad_id, watch_id):
        boot(device_id)
    status_bar(iphone_id)
    status_bar(ipad_id, ipad=True)

    app, watch_app = build_products(args.derived_data, args.skip_build)
    for store_locale in requested:
        language, apple_locale = LOCALES[store_locale]
        capture_locale(
            store_locale, language, apple_locale, app, watch_app,
            iphone_id, ipad_id, watch_id, copy, args.python,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
