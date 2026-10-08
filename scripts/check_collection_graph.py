#!/usr/bin/env python3
"""Static fixture check for grouped ComfyUI UI workflow files.

Usage: python scripts/check_collection_graph.py /path/to/workflow.json
Only verifies group attribution and output-rooted graph reachability; it is NOT
an Android build or a live /object_info validator.
"""
import collections
import json
import sys

workflow = json.load(open(sys.argv[1], encoding="utf-8"))
if "workflow" in workflow and "nodes" not in workflow:
    workflow = workflow["workflow"]
nodes = {str(n["id"]): n for n in workflow["nodes"]}
links = {str(link[0]): link for link in workflow["links"] if isinstance(link, list) and len(link) >= 5}
groups = workflow.get("groups", [])
outputs = []
for n in nodes.values():
    if not n.get("type", "").lower().startswith("saveimage"):
        continue
    x, y = n["pos"]
    candidates = [(g["bounding"][2] * g["bounding"][3], i) for i, g in enumerate(groups)
                  if g["bounding"][0] <= x <= g["bounding"][0] + g["bounding"][2]
                  and g["bounding"][1] <= y <= g["bounding"][1] + g["bounding"][3]]
    if candidates:
        outputs.append((n, min(candidates)[1]))
assert len({i for _, i in outputs}) >= 2, "Not a grouped multi-output workflow"
for output, group_id in outputs:
    visited, todo = set(), [str(output["id"])]
    while todo:
        node_id = todo.pop()
        if node_id in visited:
            continue
        assert node_id in nodes, f"Missing node {node_id}"
        visited.add(node_id)
        for inp in nodes[node_id].get("inputs", []):
            lid = inp.get("link")
            if lid is not None:
                assert str(lid) in links, f"Missing link {lid}"
                todo.append(str(links[str(lid)][1]))
    saves = [nodes[i] for i in visited if nodes[i]["type"].lower().startswith("saveimage")]
    assert len(saves) == 1 and saves[0]["id"] == output["id"], "Branch reaches multiple final outputs"
    print(f"OK {groups[group_id]['title']}: root={output['id']}, deps={len(visited)}, "
          f"original_mode={output.get('mode', 0)}, original_bypasses={sum(nodes[i].get('mode')==4 for i in visited)}")
print(f"PASS {len(outputs)} separate SaveImage output roots")
