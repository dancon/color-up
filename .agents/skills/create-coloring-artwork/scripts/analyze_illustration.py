#!/usr/bin/env python3
"""Read-only closed-region quality gate for original PNG coloring illustrations.

Run with a Python environment installed from the bundled requirements.lock.
The original image is never overwritten.
Only explicit --report/--preview/--export paths produce derived QA/geometry files.
All substantive regions count: the program never truncates a busy image to 20.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile

try:
    import cv2
    import numpy as np
    from PIL import Image
except ImportError as error:
    raise SystemExit(f"Install this skill's dependencies with uv pip sync {Path(__file__).with_name('requirements.lock')} --python <venv-python>.") from error


@dataclass(frozen=True)
class Settings:
    threshold: int = 160
    close_kernel: int = 3
    max_regions: int = 20
    target_min: int = 10
    target_max: int = 16
    min_area_ratio: float = .0008
    min_touch_diameter: float = 44.0
    require_square: bool = True
    contour_error: float = .5
    # Fixed tiny highlights must satisfy ALL three conditions. These are intentionally
    # conservative and are not exposed as CLI knobs for concealing excessive region counts.
    micro_max_area_ratio: float = .00015
    micro_max_diameter: float = 16.0
    micro_max_extent: float = 26.0
    max_micro_details: int = 12


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_gray(path: Path):
    with Image.open(path) as original:
        if original.format != "PNG":
            raise ValueError("Source must be a PNG illustration")
        width, height = original.size
        if min(width, height) < 256 or max(width, height) > 8192:
            raise ValueError("Expected a PNG between 256 and 8192 pixels per edge")
        rgba = np.asarray(original.convert("RGBA"), dtype=np.uint8)
    # Analyze the visible image on white without editing the source's bytes or alpha.
    alpha = rgba[:, :, 3:4].astype(np.float32) / 255.0
    rgb = np.rint(rgba[:, :, :3] * alpha + 255 * (1 - alpha)).astype(np.uint8)
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY), rgb


def components(free_space, settings: Settings):
    count, labels, stats, _ = cv2.connectedComponentsWithStats(free_space, connectivity=4)
    height, width = free_space.shape
    unit = 1000.0 / max(width, height)
    border = set(map(int, np.unique(np.concatenate((labels[0], labels[-1], labels[:, 0], labels[:, -1]))))) - {0}
    result = []
    for label in range(1, count):
        x, y, w, h, area = map(int, stats[label])
        local = (labels[y:y+h, x:x+w] == label).astype(np.uint8)
        padded = np.pad(local, 1, mode="constant")
        distance = cv2.distanceTransform(padded, cv2.DIST_L2, cv2.DIST_MASK_PRECISE)
        _, radius, _, center = cv2.minMaxLoc(distance)
        diameter = radius * 2 * unit
        area_ratio = area / (width * height)
        is_micro = (
            label not in border and area_ratio <= settings.micro_max_area_ratio
            and diameter <= settings.micro_max_diameter
            and max(w, h) * unit <= settings.micro_max_extent
        )
        result.append({
            "label": label,
            "area_pixels": area,
            "area_ratio": round(area_ratio, 8),
            "bbox_pixels": [x, y, w, h],
            "bbox_design": [round(value * unit, 3) for value in (x, y, w, h)],
            "max_touch_diameter_design": round(diameter, 3),
            "touch_center_design": [round((x + center[0] - 1) * unit, 3), round((y + center[1] - 1) * unit, 3)],
            "touches_border": label in border,
            "classification": "fixed_micro_detail" if is_micro else "region",
            "too_small": not is_micro and (area_ratio < settings.min_area_ratio or diameter < settings.min_touch_diameter),
        })
    result.sort(key=lambda item: (item["bbox_pixels"][1], item["bbox_pixels"][0], -item["area_pixels"]))
    return result, labels


def analyze(path: Path, settings: Settings = Settings()):
    gray, rgb = load_gray(path)
    height, width = gray.shape
    ink = (gray < settings.threshold).astype(np.uint8) * 255
    if settings.close_kernel > 1:
        kernel = np.ones((settings.close_kernel, settings.close_kernel), np.uint8)
        closed = cv2.morphologyEx(ink, cv2.MORPH_CLOSE, kernel)
    else:
        closed = ink.copy()
    raw_components, _ = components(255 - ink, settings)
    measured, labels = components(255 - closed, settings)
    regions = [item for item in measured if item["classification"] == "region"]
    micro = [item for item in measured if item["classification"] == "fixed_micro_detail"]
    small = [item for item in regions if item["too_small"]]
    raw_count = sum(item["classification"] == "region" for item in raw_components)
    added = int(np.count_nonzero((closed > 0) & (ink == 0)))
    ink_fraction = float(np.count_nonzero(ink)) / gray.size
    tonal_fraction = float(np.count_nonzero((gray > 48) & (gray < 240))) / gray.size
    color_spread = rgb.max(axis=2).astype(np.int16) - rgb.min(axis=2).astype(np.int16)
    colored_fraction = float(np.count_nonzero(color_spread > 24)) / gray.size
    reject, warnings = [], []
    if settings.require_square and width != height:
        reject.append("non_square_source")
    if len(regions) > settings.max_regions:
        reject.append("more_than_20_substantive_regions" if settings.max_regions == 20 else "region_limit_exceeded")
    if small:
        reject.append("substantive_regions_too_small_to_paint_comfortably")
    if len(micro) > settings.max_micro_details:
        reject.append("excessive_micro_details")
    if ink_fraction < .001:
        reject.append("missing_or_insufficient_line_art")
    if ink_fraction > .24:
        reject.append("excessive_ink_density")
    if tonal_fraction > .12:
        reject.append("excessive_gray_shading")
    if colored_fraction > .01:
        reject.append("source_contains_substantial_color")
    if not settings.target_min <= len(regions) <= settings.target_max:
        warnings.append("outside_preferred_region_range")
    if len(regions) - raw_count > 4:
        reject.append("many_enclosures_depend_on_closing_gaps")
    if added / gray.size > .003:
        reject.append("too_many_mask_pixels_require_gap_repair")
    if sum(item["touches_border"] for item in regions) > 1:
        warnings.append("multiple_border_regions_check_for_cropped_objects")
    for index, item in enumerate(regions):
        item["region_index"] = index
    report = {
        "schema_version": 1,
        "source": str(path.resolve()),
        "source_sha256": digest(path),
        "source_size": [width, height],
        "status": "reject" if reject else "pass",
        "rejection_reasons": reject,
        "warnings": warnings,
        "settings": asdict(settings),
        "region_count": len(regions),
        "raw_region_count": raw_count,
        "fixed_micro_detail_count": len(micro),
        "small_substantive_region_count": len(small),
        "minimum_region_area_pixels": min((item["area_pixels"] for item in regions), default=0),
        "minimum_region_area_ratio": min((item["area_ratio"] for item in regions), default=0),
        "minimum_touch_diameter_design": min((item["max_touch_diameter_design"] for item in regions), default=0),
        "ink_fraction": round(ink_fraction, 6),
        "tonal_shading_fraction": round(tonal_fraction, 6),
        "colored_fraction": round(colored_fraction, 6),
        "gap_repair_added_pixels": added,
        "gap_repair_fraction": round(added / gray.size, 8),
        "regions": regions,
        "fixed_micro_details": micro,
        "note": "Counts do not certify composition or character design; inspect original artwork visually. No regions were truncated.",
    }
    return report, labels


def save_preview(path: Path, report, labels):
    height, width = labels.shape
    preview = np.full((height, width, 3), 35, dtype=np.uint8)
    for item in report["fixed_micro_details"]:
        preview[labels == item["label"]] = (205, 205, 205)
    for item in report["regions"]:
        index = item["region_index"]
        color = ((67 * index + 80) % 160 + 75, (103 * index + 40) % 160 + 75, (43 * index + 110) % 160 + 75)
        preview[labels == item["label"]] = color
        cx, cy = item["touch_center_design"]
        center = (round(cx * width / 1000), round(cy * height / 1000))
        cv2.putText(preview, str(index + 1), center, cv2.FONT_HERSHEY_SIMPLEX,
                    max(.35, width / 1700), (0, 0, 0), max(1, width // 900), cv2.LINE_AA)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(preview).save(path)


def export_geometry(directory: Path, page_id: str, report, labels):
    if report["status"] != "pass":
        raise ValueError("Rejected artwork cannot export clipping regions; revise the original illustration and analyze again")
    height, width = labels.shape
    scale = 1000.0 / width
    regions = []
    for item in report["regions"]:
        mask = (labels == item["label"]).astype(np.uint8) * 255
        raw, _ = cv2.findContours(mask, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
        contours = []
        for contour in raw:
            if len(contour) < 3 or cv2.contourArea(contour) == 0:
                continue
            simplified = cv2.approxPolyDP(contour, report["settings"]["contour_error"] / scale, True)
            if len(simplified) < 3:
                simplified = contour
            points = [[round(float(x) * scale, 3), round(float(y) * scale, 3)] for x, y in simplified[:, 0, :]]
            contours.append(points)
        if not contours:
            raise ValueError(f"Region {item['region_index']} has no closed contours")
        commands = []
        for contour in contours:
            commands.append("M " + " L ".join(f"{x:g} {y:g}" for x, y in contour) + " Z")
        regions.append({
            "id": f"{page_id}:r{item['region_index']:02d}",
            "name": f"区域{item['region_index'] + 1}",
            "path_data": " ".join(commands),
            "path_scale": 1.0,
            "fill_rule": "EVEN_ODD",
            "contours": contours,
            "area_ratio": item["area_ratio"],
            "touch_center_design": item["touch_center_design"],
        })
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / f"{page_id}.regions.json"
    if target.resolve() == Path(report["source"]).resolve():
        raise ValueError("Geometry export must not overwrite its source illustration")
    payload = {"schema_version": 1, "page_id": page_id, "source_sha256": report["source_sha256"],
               "source_size": report["source_size"], "settings": report["settings"], "regions": regions}
    target.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    return target


def self_test():
    def base():
        return np.full((512, 512), 255, dtype=np.uint8)

    with tempfile.TemporaryDirectory(prefix="color-up-illustration-qa-") as temporary:
        directory = Path(temporary)
        def check(name, image):
            path = directory / f"{name}.png"
            Image.fromarray(image).save(path)
            before = digest(path)
            result, labels = analyze(path)
            assert digest(path) == before, "Analyzing must not modify original artwork"
            return result, labels

        simple = base()
        cv2.circle(simple, (256, 256), 145, 0, 5)
        passed, labels = check("simple", simple)
        assert passed["status"] == "pass", passed["rejection_reasons"]
        assert passed["region_count"] == 2
        geometry = export_geometry(directory, "art2-test", passed, labels)
        assert len(json.loads(geometry.read_text())["regions"]) == 2

        crowded = base()
        for row in range(3):
            for column in range(7):
                cv2.rectangle(crowded, (20 + column*68, 90 + row*100), (65 + column*68, 135 + row*100), 0, 4)
        rejected, labels = check("too-many", crowded)
        assert rejected["region_count"] == 22 and rejected["status"] == "reject"
        try:
            export_geometry(directory, "art2-rejected", rejected, labels)
        except ValueError:
            pass
        else:
            raise AssertionError("More than 20 regions must not export or be truncated")

        highlight = simple.copy()
        cv2.circle(highlight, (245, 245), 18, 0, -1)
        cv2.circle(highlight, (239, 239), 2, 255, -1)
        highlighted, _ = check("highlight", highlight)
        assert highlighted["region_count"] == 2 and highlighted["fixed_micro_detail_count"] == 1

        narrow = simple.copy()
        cv2.rectangle(narrow, (440, 160), (449, 350), 0, 2)
        thin, _ = check("narrow", narrow)
        assert thin["small_substantive_region_count"] > 0 and thin["status"] == "reject"

        gapped = base()
        cv2.rectangle(gapped, (100, 100), (400, 400), 0, 4)
        gapped[98:103, 249:251] = 255
        closed, _ = check("gap", gapped)
        assert closed["region_count"] == 2 and closed["raw_region_count"] == 1
    print("Self-tests passed: complete region counting, >20 rejection, micro highlights, thin-region rejection, gap diagnostics, immutable source, contour export.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", nargs="?", type=Path)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--report", type=Path, help="Write the complete QA JSON report, including rejection reasons")
    parser.add_argument("--summary", action="store_true", help="Print summary metrics; detailed components remain in --report")
    parser.add_argument("--preview", type=Path, help="Write a derived, numbered component mask for inspection")
    parser.add_argument("--export", type=Path, metavar="DIRECTORY", help="Export closed clip/hit geometry only if QA passes")
    parser.add_argument("--page-id", help="Stable new artwork identity, e.g. dinosaur-postman-v1")
    parser.add_argument("--threshold", type=int, default=160)
    parser.add_argument("--close-kernel", type=int, default=3)
    parser.add_argument("--max-regions", type=int, default=20, help="User-selected limit; default 20, never silently loosen to pass a failed picture")
    parser.add_argument("--min-touch-diameter", type=float, default=44)
    parser.add_argument("--allow-non-square", action="store_true", help="Only when the requested deliverable is not square; project adapters may require square")
    options = parser.parse_args()
    if options.self_test:
        self_test()
        if options.image is None:
            return 0
    if options.image is None:
        parser.error("Provide a PNG illustration or --self-test")
    if options.max_regions < 1:
        parser.error("--max-regions must be positive and match the user's actual requirement")
    if not 1 <= options.threshold <= 254:
        parser.error("--threshold must be between 1 and 254")
    if options.close_kernel not in (1, 3, 5):
        parser.error("--close-kernel supports 1, 3, or 5 pixels")
    if not 20 <= options.min_touch_diameter <= 150:
        parser.error("--min-touch-diameter must be between 20 and 150 design units")
    settings = Settings(threshold=options.threshold, close_kernel=options.close_kernel,
                        max_regions=options.max_regions, min_touch_diameter=options.min_touch_diameter,
                        require_square=not options.allow_non_square)
    result, labels = analyze(options.image, settings)
    destinations = [path for path in (options.report, options.preview) if path is not None]
    if any(path.resolve() == options.image.resolve() for path in destinations):
        parser.error("Derived outputs must not overwrite the source PNG")
    if options.preview:
        save_preview(options.preview, result, labels)
        result["preview_sha256"] = digest(options.preview)
    if options.export:
        page_id = options.page_id or re.sub(r"[^A-Za-z0-9._-]+", "-", options.image.stem).strip("-")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}", page_id):
            parser.error("Export identity must be a safe ASCII name of at most 80 characters")
        if result["status"] == "pass":
            result["geometry_export"] = str(export_geometry(options.export, page_id, result, labels).resolve())
    if options.report:
        options.report.parent.mkdir(parents=True, exist_ok=True)
        options.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    printed = {key: value for key, value in result.items() if key not in ("regions", "fixed_micro_details")} if options.summary else result
    print(json.dumps(printed, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "pass" else 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError) as error:
        print(f"Illustration analysis failed: {error}", file=sys.stderr)
        sys.exit(1)
