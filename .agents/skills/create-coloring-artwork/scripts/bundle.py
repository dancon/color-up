"""Small standard-library helpers shared by the portable asset tools."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

ID = re.compile(r"[a-z][a-z0-9-]{0,79}")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    pending = path.with_name(path.name + ".pending")
    pending.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    pending.replace(path)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def safe_id(value):
    if not isinstance(value, str) or not ID.fullmatch(value):
        raise ValueError("Use a new lowercase ASCII artwork ID, at most 80 characters")
    return value


def inside(root, relative, exists=True):
    root = Path(root).resolve()
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise ValueError("Bundle/catalog paths must be non-empty relative paths")
    path = (root / relative).resolve()
    if not path.is_relative_to(root) or path == root:
        raise ValueError(f"Path escapes its asset directory: {relative}")
    if exists and not path.is_file():
        raise ValueError(f"Missing asset file: {relative}")
    return path
