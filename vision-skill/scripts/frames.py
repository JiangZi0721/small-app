import os
import sys
from pathlib import Path

def _force_utf8():
    for stream in (sys.stdout, sys.stderr):
        if stream and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8")
            except Exception:
                pass

_force_utf8()

def main():
    if len(sys.argv) < 2:
        print("用法: python frames.py <视频路径> [--interval 秒数] [--out 输出目录]")
        sys.exit(1)
    video = Path(sys.argv[1]).resolve()
    if not video.exists():
        print("文件不存在:", video)
        sys.exit(1)

    interval = 1.0
    out_dir = Path("assets/frames")
    args = sys.argv[2:]
    for i, a in enumerate(args):
        if a == "--interval" and i + 1 < len(args):
            interval = float(args[i + 1])
        if a == "--out" and i + 1 < len(args):
            out_dir = Path(args[i + 1])

    try:
        import cv2
    except ImportError:
        print("请先安装: pip install -r scripts/requirements.txt")
        sys.exit(1)

    cap = cv2.VideoCapture(str(video))
    fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
    frame_step = max(1, int(round(fps * interval)))
    out_dir.mkdir(parents=True, exist_ok=True)

    index = 0
    saved = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if index % frame_step == 0:
            out_path = out_dir / f"frame_{index:06d}.jpg"
            cv2.imwrite(str(out_path), frame)
            saved += 1
        index += 1
    cap.release()
    print(f"共读取 {index} 帧，保存 {saved} 帧到 {out_dir}")

if __name__ == "__main__":
    main()