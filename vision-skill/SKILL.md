---
name: vision
description: 为 Codex 提供视觉识别能力。支持本地图片路径、URL 和图片 data URL 的描述、视觉问答、OCR 文字识别、目标检测和视频抽帧。当当前模型不支持图像输入时，必须使用外部多模态 API 脚本而不是直接调用 view_image。使用场景：用户分享图片、要求分析/描述/识别图片、提取图中文字、检测图中物体、分析视频画面。
---

# 视觉识别

## 重要：非视觉模型的兜底

- `view_image` 只适用于当前模型本身支持图像输入的情况。添加 skill 不能给文本模型增加原生视觉能力。
- 当前模型不支持图像输入时，不要调用 `view_image`，也不要直接把图片内容喂给当前模型；改为让外部多模态 API 处理。
- 如果消息带有本地图片附件，优先取附件对应的本地路径；如果只拿到 `view_image` 返回的 `data:image/...;base64,...` 文本，先把它原样写入 UTF-8 临时文件，再用 `--data-file` 调用脚本。

## 工具选择

1. 当前模型支持图像输入时：简单看图或视觉问答优先使用内置 `view_image` 工具，零依赖。
2. 当前模型不支持图像输入时：不要调用 `view_image`，改走外部多模态 API。
3. 外部多模态 API：先 `cd` 到本技能目录，再运行：
   ```bash
   node --env-file=.env scripts/vision_api.js "图片路径" "问题"
   node --env-file=.env scripts/vision_api.js --url "图片URL" "问题"
   node --env-file=.env scripts/vision_api.js --data-file "保存view_image结果的临时文件" "问题"
   ```
4. 本地 OCR：`python scripts/ocr.py "图片路径"`。
5. 本地目标检测：`python scripts/detect.py "图片路径" --conf 0.4`。
6. 视频：`python scripts/frames.py "视频路径" --interval 1`，再对输出帧调用上述能力。

## 图片附件与 data URL

- 如果消息带有本地图片附件，优先取附件对应的本地路径，直接传给 `vision_api.js`。
- 如果只拿到 `view_image` 返回的 `data:image/...;base64,...` 文本，先把它原样写入一个 UTF-8 临时文件，再用 `--data-file` 调用脚本。
- 对较小的图片，也可直接把 `data:` 字符串作为 `<图片路径>` 参数传入，脚本会识别并直接发给多模态 API。

## 前置配置

- 复制 `.env.example` 为 `.env`，填写 `VISION_API_KEY` 和 `VISION_MODEL`。
- 本地 OCR 依赖：`pip install -r scripts/requirements.txt`。
- 本地目标检测依赖（可选，体积大）：`pip install ultralytics`。
- 建议把 Python 依赖和模型缓存放到非系统盘，例如 `F:\DevTools\vision`。