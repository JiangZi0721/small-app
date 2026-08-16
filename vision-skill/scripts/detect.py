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
        print("用法: python detect.py <图片路径> [--conf 阈值]")
        sys.exit(1)
    image = Path(sys.argv[1]).resolve()
    if not image.exists():
        print("文件不存在:", image)
        sys.exit(1)

    conf = 0.4
    args = sys.argv[2:]
    for i, a in enumerate(args):
        if a == "--conf" and i + 1 < len(args):
            conf = float(args[i + 1])

    try:
        from ultralytics import YOLO
    except ImportError:
        print("请先安装: pip install ultralytics")
        sys.exit(1)

    model_path = os.environ.get("VISION_YOLO_MODEL", "yolov8n.pt")
    model = YOLO(model_path)
    results = model(str(image), conf=conf, verbose=False)
    found = 0
    for r in results:
        for box in r.boxes:
            cls_id = int(box.cls[0])
            score = float(box.conf[0])
            xyxy = [round(float(v), 1) for v in box.xyxy[0].tolist()]
            print(f"{r.names[cls_id]}\t{score:.3f}\t{xyxy}")
            found += 1
    if not found:
        print("未检测到目标")

if __name__ == "__main__":
    main()