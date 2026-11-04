#!/usr/bin/env python3
"""Place the rendered iPhone Duo screenshots and product page header on one App Store version.

App Store Connect only takes these through the Asset Library API (as in 1.8.2's
whereday-duo-v1). For each locale this uploads the images, replaces the version's existing
IPHONE_DUO_PROFILE screenshots and PRODUCT_PAGE_HEADER_ASSET, and orders the Duo set.
It never submits anything. A receipt makes reruns resume instead of re-uploading.

Usage: place-assets.py --version-id ID --images DIR --receipt FILE [--locales en-US,...]
"""

import argparse
import hashlib
import json
import subprocess
import time
import urllib.request
from pathlib import Path

APP_ID = "6761960794"
DUO = ("APP_SCREENSHOT", "IPHONE_DUO_PROFILE")
HEADER = ("PRODUCT_PAGE_HEADER_ASSET", "DEFAULT_PROFILE")
READY = {"UPLOAD_COMPLETE", "PREPARE_FOR_SUBMISSION", "APPROVED", "WAITING_FOR_REVIEW"}


def api(method, route, body=None):
    command = ["asc", "api", method, route, "--allow-unknown-path"]
    if body is not None:
        command += ["--confirm", "--body-file", "-"]
    if method == "DELETE":
        command += ["--confirm"]
    result = subprocess.run(command, input=json.dumps(body) if body is not None else None,
                            capture_output=True, text=True, check=True)
    return json.loads(result.stdout) if result.stdout.strip() else {}


def upload(library, path: Path, reference, receipt, save, category="APP_SCREENSHOTS_AND_PREVIEWS"):
    binary = path.read_bytes()
    digest = hashlib.sha256(binary).hexdigest()
    item = receipt["images"].get(digest)
    if item:
        resource = api("GET", "/v1/appAssetLibraryImages/" + item["id"])["data"]
    else:
        resource = api("POST", "/v1/appAssetLibraryImages", {"data": {
            "type": "appAssetLibraryImages",
            "attributes": {"fileName": path.name, "fileSize": len(binary),
                           "category": category, "referenceName": reference},
            "relationships": {"assetLibrary": {"data": {"type": "appAssetLibraries", "id": library}}}}})["data"]
        item = {"id": resource["id"], "file": str(path)}
        receipt["images"][digest] = item
        save()
    if resource["attributes"]["state"] == "AWAITING_UPLOAD":
        for op in resource["attributes"]["uploadOperations"]:
            headers = {h["name"]: h["value"] for h in op["requestHeaders"]}
            request = urllib.request.Request(op["url"], data=binary[op["offset"]:op["offset"] + op["length"]],
                                             headers=headers, method=op["method"])
            with urllib.request.urlopen(request, timeout=300) as response:
                assert 200 <= response.status < 300
        api("PATCH", "/v1/appAssetLibraryImages/" + resource["id"], {"data": {
            "type": "appAssetLibraryImages", "id": resource["id"], "attributes": {"uploaded": True}}})
    return item


def wait_ready(item):
    for _ in range(120):
        state = api("GET", "/v1/appAssetLibraryImages/" + item["id"])["data"]["attributes"]["state"]
        item["state"] = state
        if state in READY:
            return
        if "FAILED" in state:
            raise RuntimeError(f"{item['file']}: {state}")
        time.sleep(5)
    raise TimeoutError(item["file"])


def place(localization, image_id, kind):
    return api("POST", "/v1/appAssetLibraryPlacements", {"data": {
        "type": "appAssetLibraryPlacements",
        "attributes": {"placementType": kind[0], "placementGroup": kind[1]},
        "relationships": {
            "image": {"data": {"type": "appAssetLibraryImages", "id": image_id}},
            "appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations", "id": localization}}}}})["data"]["id"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version-id", required=True)
    parser.add_argument("--images", type=Path, required=True, help="render output: LOCALE/{duo-inner,duo-outer,header}/*.png")
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--locales")
    args = parser.parse_args()

    receipt = json.loads(args.receipt.read_text()) if args.receipt.exists() else {"versionId": args.version_id,
                                                                                  "images": {}, "locales": {}}
    save = lambda: args.receipt.write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n")
    library = api("GET", f"/v1/apps/{APP_ID}/assetLibrary")["data"]["id"]
    localizations = {item["attributes"]["locale"]: item["id"] for item in
                     api("GET", f"/v1/appStoreVersions/{args.version_id}/appStoreVersionLocalizations")["data"]}
    locales = args.locales.split(",") if args.locales else sorted(localizations)

    for locale in locales:
        localization = localizations[locale]
        folder = args.images / locale
        duo = sorted((folder / "duo-inner").glob("*.png")) + sorted((folder / "duo-outer").glob("*.png"))
        header = sorted((folder / "header").glob("*.png"))
        assert len(duo) == 8 and len(header) == 1, (locale, len(duo), len(header))

        items = [upload(library, path, f"Whereday 2.0 / {locale} / {path.parent.name}/{path.name}", receipt, save)
                 for path in duo]
        # Header images are creative assets, not screenshots; the wrong category fails with SPEC_MISMATCH.
        items += [upload(library, path, f"Whereday 2.0 / {locale} / header", receipt, save, "CREATIVE_ASSETS")
                  for path in header]
        for item in items:
            wait_ready(item)
        save()

        record = receipt["locales"].setdefault(locale, {"localizationId": localization})
        if record.get("done"):
            print(locale, "already placed", flush=True)
            continue
        existing = api("GET", f"/v1/appStoreVersionLocalizations/{localization}/placements")["data"]
        removed = [p["id"] for p in existing
                   if (p["attributes"]["placementType"], p["attributes"]["placementGroup"]) in (DUO, HEADER)]
        for placement in removed:
            api("DELETE", "/v1/appAssetLibraryPlacements/" + placement)
        record["removedPlacements"] = removed
        record["duoPlacements"] = [place(localization, item["id"], DUO) for item in items[:8]]
        record["headerPlacement"] = place(localization, items[8]["id"], HEADER)
        api("POST", "/v1/appAssetLibraryPlacementOrderingRequests", {"data": {
            "type": "appAssetLibraryPlacementOrderingRequests",
            "attributes": {"placementGroup": DUO[1]},
            "relationships": {
                "orderedPlacements": {"data": [{"type": "appAssetLibraryPlacements", "id": pid}
                                               for pid in record["duoPlacements"]]},
                "appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations", "id": localization}}}}})
        record["done"] = True
        save()
        print(locale, "placed", len(record["duoPlacements"]), "Duo + header; removed", len(removed), flush=True)


if __name__ == "__main__":
    main()
