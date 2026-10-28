#!/usr/bin/env python3
"""Project entry point for the portable coloring-artwork skill's canonical analyzer."""
from pathlib import Path
import runpy

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).resolve().parents[1] / ".agents/skills/create-coloring-artwork/scripts/analyze_illustration.py"), run_name="__main__")
