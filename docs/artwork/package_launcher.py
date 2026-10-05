"""Package the approved icon without repainting, cleanup, or cropping its artwork."""
from pathlib import Path
import hashlib
import json

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ARTWORK = ROOT / "docs" / "artwork"
RES = ROOT / "app" / "src" / "main" / "res"
BACKGROUND = "#27A4B7"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def package():
    master = Image.open(ARTWORK / "icon-master.png").convert("RGBA")
    assert master.size == (1254, 1254)
    for density, scale in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        size = round(48 * scale)
        legacy = master.resize((size, size), Image.Resampling.LANCZOS)
        legacy.save(folder / "ic_launcher.png")
        # Legacy launchers choose their own masks; retain the supplied composition.
        legacy.save(folder / "ic_launcher_round.png")

        canvas_size = round(108 * scale)
        content_size = round(72 * scale)
        foreground = Image.new("RGBA", (canvas_size, canvas_size))
        content = master.resize((content_size, content_size), Image.Resampling.LANCZOS)
        offset = (canvas_size - content_size) // 2
        foreground.alpha_composite(content, (offset, offset))
        foreground.save(folder / "ic_launcher_foreground.png")

    master.resize((192, 192), Image.Resampling.LANCZOS).save(RES / "drawable" / "adcalm_brand.png")
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <!-- Median RGB of the approved icon’s four opaque inner corners. -->\n'
        f'    <color name="ic_launcher_background">{BACKGROUND}</color>\n'
        '</resources>\n', encoding="utf-8"
    )
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <!-- Approved artwork fits a centered 72 dp content box on the 108 dp canvas. -->\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    for name in ["ic_launcher.xml", "ic_launcher_round.xml"]:
        (RES / "mipmap-anydpi-v26" / name).write_text(adaptive, encoding="utf-8")

    preview_dir = ARTWORK / "launcher-previews"
    preview_dir.mkdir(exist_ok=True)
    foreground = Image.open(RES / "mipmap-xxxhdpi" / "ic_launcher_foreground.png").convert("RGBA")
    layered = Image.new("RGBA", foreground.size, BACKGROUND)
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
            sample = master.resize((288, 288), Image.Resampling.LANCZOS)
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
    draw.text((28, 460), "Original composition retained. Launcher mask controls the outer shape.",
              fill="#60777F", font=font)
    sheet.save(preview_dir / "launcher-masks.png")
    audit = {
        "source_sha256": hashlib.sha256((ARTWORK / "icon-source.png").read_bytes()).hexdigest(),
        "master_sha256": hashlib.sha256((ARTWORK / "icon-master.png").read_bytes()).hexdigest(),
        "source_dimensions": list(master.size),
        "artwork_edit": "none",
        "adaptive_canvas_dp": 108,
        "adaptive_content_dp": 72,
        "background": BACKGROUND,
        "densities": DENSITIES,
    }
    (preview_dir / "packaging-audit.json").write_text(json.dumps(audit, indent=2), encoding="utf-8")
    print(json.dumps(audit, indent=2))


if __name__ == "__main__":
    package()
