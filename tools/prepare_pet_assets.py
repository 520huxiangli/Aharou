#!/usr/bin/env python3
"""把画好的桌宠素材整理成 App 直接可用的 PNG。

做四件事（都可按参数关掉）：

1. **抠白底**（`--key-white`）：从四边向内做洪水填充，只把「与画面外沿连通的白色」判为背景。
   水手帽、袜子这类被描边围住的白色不会被误伤。素材本身带透明通道时不做这一步。
2. **裁掉四周留白**：按 alpha 的实际范围裁紧，避免同一套素材因为画布留白不同而「大小不一」。
3. **等比缩到上限**（`--max-size`）：素材大多 1024 级别，桌宠最大也就显示到 500 多像素，
   按上限收一下能省不少包体积。
4. **生成预览**（`--preview`）：把处理结果拼一张洋红底的图，用来肉眼检查边缘有没有残白。

用法：

    # 单张：把 root/orca girl.png 处理成 assets/pet/orca/front.png
    python3 tools/prepare_pet_assets.py "orca girl.png" --out app/src/main/assets/pet/orca/front.png

    # 整个目录：目录里的图按文件名输出到指定目录
    python3 tools/prepare_pet_assets.py ./original --out app/src/main/assets/pet/orca --max-size 512

    # 附带预览拼图（写到 --preview 指定路径，默认 tools/preview-pet.png）
    python3 tools/prepare_pet_assets.py ./original --out app/src/main/assets/pet/orca --preview

依赖 Pillow + numpy（容器里都有）。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

WHITE_MIN = 240
DEFAULT_MAX_SIZE = 512
IMAGE_SUFFIXES = {".png", ".webp", ".jpg", ".jpeg", ".bmp"}


def has_real_alpha(img: Image.Image) -> bool:
    """判断这张图是不是已经有真正的透明背景（而不是全不透明的摆设 alpha）。"""
    if img.mode not in ("RGBA", "LA", "P"):
        return False
    alpha = np.asarray(img.convert("RGBA"))[..., 3]
    return bool((alpha == 0).mean() > 0.01)


def background_mask(rgb: np.ndarray) -> np.ndarray:
    """从四边向内洪水填充，返回 True 表示该像素是与外界连通的白色背景。"""
    white = (rgb >= WHITE_MIN).all(axis=2)
    reached = np.zeros_like(white)
    reached[0, :] = white[0, :]
    reached[-1, :] = white[-1, :]
    reached[:, 0] |= white[:, 0]
    reached[:, -1] |= white[:, -1]
    # 迭代膨胀：每轮把已有区域向四邻域扩一格，直到不再变化。
    # 比逐像素 BFS 慢一点，但实现短、够快（百万像素几十轮）。
    while True:
        grown = reached.copy()
        grown[1:, :] |= reached[:-1, :]
        grown[:-1, :] |= reached[1:, :]
        grown[:, 1:] |= reached[:, :-1]
        grown[:, :-1] |= reached[:, 1:]
        grown &= white
        if np.array_equal(grown, reached):
            return reached
        reached = grown


def key_out_white(img: Image.Image) -> Image.Image:
    img = img.convert("RGBA")
    arr = np.asarray(img).copy()
    mask = background_mask(arr[..., :3])
    arr[mask, 3] = 0
    return Image.fromarray(arr, "RGBA")


def trim(img: Image.Image) -> Image.Image:
    """按 alpha 的实际范围裁紧。"""
    alpha = np.asarray(img)[..., 3]
    ys, xs = np.nonzero(alpha)
    if len(xs) == 0:
        return img
    return img.crop((int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1))


def fit_max(img: Image.Image, max_size: int) -> Image.Image:
    longest = max(img.width, img.height)
    if longest <= max_size:
        return img
    scale = max_size / longest
    size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
    return img.resize(size, Image.LANCZOS)


def process(path: Path, args: argparse.Namespace) -> Image.Image | None:
    img = Image.open(path)
    if args.key_white and not has_real_alpha(img):
        img = key_out_white(img)
    else:
        img = img.convert("RGBA")
    img = trim(img)
    img = fit_max(img, args.max_size)
    return img


def main() -> int:
    ap = argparse.ArgumentParser(description="桌宠素材预处理：抠白底 / 裁留白 / 缩上限 / 出预览")
    ap.add_argument("source", help="单张图片或包含图片的目录")
    ap.add_argument("--out", required=True, help="输出文件（单张）或输出目录（多张）")
    ap.add_argument("--max-size", type=int, default=DEFAULT_MAX_SIZE, help="最长边上限，默认 512")
    ap.add_argument("--key-white", action="store_true", help="白底不透明时先抠白底")
    ap.add_argument("--preview", nargs="?", const="tools/preview-pet.png", default=None,
                    help="生成洋红底预览图（可给路径，默认 tools/preview-pet.png）")
    args = ap.parse_args()

    src = Path(args.source)
    if not src.exists():
        print(f"找不到输入：{src}", file=sys.stderr)
        return 1

    if src.is_dir():
        files = sorted(p for p in src.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)
        if not files:
            print(f"目录里没有图片：{src}", file=sys.stderr)
            return 1
        out_dir = Path(args.out)
        out_dir.mkdir(parents=True, exist_ok=True)
        targets = [(p, out_dir / f"{p.stem}.png") for p in files]
    else:
        targets = [(src, Path(args.out))]

    done: list[Image.Image] = []
    for source, target in targets:
        img = process(source, args)
        if img is None:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        img.save(target, "PNG", optimize=True)
        # front 是我们的基准素材：报告它裁完的尺寸，方便核对各角色是否一致
        print(f"{source.name} -> {target}  {img.width}x{img.height}  {target.stat().st_size // 1024}K")
        done.append(img)

    if args.preview and done:
        cell = 260
        cols = min(4, len(done))
        rows = (len(done) + cols - 1) // cols
        sheet = Image.new("RGBA", (cols * cell, rows * cell), (255, 0, 255, 255))
        for i, img in enumerate(done):
            thumb = img.copy()
            thumb.thumbnail((cell - 8, cell - 8), Image.LANCZOS)
            x = (i % cols) * cell + (cell - thumb.width) // 2
            y = (i // cols) * cell + (cell - thumb.height) // 2
            sheet.alpha_composite(thumb, (x, y))
        preview_path = Path(args.preview)
        preview_path.parent.mkdir(parents=True, exist_ok=True)
        sheet.convert("RGB").save(preview_path, "PNG")
        print(f"预览已写入 {preview_path}（洋红底：残留白边会一眼看出来）")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
