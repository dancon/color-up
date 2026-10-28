#!/usr/bin/env python3
"""Prepare self-contained candidate bundles and image prompts from one or more themes."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import uuid

sys.dont_write_bytecode = True

from bundle import safe_id, write_json


def prompt_for(title, scene, age, size, maximum, target_min, target_max):
    return (
        f"Use case: illustration-story. Create ONE finished professional SIMPLE children's coloring-book illustration "
        f"for ages {age}, square {size}x{size}. Subject/theme: {title}. Scene and story beat: {scene}. "
        "Expert illustrator's expressive organic drawing, confident flowing curves, tasteful varied line weight, "
        "charming asymmetry and a lively playful pose. Main subject fills about 70–80% with safe white margins. "
        f"Prefer {target_min}–{target_max} LARGE enclosed white colorable areas, hard maximum {maximum} across "
        "the ENTIRE picture including background; simpler is fine. Broad finger-friendly shapes, near-empty white "
        "background, only one or two large supporting props. Crisp bold BLACK continuous CLOSED boundaries on pure "
        "WHITE, roughly 6px outer/4px interior lines at 1024px. Solid-black simple eyes, open smile curves. "
        "No narrow white rings, double wheel rims, tiny trapped wedges, eye-white slivers, screws, fur texture, "
        "dense grids, little decorations, small bubble clusters or background ornaments. No color, gray shading, "
        "hatching, sketch marks, paper texture, text, logo, border or watermark. Professional charming picture-book "
        "ink drawing; NEVER an assembly of procedural geometric clip-art parts."
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--theme", action="append", default=[], help="Repeat for a set of subjects; preserves the user's wording")
    parser.add_argument("--themes-json", type=Path, help="Array of theme strings or objects with title/scene/id/series metadata")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--id", help="Explicit new identity for a single theme")
    parser.add_argument("--scene", help="Specific action/story beat for a single theme")
    parser.add_argument("--age", default="5–12")
    parser.add_argument("--size", type=int, default=1024)
    parser.add_argument("--max-regions", type=int, default=20)
    parser.add_argument("--target-min", type=int)
    parser.add_argument("--target-max", type=int)
    parser.add_argument("--series", default="custom")
    parser.add_argument("--series-label", default="自选主题")
    options = parser.parse_args()
    if options.size < 256 or options.max_regions < 1:
        parser.error("Image size must be >=256 and max-regions must be positive")
    themes = list(options.theme)
    if options.themes_json:
        loaded = json.loads(options.themes_json.read_text(encoding="utf-8"))
        if not isinstance(loaded, list):
            parser.error("--themes-json must contain an array")
        themes += loaded
    if not themes:
        parser.error("Provide --theme or --themes-json")
    if len(themes) != 1 and (options.id or options.scene):
        parser.error("For a batch, put individual id/scene fields in --themes-json")
    low = options.target_min if options.target_min is not None else min(10, max(1, options.max_regions - 4))
    high = options.target_max if options.target_max is not None else min(16, options.max_regions)
    if not 1 <= low <= high <= options.max_regions:
        parser.error("Target range must be positive and within max-regions")
    planned = []
    for entry in themes:
        entry = {"title": entry} if isinstance(entry, str) else entry
        if not isinstance(entry, dict) or not isinstance(entry.get("title", entry.get("theme")), str):
            parser.error("Each theme needs a non-empty title/theme")
        title = entry.get("title", entry.get("theme", "")).strip()
        if not title:
            parser.error("Theme must not be blank")
        slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
        default_id = "art-" + (slug[:45] or hashlib.sha256(title.encode()).hexdigest()[:10]) + "-" + uuid.uuid4().hex[:10]
        artwork_id = safe_id(entry.get("id") or options.id or default_id)
        scene = entry.get("scene") or options.scene or title
        if not isinstance(scene, str) or not scene.strip():
            parser.error("Scene must be a non-empty string")
        if any(key in entry and (not isinstance(entry[key], str) or not entry[key].strip())
               for key in ("series", "themeLabel", "sceneLabel")):
            parser.error("Series and scene labels must be non-empty strings")
        if "featured" in entry and type(entry["featured"]) is not bool:
            parser.error("featured must be a JSON boolean")
        planned.append((artwork_id, title, scene, entry))
    if len({item[0] for item in planned}) != len(planned):
        parser.error("Theme IDs must be unique; supply explicit IDs for duplicate themes")
    if any((options.out / item[0]).exists() for item in planned):
        parser.error("A target bundle already exists; choose a new versioned ID, do not overwrite it")
    for artwork_id, title, scene, entry in planned:
        root = options.out / artwork_id
        root.mkdir(parents=True)
        (root / "qa").mkdir()
        (root / "geometry").mkdir()
        prompt = prompt_for(title, scene, options.age, options.size, options.max_regions, low, high)
        write_json(root / "brief.json", dict(id=artwork_id, title=title, scene=scene, age=options.age,
                   imageSize=options.size, maxRegions=options.max_regions, targetRegions=[low, high],
                   generationTool="built-in image_gen", status="prepared"))
        write_json(root / "prompt.json", dict(id=artwork_id, title=title, prompt=prompt, tool="built-in image_gen",
                   sha256=None, promptSha256=hashlib.sha256(prompt.encode()).hexdigest(), attempts=[]))
        write_json(root / "candidate.json", dict(schemaVersion=1, id=artwork_id, title=title,
                   theme=entry.get("series", options.series), themeLabel=entry.get("themeLabel", options.series_label),
                   sceneLabel=entry.get("sceneLabel") or entry.get("themeLabel") or title,
                   featured=bool(entry.get("featured", False)),
                   source="source.png", prompt="prompt.json", report="qa/report.json", preview="qa/regions-preview.png",
                   geometry=f"geometry/{artwork_id}.regions.json", manualReview="review.json",
                   inkAsset=f"illustrated/{artwork_id}.png", maxRegions=options.max_regions))
        write_json(root / "review.json", dict(status="pending", source_sha256=None, preview_sha256=None,
                   reviewed_original=False, reviewed_preview=False, subjects_closed=False, notes=""))
        print(str((root / "candidate.json").resolve()))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print(f"Prepare failed: {error}", file=sys.stderr)
        raise SystemExit(1)
