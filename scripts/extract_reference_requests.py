"""Verify and export the synthetic requests captured by the native reference test."""
import json
from pathlib import Path
import sys

prefix = 'REFERENCE_REQUESTS='
records = [line.split(prefix, 1)[1] for line in Path(sys.argv[1]).read_text(encoding='utf-8').splitlines() if prefix in line]
assert len(records) == 1, 'Missing or duplicate native request evidence'
requests = json.loads(records[0])
assert len(requests) == 2
for graph in requests:
    assert '295' not in graph
    assert graph['308']['inputs']['image'] == 'mobile_A.png'
    assert graph['316']['inputs']['ref_images.ref_image_0'] == ['308', 0]
    assert 'ref_images.ref_image_1' not in graph['316']['inputs']
    assert 'original_C.png' not in json.dumps(graph)
Path(sys.argv[2]).write_text(json.dumps(requests, indent=2), encoding='utf-8')
print('Verified two actual Android submissions: only A, no second loader or reference connection.')
