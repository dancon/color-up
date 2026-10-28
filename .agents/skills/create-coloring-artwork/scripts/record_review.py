#!/usr/bin/env python3
"""Record an agent's actual visual review of the current original and numbered mask."""
import argparse
from pathlib import Path
import sys

sys.dont_write_bytecode = True

from bundle import inside, read_json, sha256, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--reviewed-original", action="store_true")
    parser.add_argument("--reviewed-preview", action="store_true")
    parser.add_argument("--subjects-closed", action="store_true")
    parser.add_argument("--notes", required=True)
    options = parser.parse_args()
    if not all((options.reviewed_original, options.reviewed_preview, options.subjects_closed)) or not options.notes.strip():
        parser.error("Approval requires actual original+preview inspection, closed subjects and concrete notes")
    candidate = read_json(options.candidate)
    root = options.candidate.resolve().parent
    digest = sha256(inside(root, candidate["source"]))
    report = read_json(inside(root, candidate["report"]))
    preview_digest = sha256(inside(root, candidate["preview"]))
    if (report.get("status") != "pass" or report.get("source_sha256") != digest or
            report.get("preview_sha256") != preview_digest):
        parser.error("The current original must first pass its real QA analysis")
    target = inside(root, candidate["manualReview"], exists=False)
    write_json(target, dict(status="approved", source_sha256=digest,
               preview_sha256=preview_digest, reviewed_original=True,
               reviewed_preview=True, subjects_closed=True, notes=options.notes.strip()))
    print(f"Recorded visual review for {candidate['id']}; this record is not a substitute for inspecting images")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print(f"Review record failed: {error}", file=sys.stderr)
        raise SystemExit(1)
