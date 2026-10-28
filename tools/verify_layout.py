#!/usr/bin/env python3
"""Check tablet layout from an actual `adb shell uiautomator dump` XML snapshot."""
import argparse
import json
import re
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("snapshot")
parser.add_argument("--density", type=float, required=True, help="Display density, for example 2 for 320dpi")
args = parser.parse_args()
if args.density <= 0:
    parser.error("density must be positive")
nodes = list(ET.parse(args.snapshot).getroot().iter("node"))


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))


canvas = next(node for node in nodes if "涂色画板" in node.get("content-desc", ""))
paper = bounds(canvas)
names = {"返回画册", "橡皮擦", "撤销一笔", "重新画，可以撤销", "另存为 PNG 图片", "打开声音", "关闭声音"}


def descriptions(node):
    """Compose may publish a merged button's label as a same-bounds synthetic child."""
    result = {node.get("content-desc", "")}
    for child in node:
        if child.get("clickable") != "true" and bounds(child) == bounds(node):
            result.update(descriptions(child))
    return result


tools = [node for node in nodes if node.get("clickable") == "true" and descriptions(node) & names]
assert len(tools) == 6, "The six toolbar controls must be present"
assert paper[2] - paper[0] == paper[3] - paper[1], "The painting surface must be square"
gap = (paper[1] - max(bounds(node)[3] for node in tools)) / args.density
assert gap >= 12, f"Toolbar overlaps or crowds the paper: {gap}dp"
targets = [node for node in nodes if node.get("clickable") == "true" or node.get("content-desc", "").endswith("蜡笔")]
for node in targets:
    left, top, right, bottom = bounds(node)
    assert right - left >= 48 * args.density and bottom - top >= 48 * args.density, node.attrib
print(json.dumps({
    "snapshot": args.snapshot,
    "square_canvas_pixels": paper,
    "visible_control_to_canvas_gap_dp": gap,
    "touch_targets_at_least_48dp": True,
}, ensure_ascii=False))
