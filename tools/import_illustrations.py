#!/usr/bin/env python3
"""Import approved original PNG illustrations and their derived clipping geometry.

Uses Python's standard library only. No drawing is synthesized or traced here: the
PNG is copied byte-for-byte, and boundaries come from approved analysis exports.
artwork/catalog.json declares the complete gallery and its expected page count.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import struct
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ARTWORK = ROOT / "artwork"
MANIFEST = ARTWORK / "catalog.json"
ASSETS = ROOT / "app/src/main/assets/illustrated"
OUTPUT = ROOT / "coloring-core/src/main/kotlin/com/colorup/core/GeneratedIllustrations.kt"
ID_PATTERN = re.compile(r"[a-z][a-z0-9-]*")
TOKEN = re.compile(r"[MLZ]|-?\d+(?:\.\d+)?")


def kotlin_string(value):
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def finite_coordinate(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("A boundary coordinate must be numeric")
    if not math.isfinite(value) or not 0 <= value <= 1000:
        raise ValueError("Illustration boundaries must use finite 1000×1000 coordinates")
    return float(value)


def path_contours(path):
    """Validate exported polygon commands, without changing or smoothing their geometry."""
    if not isinstance(path, str) or not path.strip() or TOKEN.sub("", path).strip():
        raise ValueError("Only exported M/L/Z clipping paths are accepted")
    tokens = TOKEN.findall(path)
    contours, current, cursor = [], [], 0
    while cursor < len(tokens):
        command = tokens[cursor]
        cursor += 1
        if command == "Z":
            if len(current) < 3:
                raise ValueError("A clipping contour must contain at least three points")
            contours.append(current)
            current = []
            continue
        if command not in ("M", "L") or cursor + 1 >= len(tokens):
            raise ValueError("Malformed exported clipping path")
        if (command == "M") != (not current):
            raise ValueError("Each contour must start with M and close with Z")
        current.append([finite_coordinate(float(tokens[cursor])), finite_coordinate(float(tokens[cursor + 1]))])
        cursor += 2
    if current or not contours:
        raise ValueError("Clipping contours must be explicitly closed")
    return contours


def png_dimensions(path):
    with path.open("rb") as source:
        header = source.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError(f"Invalid original PNG header: {path.name}")
    width, height = struct.unpack(">II", header[16:24])
    if width != height or width < 256:
        raise ValueError(f"Expected a square original illustration: {path.name}")
    return [width, height]


def artifact_path(value):
    """Manifest paths stay relative to artwork/, including future generated/ candidates."""
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError("Artwork paths must be nonempty relative POSIX paths")
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Artwork paths must stay inside artwork/")
    resolved = (ARTWORK / relative).resolve()
    if not resolved.is_relative_to(ARTWORK.resolve()):
        raise ValueError("Artwork paths must not escape artwork/ through a symlink")
    return resolved


def validate_active_ids(metadata, registry):
    """Retired identities cannot be rebound to new geometry, including via manual edits."""
    if not registry.exists():
        return
    record = json.loads(registry.read_text())
    if not isinstance(record, dict) or type(record.get("schemaVersion")) is not int or record["schemaVersion"] != 1:
        raise ValueError("Unsupported retired artwork ID registry schema")
    retired = record.get("ids")
    if not isinstance(retired, list) or any(not isinstance(ident, str) or not ID_PATTERN.fullmatch(ident) for ident in retired):
        raise ValueError("Retired artwork IDs must be safe identifiers")
    if len(retired) != len(set(retired)):
        raise ValueError("Retired artwork IDs must be unique")
    reused = {page["id"] for page in metadata}.intersection(retired)
    if reused:
        raise ValueError("Retired artwork IDs cannot be reused: " + ", ".join(sorted(reused)))


def load_pages():
    manifest = json.loads(MANIFEST.read_text())
    if manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported artwork catalog schema")
    expected = manifest.get("expectedCount")
    metadata = manifest.get("pages")
    if type(expected) is not int or expected < 1 or not isinstance(metadata, list) or len(metadata) != expected:
        raise ValueError("Catalog page count must match its positive expectedCount")
    if len({page["id"] for page in metadata}) != expected:
        raise ValueError("Artwork catalog IDs must be unique")
    validate_active_ids(metadata, ARTWORK / "retired-ids.json")
    asset_names = set()
    missing, pages = [], []
    for page in metadata:
        page_id = page["id"]
        if not isinstance(page_id, str) or not ID_PATTERN.fullmatch(page_id):
            raise ValueError(f"Unsafe illustration ID: {page_id!r}")
        for field in ("title", "theme", "themeLabel"):
            if not isinstance(page.get(field), str) or not page[field].strip():
                raise ValueError(f"Missing illustration {field}: {page_id}")
        if not isinstance(page.get("sceneLabel"), str) or type(page.get("featured")) is not bool:
            raise ValueError(f"Invalid sceneLabel/featured metadata: {page_id}")
        source, prompt, report, geometry = (artifact_path(page[field]) for field in ("source", "prompt", "report", "geometry"))
        ink_asset = page["inkAsset"]
        if not isinstance(ink_asset, str) or not re.fullmatch(r"illustrated/[a-z][a-z0-9-]*\.png", ink_asset):
            raise ValueError(f"inkAsset must name an owned illustrated PNG: {page_id}")
        asset_name = ink_asset.removeprefix("illustrated/")
        if asset_name in asset_names:
            raise ValueError("Each illustrated page must have a unique PNG asset name")
        asset_names.add(asset_name)
        absent = [kind for kind, path in (("PNG", source), ("prompt", prompt), ("regions", geometry), ("QA", report)) if not path.is_file()]
        if absent:
            missing.append(f"{page_id}: {', '.join(absent)}")
            continue
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        prompted = json.loads(prompt.read_text())
        exported = json.loads(geometry.read_text())
        checked = json.loads(report.read_text())
        if exported.get("schema_version") != 1 or exported.get("page_id") != page_id:
            raise ValueError(f"Wrong clipping identity/schema: {page_id}")
        if prompted.get("sha256") != digest or exported.get("source_sha256") != digest or checked.get("source_sha256") != digest:
            raise ValueError(f"PNG and its prompt/geometry/QA SHA-256 disagree: {page_id}")
        if not isinstance(prompted.get("prompt"), str) or not prompted["prompt"].strip():
            raise ValueError(f"The full generation prompt must be recorded: {page_id}")
        size = png_dimensions(source)
        if exported.get("source_size") != size or checked.get("source_size") != size:
            raise ValueError(f"Derived geometry used the wrong actual PNG dimensions: {page_id}")
        regions = exported.get("regions", [])
        if not 1 <= len(regions) <= 20 or checked.get("region_count") != len(regions):
            raise ValueError(f"Expected at most 20 substantive regions including background: {page_id}")
        if checked.get("status") != "pass":
            raise ValueError(f"Illustration has not passed the automated QA gate: {page_id}")
        for index, region in enumerate(regions):
            if region.get("id") != f"{page_id}:r{index:02d}":
                raise ValueError(f"Unstable or duplicated region identity: {page_id}")
            if region.get("path_scale") != 1 or region.get("fill_rule") != "EVEN_ODD":
                raise ValueError(f"Expected normalized EVEN_ODD clipping geometry: {page_id}")
            parsed = path_contours(region.get("path_data"))
            contours = region.get("contours")
            if not isinstance(contours, list) or parsed != contours:
                raise ValueError(f"Hit-test contours and clipping paths disagree: {region['id']}")
        pages.append(dict(metadata=page, source=source, asset_name=asset_name, sha256=digest, page_id=page_id, regions=regions))
    if missing:
        raise ValueError("Incomplete illustrated gallery; no files were written:\n  " + "\n  ".join(missing))
    return pages


def literal(value):
    # Avoid the JVM's per-string UTF-8 constant limit without compile-time concatenation folding.
    if len(value.encode()) < 48_000:
        return kotlin_string(value)
    chunks = [kotlin_string(value[index:index + 12_000]) for index in range(0, len(value), 12_000)]
    return "listOf(" + ", ".join(chunks) + ').joinToString("")'


def generate(pages):
    lines = [
        "// Generated by tools/import_illustrations.py. Do not edit; original PNGs are the artwork.",
        "// Extracted contours are only for region clipping/hit tests, never replacement line art.",
        "package com.colorup.core", "", "internal object GeneratedIllustrations {",
        f"    const val expectedPageCount: Int = {len(pages)}",
        "    val pages: List<LineArt> = listOf(" + ", ".join(f"page{index}()" for index in range(len(pages))) + ")", "",
    ]
    for index, entry in enumerate(pages):
        page, page_id = entry["metadata"], entry["page_id"]
        lines += [f"    // {page_id}: SHA-256 {entry['sha256']}", f"    private fun page{index}() = LineArt(",
                  f"        id = {kotlin_string(page_id)}, title = {kotlin_string(page['title'])},",
                  f"        subtitle = {kotlin_string(page.get('sceneLabel') or page['themeLabel'])},",
                  "        regions = listOf(" + ", ".join(f"region{index}_{region}()" for region in range(len(entry["regions"]))) + "),",
                  "        details = emptyList(),",
                  f"        theme = {kotlin_string(page['theme'])}, themeLabel = {kotlin_string(page['themeLabel'])},",
                  f"        sceneLabel = {kotlin_string(page.get('sceneLabel', ''))}, featured = {str(page.get('featured', False)).lower()},",
                  f"        inkAsset = {kotlin_string(page['inkAsset'])},", "    )", ""]
        for position, region in enumerate(entry["regions"]):
            lines += [f"    private fun region{index}_{position}() = ColoringRegion(",
                      f"        id = {kotlin_string(region['id'])}, name = {kotlin_string(region['name'])}, outlineWidth = 0f,",
                      f"        shape = boundary({literal(' '.join(TOKEN.findall(region['path_data'])))}),", "    )", ""]
    lines += [
        "    /** Decode the imported M/L/Z boundary once; no Android parser or drawing logic. */",
        "    private fun boundary(pathData: String): Shape.Vector {",
        "        val tokens = pathData.split(' ')",
        "        val contours = mutableListOf<List<Point>>()",
        "        var current = mutableListOf<Point>()",
        "        var cursor = 0",
        "        while (cursor < tokens.size) {",
        "            when (tokens[cursor++]) {",
        '                "M", "L" -> current.add(Point(tokens[cursor++].toFloat(), tokens[cursor++].toFloat()))',
        '                "Z" -> { contours.add(current); current = mutableListOf() }',
        '                else -> error("Invalid generated illustration boundary")',
        "            }",
        "        }",
        "        check(current.isEmpty() && contours.isNotEmpty())",
        "        return Shape.Vector(pathData, 1f, contours, FillRule.EVEN_ODD)",
        "    }", "}", "",
    ]
    return "\n".join(lines)


def self_test():
    """Exercise ID retirement using isolated files, without modifying gallery artifacts."""
    with tempfile.TemporaryDirectory(prefix="color-up-retired-ids-") as directory:
        registry = Path(directory) / "retired-ids.json"
        validate_active_ids([{"id": "fresh-moon"}], registry)
        registry.write_text(json.dumps({"schemaVersion": 1, "ids": ["cat", "retired-moon"]}))
        validate_active_ids([{"id": "fresh-moon"}, {"id": "art2-cat"}], registry)
        for ident in ("cat", "retired-moon"):
            try:
                validate_active_ids([{"id": ident}], registry)
            except ValueError as error:
                assert "Retired artwork IDs cannot be reused" in str(error)
            else:
                raise AssertionError("Retired identity was accepted: " + ident)
        registry.write_text(json.dumps({"schemaVersion": 99, "ids": []}))
        try:
            validate_active_ids([{"id": "fresh-moon"}], registry)
        except ValueError:
            pass
        else:
            raise AssertionError("An invalid retirement registry must fail closed")
    print("Importer self-check passed: absent registry/new IDs accepted, retired IDs and invalid registry rejected.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Read-only: verify the complete manifest, generated Kotlin and copied original PNGs")
    parser.add_argument("--self-test", action="store_true", help="Read-only: exercise retired-ID checks in a temporary directory")
    options = parser.parse_args()
    if options.self_test:
        self_test()
        if not options.check:
            return
    pages = load_pages()
    code = generate(pages)
    if options.check:
        if not OUTPUT.is_file() or OUTPUT.read_text() != code:
            raise ValueError("Generated illustrated catalog is stale; run python3 tools/import_illustrations.py")
        if {path.name for path in ASSETS.glob("*.png")} != {entry["asset_name"] for entry in pages}:
            raise ValueError("Copied illustrated PNG assets must match the complete manifest")
        for entry in pages:
            target = ASSETS / entry["asset_name"]
            if hashlib.sha256(target.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError(f"Copied PNG is not byte-identical to its original: {target.name}")
    else:
        ASSETS.mkdir(parents=True, exist_ok=True)
        for entry in pages:
            target = ASSETS / entry["asset_name"]
            shutil.copyfile(entry["source"], target)
            if hashlib.sha256(target.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError(f"Original PNG changed while importing; regenerate after it is frozen: {target.name}")
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        pending = OUTPUT.with_suffix(".kt.tmp")
        pending.write_text(code)
        pending.replace(OUTPUT)
        # Only this owned directory is pruned; originals and other asset directories are untouched.
        expected = {entry["asset_name"] for entry in pages}
        for target in ASSETS.glob("*.png"):
            if target.name not in expected:
                target.unlink()
    print(f"Illustrations {'verified' if options.check else 'generated'}: {len(pages)} original PNGs, "
          f"{sum(len(page['regions']) for page in pages)} regions; every page <=20; source/geometry SHA-256 match.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
