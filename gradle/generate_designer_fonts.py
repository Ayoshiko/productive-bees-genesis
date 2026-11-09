"""Generate small, licensed editor font subsets from SHA-256-pinned official originals.

Install fonttools==4.68.0 into a build-local directory and pass that directory via
--fonttools-dir. --source-dir contains the three unmodified files named in SOURCES.
The checked-in subsets are sufficient for normal Gradle builds; this generator is
only needed when changing the coverage or updating translations.
"""

import argparse
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import sys
import zlib


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "src/main/resources/assets/productivebeesgenesis"
FONTTOOLS_VERSION = "4.68.0"
# The editor is horizontal: keep shaping/mark positioning, omit vertical and decorative alternates.
HORIZONTAL_FEATURES = ["ccmp", "locl", "kern", "mark", "mkmk", "rlig", "liga", "clig", "calt", "curs"]
SOURCES = (
    {
        "file": "notosanssc_regular.otf",
        "sha256": "faa6c9df652116dde789d351359f3d7e5d2285a2b2a1f04a2d7244df706d5ea9",
        "url": "https://github.com/notofonts/noto-cjk/blob/main/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf",
        "output": "designer_cjk.otf",
        "family": "Bee Designer CJK",
        "postscript": "BeeDesignerCJK-Regular",
        "license": "notosanssc_ofl.txt",
    },
    {
        "file": "notosans_regular.ttf",
        "sha256": "b85c38ecea8a7cfb39c24e395a4007474fa5a4fc864f6ee33309eb4948d232d5",
        "url": "https://github.com/notofonts/noto-fonts/blob/main/hinted/ttf/NotoSans/NotoSans-Regular.ttf",
        "output": "designer_sans.ttf",
        "family": "Bee Designer Sans",
        "postscript": "BeeDesignerSans-Regular",
        "license": "notosans_ofl.txt",
    },
    {
        "file": "jetbrainsmono_regular.ttf",
        "sha256": "e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1",
        "url": "https://github.com/JetBrains/JetBrainsMono/blob/master/fonts/ttf/JetBrainsMono-Regular.ttf",
        "output": "designer_mono.ttf",
        "family": "Bee Designer Mono",
        "postscript": "BeeDesignerMono-Regular",
        "license": "jetbrainsmono_ofl.txt",
    },
)


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for entry in value.values():
            yield from strings(entry)
    elif isinstance(value, list):
        for entry in value:
            yield from strings(entry)


def first_level_han():
    points = set()
    for lead in range(0xB0, 0xD8):
        for trail in range(0xA1, 0xFF):
            try:
                points.add(ord(bytes((lead, trail)).decode("gb2312")))
            except UnicodeDecodeError:
                # Five unassigned positions in the fixed first-level encoding block.
                pass
    if len(points) != 3755:
        raise ValueError("Unexpected GB2312 first-level character mapping")
    return points


def rename(font, family, postscript):
    values = {
        1: family,
        2: "Regular",
        3: postscript + ";EditorSubset-1",
        4: family + " Regular",
        6: postscript,
        16: family,
        17: "Regular",
        18: family + " Regular",
        21: family,
        22: "Regular",
        25: postscript,
    }
    for record in font["name"].names:
        if record.nameID in values:
            record.string = values[record.nameID].encode(record.getEncoding())
    if "CFF " in font:
        font["CFF "].cff.fontNames = [postscript]
        for top in font["CFF "].cff.topDictIndex:
            top.FamilyName = family
            top.FullName = family + " Regular"


def deflate_size(data):
    compressor = zlib.compressobj(6, zlib.DEFLATED, -15)
    return len(compressor.compress(data) + compressor.flush())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", required=True, type=Path)
    parser.add_argument("--fonttools-dir", type=Path)
    parser.add_argument("--output-dir", type=Path, default=RESOURCES / "font")
    parser.add_argument("--report", type=Path, default=ROOT / "build/designer-fonts/subsets.json")
    args = parser.parse_args()
    if args.fonttools_dir:
        sys.path.insert(0, str(args.fonttools_dir.resolve()))
    import fontTools
    from fontTools import subset
    from fontTools.ttLib import TTFont

    if fontTools.__version__ != FONTTOOLS_VERSION:
        raise ValueError("Use the pinned FontTools version " + FONTTOOLS_VERSION)
    translations = sorted((RESOURCES / "lang").glob("*.json"))
    ui_points = {
        ord(character)
        for path in translations
        for text in strings(json.loads(path.read_text(encoding="utf-8")))
        for character in text
    }
    cjk = {point for point in ui_points if point >= 0x2E80}
    cjk |= first_level_han() | set(range(0x3000, 0x3040)) | set(range(0xFF00, 0xFFF0))
    latin = set(range(0x20, 0x0250)) | set(range(0x0300, 0x0530))
    latin |= set(range(0x1E00, 0x2070)) | set(range(0x20A0, 0x20D0))
    latin |= set(range(0x2100, 0x2150)) | set(range(0x2190, 0x2300))
    latin |= {point for point in ui_points if point < 0x2E80}
    results = []
    outputs = {}
    for spec in SOURCES:
        source = args.source_dir / spec["file"]
        if source.is_symlink():
            raise ValueError("Font originals must be regular files")
        data = source.read_bytes()
        if sha256(data).hexdigest() != spec["sha256"]:
            raise ValueError("Original font SHA-256 mismatch: " + spec["file"])
        font = TTFont(BytesIO(data), recalcTimestamp=False)
        requested = cjk if spec["output"] == "designer_cjk.otf" else latin
        options = subset.Options()
        options.layout_features = HORIZONTAL_FEATURES
        options.name_IDs = ["*"]
        options.name_languages = ["*"]
        options.name_legacy = True
        options.recalc_timestamp = False
        selection = subset.Subsetter(options=options)
        selection.populate(unicodes=requested & set(font.getBestCmap()))
        selection.subset(font)
        rename(font, spec["family"], spec["postscript"])
        out = BytesIO()
        font.save(out, reorderTables=True)
        output = out.getvalue()
        outputs[spec["output"]] = output
        results.append({
            **spec,
            "selected_codepoints": len(font.getBestCmap()),
            "glyphs": len(font.getGlyphOrder()),
            "raw_bytes": len(output),
            "jar_deflate_bytes": deflate_size(output),
            "subset_sha256": sha256(output).hexdigest(),
        })
    coverage = set()
    for data in outputs.values():
        coverage |= set(TTFont(BytesIO(data)).getBestCmap())
    missing = sorted(ui_points - coverage - {0x0A, 0x0D, 0x09})
    if missing:
        raise ValueError("UI coverage missing: " + ", ".join("U+%04X" % point for point in missing))
    if not first_level_han() <= coverage:
        raise ValueError("GB2312 first-level coverage was lost")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, data in outputs.items():
        destination = args.output_dir / name
        if destination.is_symlink():
            raise ValueError("Refusing linked font output")
        destination.write_bytes(data)
    report = {
        "fonttools": FONTTOOLS_VERSION,
        "layout_features": HORIZONTAL_FEATURES,
        "coverage": "all current translation text + 3755 GB2312 first-level Han; common Western alphabets and symbols",
        "ui_codepoints": len(ui_points),
        "translations": {
            path.name: sha256(path.read_bytes()).hexdigest() for path in translations
        },
        "fonts": results,
        "total_raw_bytes": sum(item["raw_bytes"] for item in results),
        "total_jar_deflate_bytes": sum(item["jar_deflate_bytes"] for item in results),
        "runtime_fallback": "system SansSerif/Monospaced outlines, then Minecraft native glyphs",
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
