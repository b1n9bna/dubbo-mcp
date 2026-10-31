#!/usr/bin/env python3
"""Render the reviewed, redacted MCP session as a 15-second GIF/MP4 replay.

This creates an editorial visualization of recorded events, not a screenshot of
Codex or its CLI. The source record and every frame state say so explicitly.
Requires Pillow and ffmpeg (or imageio-ffmpeg). No network or RPC calls are made.
"""
import argparse
from functools import lru_cache
import json
import os
from pathlib import Path
import shutil
import subprocess

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT, FPS, SECONDS = 1200, 760, 12, 15
BG, PANEL, BORDER = "#0b1116", "#111b23", "#23343f"
WHITE, MUTED, GREEN, BLUE = "#eef5f5", "#92a8b6", "#70e3b1", "#86bcf5"
ROOT = Path(__file__).resolve().parents[2]


def first_font(paths):
    for path in paths:
        if path and Path(path).is_file():
            return path
    raise RuntimeError("Install a CJK font and set DEMO_CJK_FONT / DEMO_MONO_FONT")


CJK = first_font([os.getenv("DEMO_CJK_FONT"), "/System/Library/Fonts/PingFang.ttc",
                  "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"])
MONO = first_font([os.getenv("DEMO_MONO_FONT"), "/System/Library/Fonts/Menlo.ttc",
                   "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"])


@lru_cache(maxsize=24)
def font(size, mono=False):
    return ImageFont.truetype(MONO if mono else CJK, size)


def text(draw, xy, value, size=22, color=WHITE, mono=False):
    draw.text(xy, value, font=font(size, mono), fill=color)


def wrap(draw, value, width, size):
    lines, current = [], ""
    for char in value:
        if draw.textlength(current + char, font=font(size)) > width and current:
            lines.append(current)
            current = ""
        current += char
    if current:
        lines.append(current)
    return lines


def frame(session, at, language):
    en = language == "en"
    image = Image.new("RGB", (WIDTH, HEIGHT), BG)
    draw = ImageDraw.Draw(image)
    text(draw, (40, 22), "DUBBO-MCP", 17, GREEN, True)
    headline = "One question. Your Dubbo services." if en else "一句话，直达你的 Dubbo 服务。"
    text(draw, (40, 55), headline, 36)
    draw.rounded_rectangle((948, 50, 1160, 94), radius=22, fill="#172a26", outline="#285849")
    text(draw, (970, 59), "MCP CALL REPLAY", 17, GREEN, True)
    draw.rounded_rectangle((40, 132, 1160, 234), radius=15, fill=PANEL, outline=BORDER)
    text(draw, (60, 148), ">", 27, GREEN, True)
    prompt = ("Query loading types in test. List their codes and names, then count the types."
              if en else session["prompt"])
    visible = prompt[:int(len(prompt) * min(1, max(0, (at - .25) / 1.65)))]
    for index, line in enumerate(wrap(draw, visible, 1034, 25)):
        text(draw, (94, 149 + index * 34), line, 25)
    text(draw, (40, 244), "DISCOVER  /  INSPECT  /  INVOKE", 13, MUTED, True)

    names = [call["tool"] for call in session["calls"]]
    details = ["test  ·  …TransportRpcService", "getLoadingTypeDict()  ·  version 1.0.0", "test  ·  getLoadingTypeDict()"]
    starts, ends = [2, 4, 6], [3.25, 5.25, 7.5]
    for index, name in enumerate(names):
        y = 270 + index * 110
        active, done = at >= starts[index], at >= ends[index]
        outline = GREEN if active and not done else BORDER
        draw.rounded_rectangle((40, y, 590, y + 94), radius=12, fill=PANEL, outline=outline, width=2 if active and not done else 1)
        draw.ellipse((58, y + 19, 90, y + 51), fill="#214436" if done else "#24313a")
        text(draw, (67, y + 23), str(index + 1), 17, GREEN if done else MUTED, True)
        text(draw, (104, y + 14), name, 23, WHITE if active else MUTED, True)
        text(draw, (104, y + 53), details[index] if active else "…", 17, MUTED, True)
        if active:
            text(draw, (539, y + 19), "OK" if done else "..", 16, GREEN, True)

    draw.rounded_rectangle((614, 270, 1160, 584), radius=12, fill="#0e171f", outline=BORDER)
    label = "RPC RESPONSE  /  SELECTED FIELDS" if en else "RPC 响应 · 仅保留展示字段"
    text(draw, (638, 286), label, 16, MUTED, en)
    draw.line((638, 322, 1136, 322), fill=BORDER)
    lines = json.dumps(session["result"], ensure_ascii=False, indent=2).splitlines()
    # Keep the real result compact enough to read in GitHub's README column.
    lines = ['{', '  "success": true,', '  "data": ['] + [
        '    ' + json.dumps(item, ensure_ascii=False, separators=(", ", ": ")) + ("," if i < len(session["result"]["data"]) - 1 else "")
        for i, item in enumerate(session["result"]["data"])
    ] + ['  ]', '}']
    if at >= 7.8:
        count = min(len(lines), int((at - 7.8) / .30) + 1)
        for index, line in enumerate(lines[:count]):
            color = GREEN if '"desc"' in line else WHITE
            text(draw, (638, 338 + index * 28), line, 20, color)
    else:
        text(draw, (638, 350), "Awaiting actual tool result…" if en else "等待工具返回…", 22, MUTED)

    result_ready = at >= 11
    draw.rounded_rectangle((40, 614, 1160, 706), radius=14, fill="#132a23" if result_ready else PANEL,
                           outline="#376c56" if result_ready else BORDER)
    if result_ready:
        total = session["summary"]["count"]
        text(draw, (62, 631), f"{total} loading types" if en else f"共 {total} 种装柜类型", 34, GREEN)
        values = "   ·   ".join(str(item["code"]) + " " + item["desc"] for item in session["result"]["data"])
        text(draw, (618, 643), values, 25)
    else:
        text(draw, (62, 640), "Service → schema → real RPC → answer" if en else "服务发现 → 方法元数据 → 真实 RPC → 分析结果", 25, MUTED)
    note = "Real test RPC · Rendered replay, NOT CLI footage" if en else "真实测试 RPC · 调用回放（非 CLI 录屏）"
    text(draw, (40, 726), note, 17, MUTED)
    text(draw, (812, 727), "github.com/b1n9bna/dubbo-mcp", 16, MUTED, True)
    return image


def validate(session):
    assert session["env"] == "test"
    assert [call["tool"] for call in session["calls"]] == ["dubbo_list_services", "dubbo_get_service_detail", "dubbo_invoke"]
    assert all(call["status"] == "SUCCESS" for call in session["calls"])
    assert session["result"]["success"] is True
    assert session["summary"]["count"] == len(session["result"]["data"])
    assert all(set(item) == {"code", "desc"} for item in session["result"]["data"])


def render(session, output, language, ffmpeg):
    output.mkdir(parents=True, exist_ok=True)
    stem = "dubbo-mcp-replay-" + language
    poster = frame(session, 14, language)
    poster.save(output / (stem + ".png"))
    # Use one palette for the whole GIF to keep text steady and file sizes small.
    atlas = Image.new("RGB", (WIDTH, HEIGHT * 4))
    for i, second in enumerate([1, 4.5, 8.8, 14]):
        atlas.paste(frame(session, second, language), (0, i * HEIGHT))
    palette = atlas.quantize(colors=96)
    process = subprocess.Popen([ffmpeg, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                                "-s", f"{WIDTH}x{HEIGHT}", "-r", str(FPS), "-i", "-", "-an", "-c:v", "libx264",
                                "-crf", "20", "-preset", "fast", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                                str(output / (stem + ".mp4"))], stdin=subprocess.PIPE)
    frames = []
    try:
        for number in range(FPS * SECONDS):
            image = frame(session, number / FPS, language)
            process.stdin.write(image.tobytes())
            frames.append(image.quantize(palette=palette, dither=Image.Dither.NONE))
    finally:
        process.stdin.close()
    if process.wait() != 0:
        raise RuntimeError("ffmpeg encode failed")
    # GIF stores centiseconds; cycle 80/80/90 ms to produce exactly 15 seconds.
    durations = [80, 80, 90] * (FPS * SECONDS // 3)
    frames[0].save(output / (stem + ".gif"), save_all=True, append_images=frames[1:], duration=durations,
                   loop=0, optimize=True, disposal=1)
    sheet = Image.new("RGB", (WIDTH, HEIGHT * 3))
    for i, second in enumerate([1.9, 7, 14]):
        sheet.paste(frame(session, second, language), (0, i * HEIGHT))
    sheet.resize((600, 1140)).save(output / (stem + "-contact.png"))
    print(language + ": 15s GIF/MP4 + poster rendered")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", type=Path, default=ROOT / "docs/demo/session.json")
    parser.add_argument("--output", type=Path, default=ROOT / "docs/media")
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    options = parser.parse_args()
    if not options.ffmpeg:
        import imageio_ffmpeg
        options.ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    session = json.loads(options.session.read_text())
    validate(session)
    for language in ["zh", "en"]:
        render(session, options.output, language, options.ffmpeg)
