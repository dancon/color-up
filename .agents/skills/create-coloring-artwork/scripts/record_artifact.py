#!/usr/bin/env python3
"""Copy a selected built-in image result into a candidate and record its real source hash."""
import argparse
import hashlib
from pathlib import Path
import shutil
import sys

sys.dont_write_bytecode = True

from bundle import inside, read_json, sha256, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--generated", required=True, type=Path)
    parser.add_argument("--replace-candidate", action="store_true", help="Explicitly select a revised draft, retaining earlier provenance")
    options = parser.parse_args()
    candidate = read_json(options.candidate)
    root = options.candidate.resolve().parent
    target = inside(root, candidate["source"], exists=False)
    if target.exists() and not options.replace_candidate:
        parser.error("Candidate already has an image; use --replace-candidate only for an authorized local revision")
    source = options.generated.resolve()
    if not source.is_file() or source.read_bytes()[:8] != b"\x89PNG\r\n\x1a\n":
        parser.error("The selected generated result must be a PNG")
    prompt_path = inside(root, candidate["prompt"])
    prompt = read_json(prompt_path)
    if target.exists():
        previous = {key: prompt.get(key) for key in ("sha256", "generatedSource")}
        previous["prompt"] = prompt.get("recordedPrompt", prompt["prompt"])
        prompt.setdefault("attempts", []).append(previous)
    target.parent.mkdir(parents=True, exist_ok=True)
    if source != target:
        shutil.copyfile(source, target)
    prompt.update(sha256=sha256(target), generatedSource=str(source), tool="built-in image_gen",
                  promptSha256=hashlib.sha256(prompt["prompt"].encode()).hexdigest())
    prompt["recordedPrompt"] = prompt["prompt"]
    write_json(prompt_path, prompt)
    # A new image invalidates prior visual approval even if old reports still exist.
    review = inside(root, candidate["manualReview"], exists=False)
    write_json(review, dict(status="pending", source_sha256=prompt["sha256"], preview_sha256=None,
                           reviewed_original=False, reviewed_preview=False, subjects_closed=False, notes=""))
    print(f"Recorded {candidate['id']}: {prompt['sha256']}; source preserved; rerun QA and visual review")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print(f"Record failed: {error}", file=sys.stderr)
        raise SystemExit(1)
