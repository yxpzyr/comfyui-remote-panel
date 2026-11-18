import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path

SOURCE=Path(__file__).resolve().parents[1]/'ComfyUI_Remote_H3_Mobile/__init__.py'
folder_paths=types.ModuleType('folder_paths')
sys.modules.setdefault('folder_paths',folder_paths)
spec=importlib.util.spec_from_file_location('mobile_h3',SOURCE)
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OutputTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        self.run=self.root/'minimax_seg_export'/'run_a'
        self.run.mkdir(parents=True)
        folder_paths.get_output_directory=lambda:str(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def report(self,paths):
        return '\n'.join(f'Segment {i+1}/{len(paths)}: mp4 saved → {path}' for i,path in enumerate(paths))

    def files(self):
        paths=[self.run/f'seg_{i:04d}.mp4' for i in range(2)]
        for path in paths:path.write_bytes(b'fixture')
        return paths

    def test_uses_report_paths_not_unrelated_newest_folder(self):
        paths=self.files()
        other=self.root/'minimax_seg_export'/'zz_other_project'
        other.mkdir()
        (other/'seg_0000.mp4').write_bytes(b'not this project')
        self.assertEqual(module.segment_files(self.report(paths),2,self.root),paths)

    def test_latest_explicit_trim_replaces_earlier_report_entry(self):
        paths=self.files()
        report=self.report(paths)+f'\nSegment 1: mp4 updated after continuity trim → {paths[0]}'
        self.assertEqual(module.segment_files(report,2,self.root),paths)

    def test_rejects_partial_run(self):
        paths=self.files()
        with self.assertRaisesRegex(ValueError,'完整'):
            module.segment_files(self.report(paths[:1]),2,self.root)

    def test_rejects_path_outside_segment_directory(self):
        path=self.root/'seg_0000.mp4';path.write_bytes(b'private')
        with self.assertRaisesRegex(ValueError,'目录'):
            module.segment_files(self.report([path]),1,self.root)

    def test_rejects_mixed_runs(self):
        paths=self.files()
        other=self.root/'minimax_seg_export'/'another'
        other.mkdir();moved=other/'seg_0001.mp4';moved.write_bytes(b'old')
        with self.assertRaisesRegex(ValueError,'多个'):
            module.segment_files(self.report([paths[0],moved]),2,self.root)

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'),'FFmpeg required')
    def test_real_merge_returns_standard_video_metadata_and_exact_frame_count(self):
        paths=[]
        for i,color in enumerate(['red','blue']):
            path=self.run/f'seg_{i:04d}.mp4'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i',f'color={color}:s=64x64:r=24:d=1',
                '-f','lavfi','-i','sine=frequency=440:sample_rate=32000:duration=1','-c:v','libx264','-pix_fmt','yuv420p',
                '-c:a','aac','-ac','2',str(path)],check=True)
            paths.append(path)
        result=module.ComfyRemoteH3VideoOutput().export(self.report(paths),2,24,'test-request')
        ref=result['ui']['videos'][0]
        output=self.root/ref['subfolder']/ref['filename']
        info=module.probe_video(output)
        self.assertEqual(info['frames'],48)
        self.assertAlmostEqual(info['duration'],2,places=3)
        self.assertTrue(info['audio'])
        self.assertEqual(ref['type'],'output')
        self.assertEqual(json.loads(output.with_suffix('.json').read_text())['request_id'],'test-request')


if __name__=='__main__':unittest.main()
