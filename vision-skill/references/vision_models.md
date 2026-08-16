# 视觉模型选型参考

## 外部多模态 API（联网）
- 千问 `qwen-vl-max`：中文描述强，DashScope 兼容接口。
- 千问 `qwen-vl-plus`：速度快，成本更低。
- OpenAI `gpt-4o-mini`：英文/通用场景好，OpenAI 兼容接口。
- 任意 OpenAI 兼容多模态服务：只需改 `VISION_BASE_URL`、`VISION_MODEL`。

## 本地 OCR
- RapidOCR（onnxruntime）：中文好、离线、无需系统 tesseract。
- Tesseract：老牌方案，但中文需额外语言包，本技能不依赖。

## 本地目标检测
- Ultralytics YOLOv8：使用简单，`yolov8n.pt` 是轻量预训练模型。
- 模型缓存建议放 `VISION_CACHE_DIR`，避免占用系统盘。