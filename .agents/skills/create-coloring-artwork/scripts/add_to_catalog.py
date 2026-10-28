#!/usr/bin/env python3
"""Add approved candidates to a project's relative-path catalog and run its import hooks."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

sys.dont_write_bytecode = True

from bundle import inside, read_json, safe_id, sha256, write_json

FIELDS = ("id", "title", "theme", "themeLabel", "sceneLabel", "featured", "source", "prompt", "report", "geometry", "inkAsset")


def approved_candidate(path):
    candidate = read_json(path)
    root = path.resolve().parent
    artwork_id = safe_id(candidate["id"])
    if candidate.get("schemaVersion") != 1:
        raise ValueError("Unsupported candidate schemaVersion")
    files = {key: inside(root, candidate[key]) for key in ("source", "prompt", "report", "geometry", "preview", "manualReview")}
    digest = sha256(files["source"])
    prompt, report, geometry, review = (read_json(files[key]) for key in ("prompt", "report", "geometry", "manualReview"))
    if files["source"].read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Original artwork must be a PNG")
    if not isinstance(prompt.get("prompt"), str) or not prompt["prompt"].strip():
        raise ValueError("Missing actual generation/edit prompt")
    if prompt.get("sha256") != digest or any(data.get("source_sha256") != digest for data in (report, geometry, review)):
        raise ValueError("Current PNG, prompt, report, geometry and review hashes must agree")
    maximum = candidate.get("maxRegions", 20)
    regions = geometry.get("regions", [])
    if type(maximum) is not int or maximum < 1:
        raise ValueError("Invalid requested maxRegions")
    if (report.get("status") != "pass" or not 1 <= len(regions) <= maximum or
            report.get("region_count") != len(regions) or report.get("settings", {}).get("max_regions") != maximum):
        raise ValueError("Candidate must pass its requested full-region QA limit; never truncate excess regions")
    if geometry.get("page_id") != artwork_id:
        raise ValueError("Geometry identity must equal the new candidate ID")
    for index, region in enumerate(regions):
        if region.get("id") != f"{artwork_id}:r{index:02d}" or region.get("path_scale") != 1 or region.get("fill_rule") != "EVEN_ODD":
            raise ValueError("Geometry must retain ordered IDs, normalized coordinates and EVEN_ODD holes")
    if (review.get("status") != "approved" or report.get("preview_sha256") != sha256(files["preview"]) or
            review.get("preview_sha256") != sha256(files["preview"]) or
            any(review.get(key) is not True for key in ("reviewed_original", "reviewed_preview", "subjects_closed")) or
            not isinstance(review.get("notes"), str) or not review["notes"].strip()):
        raise ValueError("Record real original+mask visual inspection, subject closure and notes before registration")
    if not re.fullmatch(r"illustrated/[a-z][a-z0-9-]{0,79}\.png", candidate.get("inkAsset", "")):
        raise ValueError("inkAsset must be a unique illustrated/<safe-name>.png path")
    return candidate, files


def argv_value(raw):
    value = json.loads(raw)
    if not isinstance(value, list) or not value or any(not isinstance(part, str) or not part for part in value):
        raise ValueError("Import/check hook must be a nonempty JSON argv array; shell commands are not accepted")
    return value


def run_hooks(hooks, root):
    for command in hooks:
        completed = subprocess.run(command, cwd=root, capture_output=True, text=True)
        if completed.returncode:
            raise RuntimeError(f"Import hook failed ({completed.returncode}): {completed.stderr[-4000:]}{completed.stdout[-2000:]}")
        if completed.stdout.strip():
            print(completed.stdout.strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, action="append", type=Path, help="Repeat to register an approved batch with one import/check")
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--project-root", required=True, type=Path)
    parser.add_argument("--replace-id", help="Explicitly retire this entry; single candidate only, and its replacement MUST have a new ID")
    parser.add_argument("--import-hook-json", help="Custom local project importer argv, JSON array")
    parser.add_argument("--check-hook-json", help="Custom strict verification argv, JSON array")
    options = parser.parse_args()
    if options.replace_id and len(options.candidate) != 1:
        parser.error("Use one candidate when declaring --replace-id")
    project = options.project_root.resolve()
    catalog = options.catalog.resolve()
    art_root = catalog.parent
    if not catalog.is_relative_to(project):
        parser.error("Catalog must be inside the explicitly selected project")
    if bool(options.import_hook_json) != bool(options.check_hook_json):
        parser.error("Custom import and strict check hooks must be supplied together")
    if options.import_hook_json:
        hooks = [argv_value(options.import_hook_json), argv_value(options.check_hook_json)]
    else:
        importer = project / "tools/import_illustrations.py"
        if not importer.is_file():
            parser.error("No Color Up importer found; supply explicit local import/check hooks")
        hooks = [[sys.executable, str(importer)], [sys.executable, str(importer), "--check"]]
    candidates = [approved_candidate(path) for path in options.candidate]
    art_root.mkdir(parents=True, exist_ok=True)
    lock = art_root / ".catalog-add.lock"
    descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(descriptor)
    created, staging, original = [], [], None
    completed = False
    try:
        original = catalog.read_bytes() if catalog.exists() else None
        retired_path = art_root / "retired-ids.json"
        retired_original = retired_path.read_bytes() if retired_path.exists() else None
        retired = json.loads(retired_original) if retired_original is not None else {"schemaVersion": 1, "ids": []}
        if retired.get("schemaVersion") != 1 or not isinstance(retired.get("ids"), list):
            raise ValueError("Unsupported retired-ID registry")
        retired_ids = {safe_id(value) for value in retired["ids"]}
        data = json.loads(original) if original is not None else {"schemaVersion": 1, "expectedCount": 0, "pages": []}
        if data.get("schemaVersion") != 1 or data.get("expectedCount") != len(data.get("pages", [])):
            raise ValueError("Existing catalog has an unsupported schema or inconsistent expectedCount")
        existing = data["pages"]
        ids = {page["id"] for page in existing}
        if len(ids) != len(existing):
            raise ValueError("Existing catalog contains duplicate IDs")
        if options.replace_id and options.replace_id not in ids:
            raise ValueError("--replace-id must identify an existing catalog entry")
        new_ids = [candidate["id"] for candidate, _ in candidates]
        if len(set(new_ids)) != len(new_ids) or set(new_ids) & (ids | retired_ids):
            raise ValueError("Never overwrite an existing identity's geometry; choose a NEW versioned ID even for replacement")
        all_ink = [page["inkAsset"] for page in existing] + [candidate["inkAsset"] for candidate, _ in candidates]
        if len(all_ink) != len(set(all_ink)):
            raise ValueError("New artwork must use new inkAsset paths; existing image identities are frozen")
        prepared = []
        for candidate, files in candidates:
            artwork_id = candidate["id"]
            destination = inside(art_root, f"generated/{artwork_id}", exists=False)
            if destination.exists():
                raise ValueError(f"Target bundle already exists; refusing to overwrite: {artwork_id}")
            temp = art_root / (".candidate-" + uuid.uuid4().hex)
            temp.mkdir()
            staging.append(temp)
            relative_names = {"source": "source.png", "prompt": "prompt.json", "report": "qa/report.json",
                              "geometry": f"geometry/{artwork_id}.regions.json", "preview": "qa/regions-preview.png", "manualReview": "review.json"}
            for key, source in files.items():
                target = temp / relative_names[key]
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                if sha256(target) != sha256(source):
                    raise ValueError("Candidate changed during import; freeze it and rerun QA")
            # Revalidate the exact staged bytes, rather than trusting a caller-owned mutable bundle.
            staged_candidate = {**candidate, **relative_names}
            write_json(temp / "candidate.json", staged_candidate)
            approved_candidate(temp / "candidate.json")
            page = {key: candidate[key] for key in FIELDS}
            for key in ("source", "prompt", "report", "geometry"):
                page[key] = f"generated/{artwork_id}/{relative_names[key]}"
            prepared.append((temp, destination, page))
        data["pages"] = [page for page in existing if page["id"] != options.replace_id]
        for temp, destination, page in prepared:
            destination.parent.mkdir(parents=True, exist_ok=True)
            temp.replace(destination)
            staging.remove(temp)
            created.append(destination)
            data["pages"].append(page)
        data["expectedCount"] = len(data["pages"])
        try:
            write_json(catalog, data)
            if options.replace_id:
                retired_ids.add(options.replace_id)
                write_json(retired_path, {"schemaVersion": 1, "ids": sorted(retired_ids)})
            run_hooks(hooks, project)
        except Exception as failed:
            if original is None:
                catalog.unlink(missing_ok=True)
            else:
                pending = catalog.with_name(catalog.name + ".restore")
                pending.write_bytes(original)
                pending.replace(catalog)
            if retired_original is None:
                retired_path.unlink(missing_ok=True)
            else:
                retired_pending = retired_path.with_name(retired_path.name + ".restore")
                retired_pending.write_bytes(retired_original)
                retired_pending.replace(retired_path)
            for directory in created:
                shutil.rmtree(directory)
            created.clear()
            if original is not None:
                try:
                    run_hooks(hooks, project)
                except Exception as recovery:
                    raise RuntimeError(f"Catalog restored, but derived-file recovery also failed: {recovery}") from failed
            raise RuntimeError(f"Catalog restored after failed integration: {failed}") from failed
        completed = True
        print(f"Registered {len(candidates)} approved original(s); expectedCount={data['expectedCount']}; import and strict check passed")
    finally:
        for directory in staging:
            shutil.rmtree(directory, ignore_errors=True)
        if not completed:
            for directory in created:
                shutil.rmtree(directory, ignore_errors=True)
        lock.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, RuntimeError) as error:
        print(f"Catalog integration failed: {error}", file=sys.stderr)
        raise SystemExit(1)
