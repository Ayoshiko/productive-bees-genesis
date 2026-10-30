"""Generate the editor's own scalable coverage masks; no reference UI assets are copied."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter
from PIL import ImageChops


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/productivebeesgenesis/textures/gui/bee_designer/materials"
DENSITY = 4
SAMPLES = 4


def rounded(name: str, radius: int) -> None:
    size = (radius * 2 + 8) * DENSITY
    mask = Image.new("L", (size * SAMPLES, size * SAMPLES))
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, mask.width - 1, mask.height - 1), radius * DENSITY * SAMPLES, fill=255
    )
    mask = mask.resize((size, size), Image.Resampling.LANCZOS)
    image = Image.new("RGBA", mask.size, (255, 255, 255, 0))
    image.putalpha(mask)
    image.save(OUTPUT / f"{name}.png")
    inner = Image.new("L", (size * SAMPLES, size * SAMPLES))
    inset = DENSITY * SAMPLES
    ImageDraw.Draw(inner).rounded_rectangle(
        (inset, inset, inner.width - inset - 1, inner.height - inset - 1),
        max(0, radius - 1) * DENSITY * SAMPLES,
        fill=255,
    )
    inner = inner.resize((size, size), Image.Resampling.LANCZOS)
    image.putalpha(ImageChops.subtract(mask, inner))
    image.save(OUTPUT / f"{name}_border.png")


def shadow() -> None:
    # The 6-GUI-pixel transparent margin belongs to rendering, never to hit testing.
    size = 48 * DENSITY
    mask = Image.new("L", (size, size))
    margin = 6 * DENSITY
    ImageDraw.Draw(mask).rounded_rectangle(
        (margin, margin, size - margin - 1, size - margin - 1), 10 * DENSITY, fill=255
    )
    mask = mask.filter(ImageFilter.GaussianBlur(2 * DENSITY))
    image = Image.new("RGBA", mask.size, (255, 255, 255, 0))
    image.putalpha(mask)
    image.save(OUTPUT / "shadow.png")


if __name__ == "__main__":
    OUTPUT.mkdir(parents=True, exist_ok=True)
    rounded("control", 5)
    rounded("card", 8)
    rounded("window", 10)
    shadow()
