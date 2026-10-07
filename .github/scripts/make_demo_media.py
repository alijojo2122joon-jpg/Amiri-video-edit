#!/usr/bin/env python3
"""Generates demo media for the UI screenshot harness (scenic test videos, photos, a sticker, music).

Usage: make_demo_media.py OUT_DIR
Everything is synthetic (drawn with numpy/PIL), so no personal media ends up in the repo.
"""
import math
import os
import shutil
import subprocess
import sys
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import imageio_ffmpeg

OUT = sys.argv[1] if len(sys.argv) > 1 else "demo-media"
GAL = os.path.join(OUT, "gallery")
os.makedirs(GAL, exist_ok=True)
FF = imageio_ffmpeg.get_ffmpeg_exe()
rng = np.random.default_rng(3)


def grad(h, w, top, bottom):
    t = np.linspace(0, 1, h)[:, None, None]
    return (np.array(top)[None, None, :] * (1 - t) + np.array(bottom)[None, None, :] * t) * np.ones((1, w, 1))


def hills(img, base, amp, freq, phase, color):
    h, w, _ = img.shape
    xs = np.arange(w)
    ys = base + amp * np.sin(xs / w * freq * 2 * math.pi + phase) + amp * 0.4 * np.sin(xs / w * freq * 5.1 + phase * 1.7)
    yy = np.arange(h)[:, None]
    mask = yy >= ys[None, :]
    img[mask] = np.array(color)
    return img


def scene(w, h, t, palette):
    sky_top, sky_bot, sun, h1, h2, h3 = palette
    img = grad(h, w, sky_top, sky_bot)
    # sun
    yy, xx = np.mgrid[0:h, 0:w]
    sx, sy = w * (0.62 + 0.04 * math.sin(t * 0.4)), h * (0.36 + 0.02 * t)
    r = min(w, h) * 0.12
    d = np.sqrt((xx - sx) ** 2 + (yy - sy) ** 2)
    glow = np.clip(1 - d / (r * 3.2), 0, 1) ** 2
    img = img * (1 - glow[..., None] * 0.6) + np.array(sun) * glow[..., None] * 0.6
    img[d < r] = np.array([255, 244, 214])
    img = hills(img, h * 0.58, h * 0.04, 1.3, t * 0.25, h1)
    img = hills(img, h * 0.68, h * 0.05, 2.0, 1.3 + t * 0.5, h2)
    img = hills(img, h * 0.80, h * 0.05, 2.7, 2.1 + t * 0.9, h3)
    return np.clip(img, 0, 255).astype(np.uint8)


def ocean(w, h, t):
    img = grad(h, w, [18, 70, 140], [120, 190, 230])
    yy, xx = np.mgrid[0:h, 0:w]
    horizon = int(h * 0.5)
    sea = grad(h - horizon, w, [10, 90, 150], [4, 40, 80])
    img[horizon:] = sea
    for k in range(9):
        y = horizon + (k + 1) ** 1.6 * h * 0.012
        wave_y = y + np.sin(xx[0] / w * (6 + k) * math.pi + t * (1.2 + k * 0.3)) * (2 + k)
        for dy in range(2):
            ys = np.clip((wave_y + dy).astype(int), 0, h - 1)
            img[ys, np.arange(w)] = [200, 230, 250]
    # boat
    bx = int(w * (0.2 + 0.03 * t)); by = int(h * 0.52)
    img[by - 6:by, bx - 30:bx + 30] = [250, 250, 250]
    img[by - 40:by - 6, bx:bx + 3] = [60, 40, 30]
    tri = (xx - bx) ** 2 / 400 + (yy - (by - 24)) ** 2 / 300
    img[(tri < 1) & (xx > bx)] = [255, 200, 120]
    return np.clip(img, 0, 255).astype(np.uint8)


def encode(path, w, h, fps, frames_fn, n, audio=None):
    cmd = [FF, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(fps), "-i", "-"]
    if audio:
        cmd += ["-i", audio, "-shortest", "-c:a", "aac", "-b:a", "128k"]
    cmd += ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p", "-movflags", "+faststart", path]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for i in range(n):
        p.stdin.write(frames_fn(i / fps).tobytes())
    p.stdin.close()
    p.wait()


def music(path, seconds=20.0, sr=44100):
    t = np.arange(int(seconds * sr)) / sr
    out = np.zeros_like(t)
    chords = [[220.0, 277.18, 329.63], [174.61, 220.0, 261.63], [261.63, 329.63, 392.0], [196.0, 246.94, 293.66]]
    beat = 0.5
    for i, start in enumerate(np.arange(0, seconds, 2.0)):
        seg = (t >= start) & (t < start + 2.0)
        for f in chords[i % 4]:
            out[seg] += 0.12 * np.sin(2 * math.pi * f * t[seg]) * np.exp(-((t[seg] - start) % beat) * 2.5)
    for b in np.arange(0, seconds, beat):
        seg = (t >= b) & (t < b + 0.25)
        tt = t[seg] - b
        out[seg] += 0.5 * np.sin(2 * math.pi * (50 + 100 * np.exp(-tt * 30)) * tt) * np.exp(-tt * 12)
    out = np.tanh(out * 1.4) * 0.8
    data = (out * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(data.tobytes())


PAL_SUNSET = ([44, 54, 108], [246, 152, 98], [255, 214, 150], [176, 92, 96], [120, 60, 82], [62, 34, 60])
PAL_MORNING = ([90, 160, 230], [220, 238, 250], [255, 250, 220], [110, 170, 110], [70, 130, 80], [40, 90, 60])
PAL_DUSK = ([20, 24, 60], [120, 80, 150], [255, 190, 210], [70, 50, 110], [45, 30, 80], [24, 16, 46])

music(os.path.join(OUT, "demo_music.wav"))
encode(os.path.join(OUT, "demo1.mp4"), 720, 1280, 24, lambda t: scene(720, 1280, t, PAL_SUNSET), 24 * 7, os.path.join(OUT, "demo_music.wav"))
encode(os.path.join(OUT, "demo2.mp4"), 1280, 720, 24, lambda t: ocean(1280, 720, t), 24 * 6)
encode(os.path.join(GAL, "VID_morning.mp4"), 720, 1280, 24, lambda t: scene(720, 1280, t + 3, PAL_MORNING), 24 * 4)
encode(os.path.join(GAL, "VID_dusk.mp4"), 1280, 720, 24, lambda t: scene(1280, 720, t + 5, PAL_DUSK), 24 * 9)
shutil.copy(os.path.join(OUT, "demo1.mp4"), os.path.join(GAL, "VID_sunset.mp4"))
shutil.copy(os.path.join(OUT, "demo2.mp4"), os.path.join(GAL, "VID_ocean.mp4"))

# photos
for i, pal in enumerate([PAL_SUNSET, PAL_MORNING, PAL_DUSK, PAL_MORNING, PAL_SUNSET, PAL_DUSK, PAL_MORNING, PAL_SUNSET]):
    w, h = (900, 1200) if i % 3 else (1200, 900)
    Image.fromarray(scene(w, h, i * 2.3, pal)).save(os.path.join(GAL, f"IMG_{i:02d}.jpg"), quality=88)

# the cat photo from the app's preview asset (already in the repo)
cat = "app/src/main/assets/preview/cat.jpg"
if os.path.exists(cat):
    im = Image.open(cat).convert("RGB")
    im = im.resize((im.width * 4, im.height * 4), Image.LANCZOS).filter(ImageFilter.SMOOTH)
    im.save(os.path.join(OUT, "demo_cat.jpg"), quality=90)
    im.save(os.path.join(GAL, "IMG_cat.jpg"), quality=90)
else:
    Image.fromarray(scene(900, 1200, 9, PAL_MORNING)).save(os.path.join(OUT, "demo_cat.jpg"))

# a round sticker with transparency
st = Image.new("RGBA", (420, 420), (0, 0, 0, 0))
d = ImageDraw.Draw(st)
d.ellipse((10, 10, 410, 410), fill=(244, 162, 97, 255), outline=(255, 255, 255, 255), width=10)
for cx, cy, r in [(150, 150, 34), (210, 120, 36), (270, 150, 34), (300, 215, 30), (110, 215, 30)]:
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(60, 30, 20, 255))
d.ellipse((140, 210, 280, 320), fill=(60, 30, 20, 255))
st.save(os.path.join(OUT, "demo_overlay.png"))
print("demo media in", OUT, sorted(os.listdir(OUT)), sorted(os.listdir(GAL)))
