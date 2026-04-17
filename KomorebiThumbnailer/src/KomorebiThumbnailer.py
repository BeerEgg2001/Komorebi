import argparse
import subprocess
import sys
import json
import math
from pathlib import Path

def find_executable(name, custom_dir):
    """ffmpeg や ffprobe の実行ファイルの場所を特定する"""
    if custom_dir:
        p = Path(custom_dir) / f"{name}.exe"
        if p.exists(): return str(p)
        p = Path(custom_dir) / name
        if p.exists(): return str(p)

    base_dir = Path(sys.executable).parent if getattr(sys, 'frozen', False) else Path(__file__).parent
    p = base_dir / f"{name}.exe"
    if p.exists(): return str(p)
    p = base_dir / name
    if p.exists(): return str(p)

    return name

def get_video_duration(ffprobe_cmd, input_path):
    """ffprobeを使用して動画の長さ（秒）を取得する"""
    cmd = [
        ffprobe_cmd, 
        "-v", "error", 
        "-show_entries", "format=duration", 
        "-of", "default=noprint_wrappers=1:nokey=1", 
        str(input_path)
    ]
    try:
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        return float(result.stdout.strip())
    except Exception as e:
        print(f"Error getting duration: {e}")
        return 86400.0

def main():
    parser = argparse.ArgumentParser(description="Komorebi Thumbnailer for EDCB")
    parser.add_argument("input_video", help="録画ファイルのパス (.ts / .mp4)")
    parser.add_argument("--ffmpeg-dir", help="ffmpeg/ffprobe が配置されているディレクトリ", default=None)
    parser.add_argument("--out-dir", help="出力先ディレクトリ (未指定時は動画と同じ場所)", default=None)

    args = parser.parse_args()
    input_path = Path(args.input_video)

    if not input_path.exists():
        print(f"Error: Input file not found - {input_path}")
        sys.exit(1)

    ffmpeg_cmd = find_executable("ffmpeg", args.ffmpeg_dir)
    ffprobe_cmd = find_executable("ffprobe", args.ffmpeg_dir)
    out_dir = Path(args.out_dir) if args.out_dir else input_path.parent
    
    # 出力ファイルパス
    output_webp = out_dir / f"{input_path.name}.tile.webp"
    output_json = out_dir / f"{input_path.name}.tile.json"

    # --- サムネイル仕様 (KonomiTV準拠) ---
    tile_width = 320
    tile_height = 180
    interval_sec = 10.0
    max_cols = 50  # 50列 x 320px = 横幅 16000px (WebP上限16383pxを回避)
    max_rows = 90  # 90行 x 180px = 縦幅 16200px (約12.5時間分)

    print(f"Analyzing video duration for {input_path.name}...")
    duration_sec = get_video_duration(ffprobe_cmd, input_path)
    print(f"Duration: {duration_sec:.2f} seconds")

    # JSON出力用・FFmpeg用のメタデータを計算
    total_tiles = math.ceil(duration_sec / interval_sec)
    if total_tiles < 1: total_tiles = 1
    if total_tiles > max_cols * max_rows: total_tiles = max_cols * max_rows # 上限クリップ

    column_count = min(total_tiles, max_cols)
    row_count = math.ceil(total_tiles / column_count) if column_count > 0 else 1

    image_width = column_count * tile_width
    image_height = row_count * tile_height

    # ★ 修正: FFmpegのフィルターに、固定の50x90ではなく実際の行数(row_count)を渡す
    filter_complex = f"fps=1/{interval_sec},scale={tile_width}:{tile_height},tile={column_count}x{row_count}"

    ffmpeg_command = [
        ffmpeg_cmd,
        "-y",
        "-skip_frame", "nokey",
        "-i", str(input_path),
        "-vf", filter_complex,
        "-c:v", "libwebp",
        "-lossless", "0",
        "-q:v", "75",
        "-compression_level", "6",
        str(output_webp)
    ]

    print(f"Generating thumbnail WebP ({column_count} cols x {row_count} rows)...")
    try:
        subprocess.run(ffmpeg_command, check=True)
        print(f"Success! Saved WebP to {output_webp}")
        
        # WebP生成成功後、JSONを書き出す
        tile_info = {
            "image_width": image_width,
            "image_height": image_height,
            "tile_width": tile_width,
            "tile_height": tile_height,
            "column_count": column_count,
            "row_count": row_count,
            "interval_sec": interval_sec,
            "total_tiles": total_tiles
        }
        
        with open(output_json, "w", encoding="utf-8") as f:
            json.dump(tile_info, f, indent=4)
        print(f"Success! Saved JSON metadata to {output_json}")

    except subprocess.CalledProcessError as e:
        print(f"Error executing FFmpeg: {e}")
        sys.exit(1)
    except FileNotFoundError:
        print("Error: FFmpeg not found. Please place ffmpeg.exe/ffprobe.exe in the same folder or specify --ffmpeg-dir.")
        sys.exit(1)

if __name__ == "__main__":
    main()