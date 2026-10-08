#!/usr/bin/env python3
"""Static graph regression for optional reference images in a ComfyUI UI collection.
This test checks authored graph paths; it does not replace Android/device testing.
Usage: python scripts/check_optional_image_branches.py my_collection.json
"""
import collections
import json
import sys

j = json.load(open(sys.argv[1], encoding='utf-8'))
if 'workflow' in j and 'nodes' not in j:
    j = j['workflow']
nodes = {str(n['id']): n for n in j['nodes']}
links = {str(a[0]): a for a in j['links'] if isinstance(a, list) and len(a) >= 5}


def ancestors(output):
    found, pending = set(), [str(output)]
    while pending:
        current = pending.pop()
        if current in found:
            continue
        found.add(current)
        for input_ in nodes[current].get('inputs', []):
            link = input_.get('link')
            if link is not None:
                pending.append(str(links[str(link)][1]))
    return found


high_res = ancestors(670)
assert all(str(n) in high_res for n in (670, 705, 674, 688, 687)), 'high-res reference graph changed'
assert nodes['674']['type'] == 'LoadImage' and nodes['674']['mode'] == 0
assert nodes['687']['type'] == 'LoadImage' and nodes['687']['mode'] == 4
assert nodes['688']['type'] == 'ImageScaleToTotalPixels' and nodes['688']['mode'] == 4
inputs_705 = {v['name']: links[str(v['link'])][1]
              for v in nodes['705']['inputs'] if v.get('link') is not None}
assert inputs_705['images.image_1'] == 708, inputs_705
assert inputs_705['images.image_2'] == 688, inputs_705
assert next(x for x in nodes['688']['inputs'] if x.get('name') == 'image')['link'] == 1010
assert links['1010'][1] == 687

# Opting in to reference 687 can turn on its path to active 705 without enabling the nearby bypassed LoRAs.
consumers = collections.defaultdict(set)
for link in links.values():
    src, dst = str(link[1]), str(link[3])
    if src in high_res and dst in high_res:
        consumers[src].add(dst)
activated, todo = set(), ['687']
while todo:
    nid = todo.pop()
    if nid in activated or nodes[nid].get('mode', 0) != 4:
        continue
    activated.add(nid)
    todo.extend(consumers[nid])
assert activated == {'687', '688'}, activated
assert {'690', '692', '696'}.isdisjoint(activated), 'bypassed LoRA should not be enabled'
print('PASS high-resolution main image 674 remains available')
print('PASS optional reference 687 -> 688 -> 705 images.image_2 path exists')
print('PASS enabling reference activates ONLY bypassed image nodes 687, 688')
print('PASS unrelated bypassed LoRAs are not activated')
print('NOTE static checks are not live ComfyUI output tests')
