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
        print("用法: python ocr.py <图片路径>")
        sys.exit(1)
    image = Path(sys.argv[1]).resolve()
    if not image.exists():
        print("文件不存在:", image)
        sys.exit(1)

    try:
        from rapidocr_onnxruntime import RapidOCR
    except ImportError:
        print("请先安装: pip install -r scripts/requirements.txt")
        sys.exit(1)

    engine = RapidOCR()
    result, _ = engine(str(image))
    if not result:
        print("未识别到文字")
        return
    for box, text, score in result:
        print(f"{float(score):.3f}\t{text}")

if __name__ == "__main__":
    main()