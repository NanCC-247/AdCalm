"""Package approved RGBA artwork; trim only the exterior canvas for launcher sizing."""
from pathlib import Path
import hashlib
import json
from statistics import median

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ARTWORK = ROOT / "docs" / "artwork"
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def launcher_content(master):
    # Ignore near-transparent exterior pixels when measuring the actual icon tile.
    # Keep the supplied source and master files untouched, including their alpha.
    bounds = master.getchannel("A").point(lambda alpha: 255 if alpha >= 128 else 0).getbbox()
    if bounds is None:
        raise ValueError("The approved icon has no visible content")
    left, top, right, bottom = bounds
    side = max(right - left, bottom - top) + 4
    center_x, center_y = (left + right) / 2, (top + bottom) / 2
    crop = (round(center_x - side / 2), round(center_y - side / 2),
            round(center_x - side / 2) + side, round(center_y - side / 2) + side)
    return master.crop(crop), crop


def background_color(content):
    # Sample the opaque outer rim, away from the shield and orbital band.
    alpha_bounds = content.getchannel("A").point(lambda alpha: 255 if alpha >= 250 else 0).getbbox()
    left, top, right, bottom = alpha_bounds
    samples = []
    for fraction in (.35, .45, .55, .65):
        x = round(left + (right - left - 1) * fraction)
        y = round(top + (bottom - top - 1) * fraction)
        for point in ((x, top + 5), (x, bottom - 6), (left + 5, y), (right - 6, y)):
            pixel = content.getpixel(point)
            if pixel[3] >= 250:
                samples.append(pixel[:3])
    if not samples:
        raise ValueError("The icon has no opaque rim for a matching background")
    return "#" + "".join(f"{round(median(pixel[channel] for pixel in samples)):02X}"
                         for channel in range(3))


def package():
    master = Image.open(ARTWORK / "icon-master.png").convert("RGBA")
    content_master, crop = launcher_content(master)
    background = background_color(content_master)
    for density, scale in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        size = round(48 * scale)
        legacy = content_master.resize((size, size), Image.Resampling.LANCZOS)
        legacy.save(folder / "ic_launcher.png")
        # Legacy launchers choose their own masks; retain the supplied composition.
        legacy.save(folder / "ic_launcher_round.png")

        canvas_size = round(108 * scale)
        content_size = round(72 * scale)
        foreground = Image.new("RGBA", (canvas_size, canvas_size))
        content = content_master.resize((content_size, content_size), Image.Resampling.LANCZOS)
        offset = (canvas_size - content_size) // 2
        foreground.alpha_composite(content, (offset, offset))
        foreground.save(folder / "ic_launcher_foreground.png")

    content_master.resize((192, 192), Image.Resampling.LANCZOS).save(RES / "drawable" / "adcalm_brand.png")
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <!-- Median RGB sampled from the approved icon’s opaque outer rim. -->\n'
        f'    <color name="ic_launcher_background">{background}</color>\n'
        '</resources>\n', encoding="utf-8"
    )
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <!-- Exterior canvas trimmed; artwork fits a centered 72 dp box on a 108 dp canvas. -->\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    for name in ["ic_launcher.xml", "ic_launcher_round.xml"]:
        (RES / "mipmap-anydpi-v26" / name).write_text(adaptive, encoding="utf-8")

    preview_dir = ARTWORK / "launcher-previews"
    preview_dir.mkdir(exist_ok=True)
    foreground = Image.open(RES / "mipmap-xxxhdpi" / "ic_launcher_foreground.png").convert("RGBA")
    layered = Image.new("RGBA", foreground.size, background)
    layered.alpha_composite(foreground)
    # Masks are preview-only. Android applies the device's own launcher mask.
    safe_box = (72, 72, 360, 360)
    sheet = Image.new("RGB", (1000, 580), "#F7FBFA")
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype(r"C:\Windows\Fonts\segoeui.ttf", 20)
    except OSError:
        font = ImageFont.load_default()
    for index, label in enumerate(["Approved artwork", "Adaptive / circle", "Adaptive / rounded"]):
        mask = Image.new("L", foreground.size)
        md = ImageDraw.Draw(mask)
        if index == 0:
            sample = content_master.resize((288, 288), Image.Resampling.LANCZOS)
        else:
            if index == 1:
                md.ellipse(safe_box, fill=255)
            else:
                md.rounded_rectangle(safe_box, radius=68, fill=255)
            masked = layered.copy()
            masked.putalpha(mask)
            sample = masked.crop(safe_box)
        x = 28 + index * 330
        sheet.paste(sample, (x, 120), sample)
        draw.text((x, 70), label, fill="#1E4448", font=font)
    draw.text((28, 460), "Transparent exterior trimmed. Artwork retained; no black background.",
              fill="#60777F", font=font)
    sheet.save(preview_dir / "launcher-masks.png")
    audit = {
        "source_sha256": hashlib.sha256((ARTWORK / "icon-source.png").read_bytes()).hexdigest(),
        "master_sha256": hashlib.sha256((ARTWORK / "icon-master.png").read_bytes()).hexdigest(),
        "source_dimensions": list(master.size),
        "artwork_edit": "none; transparent exterior canvas trimmed during resource packaging",
        "resource_crop": list(crop),
        "resource_content_dimensions": list(content_master.size),
        "source_and_master_unchanged": True,
        "adaptive_canvas_dp": 108,
        "adaptive_content_dp": 72,
        "background": background,
        "densities": DENSITIES,
    }
    (preview_dir / "packaging-audit.json").write_text(json.dumps(audit, indent=2), encoding="utf-8")
    print(json.dumps(audit, indent=2))


if __name__ == "__main__":
    package()
