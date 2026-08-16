#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const http = require("http");
const https = require("https");

const BASE_URL = (process.env.VISION_BASE_URL || "https://dashscope.aliyuncs.com/compatible-mode/v1").replace(/\/+$/, "");
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

const MIME = {
  jpg: "jpeg", jpeg: "jpeg", png: "png", gif: "gif", webp: "webp", bmp: "bmp"
};

function parseArgs(argv) {
  let imageSource = "";
  let prompt = "";
  let isUrl = false;
  let isDataFile = false;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--url" && argv[i + 1]) { isUrl = true; imageSource = argv[++i]; }
    else if (a === "--data-file" && argv[i + 1]) { isDataFile = true; imageSource = argv[++i]; }
    else if (a === "--model" && argv[i + 1]) { process.env.VISION_MODEL = argv[++i]; }
    else if (!imageSource && !a.startsWith("--")) imageSource = a;
    else if (imageSource && !a.startsWith("--")) prompt = prompt ? prompt + " " + a : a;
  }
  if (!prompt) prompt = "请详细描述这张图片的内容。";
  return { imageSource, prompt, isUrl, isDataFile };
}

function resolveImageUrl(source, isUrl) {
  if (isUrl || source.startsWith("data:")) return source;
  const resolved = path.resolve(source);
  if (!fs.existsSync(resolved)) throw new Error("文件不存在: " + resolved);
  const stat = fs.statSync(resolved);
  if (stat.size > MAX_IMAGE_BYTES) throw new Error("图片过大，请先压缩或改用 --url");
  const ext = path.extname(resolved).slice(1).toLowerCase();
  const mime = MIME[ext] || "jpeg";
  const data = fs.readFileSync(resolved);
  return "data:image/" + mime + ";base64," + data.toString("base64");
}

function resolveDataFile(filePath) {
  const resolved = path.resolve(filePath);
  if (!fs.existsSync(resolved)) throw new Error("数据文件不存在: " + resolved);
  const raw = fs.readFileSync(resolved, "utf8").trim();
  if (raw.startsWith("[")) {
    try {
      const items = JSON.parse(raw);
      for (const item of items) {
        const url = item && item.image_url;
        if (typeof url === "string" && url.startsWith("data:")) return url;
      }
    } catch {
      // 不是 JSON，按纯文本继续处理
    }
  }
  if (!raw.startsWith("data:")) throw new Error("数据文件内容不是 data:image URL");
  return raw;
}

function request(payload) {
  const url = new URL(BASE_URL + "/chat/completions");
  const body = JSON.stringify(payload);
  const transport = url.protocol === "https:" ? https : http;
  return new Promise((resolve, reject) => {
    const req = transport.request(url, {
      method: "POST",
      headers: {
        Authorization: "Bearer " + process.env.VISION_API_KEY,
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(body)
      }
    }, (res) => {
      let data = "";
      res.on("data", (c) => data += c);
      res.on("end", () => {
        if (res.statusCode >= 400) return reject(new Error("API " + res.statusCode + ": " + data.slice(0, 300)));
        try {
          const parsed = JSON.parse(data);
          resolve(parsed.choices?.[0]?.message?.content || data);
        } catch {
          resolve(data);
        }
      });
    });
    req.on("error", reject);
    req.write(body);
    req.end();
  });
}

async function main() {
  const apiKey = process.env.VISION_API_KEY || "";
  if (!apiKey || apiKey.startsWith("sk-xxx")) {
    console.error("请先复制 .env.example 为 .env，并填写 VISION_API_KEY。");
    process.exit(1);
  }
  const model = process.env.VISION_MODEL || "qwen-vl-max";
  const { imageSource, prompt, isUrl, isDataFile } = parseArgs(process.argv.slice(2));
  if (!imageSource) {
    console.error("用法: node --env-file=.env scripts/vision_api.js <图片路径|data:URL|--url URL|--data-file 文件> [问题]");
    process.exit(1);
  }
  try {
    const imageUrl = isDataFile ? resolveDataFile(imageSource) : resolveImageUrl(imageSource, isUrl);
    const content = [
      { type: "image_url", image_url: { url: imageUrl } },
      { type: "text", text: prompt }
    ];
    const answer = await request({ model, messages: [{ role: "user", content }], stream: false, max_tokens: 1024 });
    console.log(answer);
  } catch (err) {
    console.error("识图失败:", err.message);
    process.exit(1);
  }
}

main();