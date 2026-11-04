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

# Launch tabs (see MainTabView.tabTag): 0 = Trace over Today, 1 = Archive, 3 = Timeline over Today,
# 4 = Today, 5 = Atlas, 6 = Places. Each launch re-seeds a year of sample data, hence the long waits.
IPHONE_STORY = (
    ("01-today-dial", ("--seed-screenshot-data", "--default-tab=4"), 10.0),
    ("02-trace-replay", ("--seed-screenshot-data", "--default-tab=0"), 10.0),
    ("03-atlas-year", ("--seed-screenshot-data", "--default-tab=5"), 12.0),
    ("04-places", ("--seed-screenshot-data", "--default-tab=6"), 10.0),
    ("05-export-sentence", ("--seed-screenshot-data", "--default-tab=1"), 10.0),
)
# Onboarding has three pages since 2.0 (OnboardingView.pageCount).
IPAD_STORY = (
    ("01-welcome", 0),
    ("02-stays-on-this-phone", 1),
    ("03-how-much-to-remember", 2),
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
        time.sleep(wait_seconds)
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
        # Screenshot mode seeds today up to the real time, so the clock has to agree with Today's dial.
        "status_bar", device_id, "override", "--time", time.strftime("%H:%M"),
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
    devices: set[str],
) -> None:
    print(f"\n=== {store_locale} ===", flush=True)
    raw_root = OUTPUT_ROOT / "raw" / store_locale

    if "iphone" in devices:
        capture_iphone(store_locale, language, apple_locale, app, iphone_id, copy, python, raw_root)
    if "ipad" in devices:
        capture_ipad(store_locale, language, apple_locale, app, ipad_id)
    if "watch" in devices:
        capture_watch(store_locale, language, apple_locale, watch_app, watch_id)


def capture_iphone(
    store_locale: str, language: str, apple_locale: str, app: Path, iphone_id: str,
    copy: dict, python: str, raw_root: Path,
) -> None:
    status_bar(iphone_id)
    reinstall(iphone_id, BUNDLE_ID, app)
    warm_up_iphone(iphone_id, language, apple_locale)
    for name, arguments, wait_seconds in IPHONE_STORY:
        launch_and_capture(
            iphone_id, BUNDLE_ID, language, apple_locale, arguments,
            raw_root / "iphone" / f"{name}.png", wait_seconds,
            minimum_entropy=5.0,
        )
    compose_iphone(python, copy[store_locale], store_locale, language, raw_root / "iphone")


def capture_ipad(store_locale: str, language: str, apple_locale: str, app: Path, ipad_id: str) -> None:
    reinstall(ipad_id, BUNDLE_ID, app)
    for name, page in IPAD_STORY:
        launch_and_capture(
            ipad_id, BUNDLE_ID, language, apple_locale, (f"--onboarding-page={page}",),
            OUTPUT_ROOT / "appstore" / store_locale / "ipad-129" / f"{name}.png", 6.0,
            minimum_entropy=1.0,
        )


def capture_watch(store_locale: str, language: str, apple_locale: str, watch_app: Path, watch_id: str) -> None:
    reinstall(watch_id, WATCH_BUNDLE_ID, watch_app)
    # Without location access the glance shows a permission prompt instead of the dial.
    simctl("privacy", watch_id, "grant", "location", WATCH_BUNDLE_ID, check=False)
    launch_and_capture(
        watch_id, WATCH_BUNDLE_ID, language, apple_locale, (),
        OUTPUT_ROOT / "appstore" / store_locale / "watch-series-10" / "01-main.png", 5.0,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--locales", default=",".join(LOCALES), help="comma-separated store locales")
    parser.add_argument("--iphone", default="iPhone 18 Pro Max")
    parser.add_argument("--ipad", default="iPad Pro 13-inch (M5)")
    parser.add_argument("--watch", default="Apple Watch Series 11 (46mm)")
    parser.add_argument("--devices", default="iphone,ipad,watch", help="comma-separated: iphone, ipad, watch")
    parser.add_argument("--derived-data", type=Path, default=Path("/tmp/isome-localization-screenshot-derived"))
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--python", default=sys.executable, help="Python with Pillow and Arabic shaping dependencies")
    args = parser.parse_args()

    requested = [locale.strip() for locale in args.locales.split(",") if locale.strip()]
    unknown = sorted(set(requested) - set(LOCALES))
    if unknown:
        raise RuntimeError(f"Unknown locales: {', '.join(unknown)}")

    devices = {device.strip() for device in args.devices.split(",") if device.strip()}
    unknown_devices = sorted(devices - {"iphone", "ipad", "watch"})
    if unknown_devices:
        raise RuntimeError(f"Unknown devices: {', '.join(unknown_devices)}")

    copy = json.loads(COPY_PATH.read_text())
    iphone_id = find_device(args.iphone, "iOS") if "iphone" in devices else ""
    ipad_id = find_device(args.ipad, "iOS") if "ipad" in devices else ""
    watch_id = find_device(args.watch, "watchOS") if "watch" in devices else ""
    for device_id in (iphone_id, ipad_id, watch_id):
        if device_id:
            boot(device_id)
    if iphone_id:
        status_bar(iphone_id)
    if ipad_id:
        status_bar(ipad_id, ipad=True)

    app, watch_app = build_products(args.derived_data, args.skip_build)
    for store_locale in requested:
        language, apple_locale = LOCALES[store_locale]
        capture_locale(
            store_locale, language, apple_locale, app, watch_app,
            iphone_id, ipad_id, watch_id, copy, args.python, devices,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
