# Vision Skill：给 Agent 一个“图片路径”，而不是给 DeepSeek 发图片

这是 `small-app` 仓库里的一个独立子项目，提供 Codex / Agent 视觉识别能力：图片描述、视觉问答、OCR 文字识别、目标检测和视频抽帧。

## 重要结论：为什么不能直接给 DeepSeek 发图片

当前 Agent 如果底层使用的是 **DeepSeek 文本模型**，它会缺少视觉输入能力：

- 把图片直接粘贴到对话里，Codex 会尝试让当前模型“看懂”图片。
- 如果模型不支持视觉，就会出现：`该模型不可视觉识别：vision`。
- Skill、脚本、工具只能改变“输入/输出格式”，不能给模型增加视觉能力。

因此正确的使用方式是：

> 给 Agent 一个**具体的图片本地路径或 URL**，让 Agent 调用本仓库里的脚本去读取并识别，而不是把图片二进制直接发给 DeepSeek。

```text
错误用法：直接粘贴图片，说“帮我识别这张图”
正确用法：发给 Agent 绝对路径，说“识别 D:\images\photo.jpg 里的文字/物体/内容”
```

## 目录结构

```text
vision-skill/
├── SKILL.md                 # Codex skill 的触发规则与工作流
├── README.md                # 本文档
├── .env.example             # 外部多模态 API 配置模板
├── .gitignore               # 忽略 .env、缓存、模型权重
├── agents/openai.yaml       # Codex UI 元数据
├── references/vision_models.md
└── scripts/
    ├── vision_api.js        # 外部多模态 API 识图/视觉问答
    ├── ocr.py               # 本地 OCR 文字识别
    ├── detect.py            # 本地目标检测（可选）
    ├── frames.py            # 视频抽帧
    └── requirements.txt     # Python 依赖
```

## 两种识别方式

### 1. 本地识别（不依赖外部 API）

适合 OCR、目标检测、视频抽帧，图片不离开本机。

```powershell
# 本地 OCR：识别图片文字
pip install -r scripts\requirements.txt
python scripts\ocr.py "D:\images\paper.png"

# 本地目标检测（需额外安装 ultralytics）
pip install ultralytics
python scripts\detect.py "D:\images\street.jpg" --conf 0.4

# 视频抽帧，之后再对帧图片做 OCR/检测
python scripts\frames.py "D:\videos\lesson.mp4" --interval 1 --out assets\frames
```

### 2. 外部多模态 API（需要视觉理解时）

适合“描述图片内容、视觉问答”，底层使用 `qwen-vl-max` 等真正的多模态模型。

```powershell
# 复制 .env.example 为 .env，填写 VISION_API_KEY
node --env-file=.env scripts\vision_api.js "D:\images\photo.jpg" "用中文描述这张图"
node --env-file=.env scripts\vision_api.js --url "https://example.com/a.jpg" "图中有哪些物体"
```

## 如何作为 Codex Skill 使用

1. 把 `vision-skill` 整个目录复制到 Codex 技能目录，例如：

   ```text
   C:\Users\<用户名>\.codex\skills\vision
   ```

2. 在 `.env` 中填写你的多模态 API 配置。
3. 重新打开 Codex 会话，让它能发现该 skill。
4. 使用绝对路径，而不是直接粘贴图片：

   ```text
   请识别 C:\Users\zero\Documents\homework\chart.png 里的文字，并解释图表的含义。
   ```

## 应用场景

- **课程资料数字化**：识别教材、PPT、板书截图中的文字，转成可编辑文本。
- **试卷/作业 OCR**：把扫描版题目转成文字，交给 Agent 解释答案。
- **图片内容理解**：分析 UI 截图、实验图、论文插图，生成中文描述。
- **目标检测作业**：用 YOLOv8 检测常见物体，输出类别、置信度、边界框。
- **视频笔记**：从课程视频或监控录像中按秒抽帧，再对关键帧做 OCR/识别。
- **隐私敏感场景**：优先使用本地 OCR/检测脚本，图片不上传外部服务。
- **Agent 自动分流**：Agent 收到“识别文字”时走本地 OCR，收到“描述图片”时走外部多模态 API。

## 技术栈与学习点

- Node.js 内置 `http/https/fs/path`，零 npm 依赖；用 `--env-file` 加载 `.env`。
- OpenAI 兼容 `POST /chat/completions` 协议，图片使用 `data:image/<mime>;base64,...` 或 URL。
- Python `RapidOCR` 完成“文字区域检测 + 文字识别”两步 OCR。
- OpenCV 读取视频、计算 FPS、按间隔抽帧。
- Ultralytics YOLOv8 输出类别、置信度和边界框。
- Windows 下 Python 控制台默认 GBK，脚本统一 `sys.stdout.reconfigure(encoding="utf-8")` 防中文乱码。
- `.env` 保存密钥，`.gitignore` 防止把真实 API Key 提交到仓库。

## 常见问题

- **报“该模型不可视觉识别：vision”**：当前模型不支持图像输入。不要发图，改成发图片路径，让 Agent 调脚本。
- **`vision_api.js` 提示填写 Key**：检查 `.env` 是否存在，且 `VISION_API_KEY` 不是示例占位符。
- **OCR 中文乱码**：脚本已强制 UTF-8；旧终端可执行 `chcp 65001` 或设置 `PYTHONUTF8=1`。
- **目标检测导入失败**：需要额外安装 `pip install ultralytics`，体积较大。
- **大图请求失败**：脚本限制本地图片 10MB；大图建议先压缩，或改用 `--url`。