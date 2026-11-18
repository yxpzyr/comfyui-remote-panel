"""Return an H3 Director segmented run as a standard ComfyUI video output."""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import uuid
from datetime import datetime
from fractions import Fraction
from pathlib import Path

import folder_paths

REPORT_CLIP = re.compile(r'^Segment\s+(\d+)(?:/\d+)?:\s*mp4 (?:saved|updated after continuity trim)\s*→\s*(.+?)\s*$')


def segment_files(report: str, expected: int, output_root: Path) -> list[Path]:
    """Use this execution's explicit saved/updated paths, never a newest-folder guess."""
    output_root = output_root.resolve()
    export_root = (output_root / 'minimax_seg_export').resolve()
    clips = {}
    for line in str(report).splitlines():
        match = REPORT_CLIP.match(line.strip())
        if match is None:
            continue
        number = int(match.group(1)) - 1
        path = Path(match.group(2)).resolve(strict=True)
        if not path.is_relative_to(export_root) or not path.is_file():
            raise ValueError('导演台片段路径不在当前 ComfyUI 分段输出目录内')
        if path.name != f'seg_{number:04d}.mp4':
            raise ValueError('导演台报告的片段编号与文件名不一致')
        clips[number] = path
    if set(clips) != set(range(expected)):
        raise ValueError(f'本次只返回片段 {[n + 1 for n in sorted(clips)]}，需要完整的1至{expected}段；手机版请完整生成全部分镜')
    ordered = [clips[i] for i in range(expected)]
    if len({path.parent for path in ordered}) != 1:
        raise ValueError('本次片段来自多个运行目录，停止自动合成以免混入旧作品')
    return ordered


def probe_video(path: Path) -> dict:
    executable = shutil.which('ffprobe')
    if not executable:
        raise RuntimeError('未找到 ffprobe，请安装配套桌面环境中的 FFmpeg')
    raw = subprocess.check_output([executable, '-v', 'error', '-show_streams', '-of', 'json', str(path)])
    streams = json.loads(raw)['streams']
    video = next((stream for stream in streams if stream.get('codec_type') == 'video'), None)
    if video is None:
        raise ValueError('片段没有可读取的视频流')
    return dict(width=int(video['width']), height=int(video['height']),
                fps=float(Fraction(video['r_frame_rate'])), duration=float(video['duration']),
                frames=int(video.get('nb_frames') or 0),
                audio=any(stream.get('codec_type') == 'audio' for stream in streams))


def ffmpeg_binary() -> str:
    executable = shutil.which('ffmpeg')
    if executable:
        return executable
    try:
        from imageio_ffmpeg import get_ffmpeg_exe
        return get_ffmpeg_exe()
    except ImportError as error:
        raise RuntimeError('未找到 FFmpeg，请安装配套桌面环境') from error


def check_interrupted():
    try:
        import comfy.model_management
    except ImportError:
        return
    comfy.model_management.throw_exception_if_processing_interrupted()


def merge_segments(clips: list[Path], destination: Path, fps: float) -> dict:
    info = [probe_video(path) for path in clips]
    if any(abs(item['fps'] - fps) > .001 for item in info):
        raise ValueError('片段帧率与导演台设置不一致')
    if len({(item['width'], item['height']) for item in info}) != 1:
        raise ValueError('片段分辨率不一致，请统一后再合成')
    if len({item['audio'] for item in info}) != 1:
        raise ValueError('部分片段缺少声音，停止合成以免丢失音轨')
    audio = info[0]['audio']
    command = [ffmpeg_binary(), '-hide_banner', '-loglevel', 'error', '-n']
    filters = []
    for i, (clip, item) in enumerate(zip(clips, info)):
        command += ['-i', str(clip)]
        filters.append(f'[{i}:v]setpts=PTS-STARTPTS[v{i}]')
        if audio:
            filters.append(f'[{i}:a]atrim=duration={item["duration"]},asetpts=PTS-STARTPTS,aresample=32000,aformat=channel_layouts=stereo[a{i}]')
    inputs = ''.join(f'[v{i}]' + (f'[a{i}]' if audio else '') for i in range(len(clips)))
    filters.append(inputs + f'concat=n={len(clips)}:v=1:a={int(audio)}[v]' + ('[a]' if audio else ''))
    command += ['-filter_complex', ';'.join(filters), '-map', '[v]']
    if audio:
        command += ['-map', '[a]', '-c:a', 'aac', '-b:a', '192k', '-ar', '32000', '-ac', '2']
    command += ['-c:v', 'libx264', '-preset', 'fast', '-crf', '18', '-pix_fmt', 'yuv420p',
                '-r', str(fps), '-movflags', '+faststart', str(destination)]
    process = subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    try:
        while True:
            try:
                _, stderr = process.communicate(timeout=.25)
                break
            except subprocess.TimeoutExpired:
                check_interrupted()
        if process.returncode:
            raise RuntimeError('视频合成失败：' + stderr.decode('utf-8', errors='replace')[-1800:])
    except BaseException:
        process.kill()
        process.communicate()
        if destination.exists():
            destination.unlink()
        raise
    result = probe_video(destination)
    expected_frames = sum(item['frames'] for item in info)
    if expected_frames and result['frames'] != expected_frames:
        destination.unlink()
        raise RuntimeError('合成后的帧数与各片段之和不一致，未返回不完整成片')
    return result


def progress(node_id: str, percent: int, message: str):
    try:
        from server import PromptServer
        server = PromptServer.instance
        server.send_sync('comfyremote_h3_progress', dict(node_id=str(node_id), percent=percent, message=message), server.client_id)
    except (ImportError, AttributeError):
        pass


class ComfyRemoteH3VideoOutput:
    @classmethod
    def INPUT_TYPES(cls):
        return {'required': {
            'report': ('STRING', {'forceInput': True}),
            'expected_segments': ('INT', {'default': 2, 'min': 1, 'max': 10000}),
            'frame_rate': ('FLOAT', {'default': 24.0, 'min': 1, 'max': 240}),
            'request_id': ('STRING', {'default': ''}),
        }, 'hidden': {'unique_id': 'UNIQUE_ID'}}

    RETURN_TYPES = ('STRING',)
    RETURN_NAMES = ('video_file',)
    OUTPUT_NODE = True
    FUNCTION = 'export'
    CATEGORY = 'MiniMaxH3/mobile'

    def export(self, report, expected_segments, frame_rate, request_id, unique_id=None):
        root = Path(folder_paths.get_output_directory()).resolve()
        clips = segment_files(report, int(expected_segments), root)
        destination_dir = root / 'comfyremote_h3'
        destination_dir.mkdir(parents=True, exist_ok=True)
        filename = f'H3_{datetime.now():%Y%m%d_%H%M%S}_{uuid.uuid4().hex[:10]}.mp4'
        destination = destination_dir / filename
        progress(unique_id, 95, f'正在合成 {len(clips)} 段完整视频')
        result = merge_segments(clips, destination, float(frame_rate))
        manifest = dict(request_id=str(request_id), segments=[str(path.relative_to(root)) for path in clips], result=result)
        destination.with_suffix('.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
        progress(unique_id, 99, '完整视频已保存，正在同步到手机图库')
        item = dict(filename=filename, subfolder='comfyremote_h3', type='output', format='video/mp4',
                    frame_rate=float(frame_rate), width=result['width'], height=result['height'])
        return {'ui': {'videos': [item]}, 'result': ('comfyremote_h3/' + filename,)}


NODE_CLASS_MAPPINGS = {'ComfyRemoteH3VideoOutput': ComfyRemoteH3VideoOutput}
NODE_DISPLAY_NAME_MAPPINGS = {'ComfyRemoteH3VideoOutput': 'H3 手机端完整视频输出'}
