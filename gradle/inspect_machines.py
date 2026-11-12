#!/usr/bin/env python3
"""Read saved PBG machines: python gradle/inspect_machines.py SOURCE.
SOURCE accepts a Java world, a probe directory, or start/end-machines.nbt.
Use --latest for a directory of probe runs; --output exports JSON.
This tool never writes a world or reads a running server's unsaved memory.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import gzip
import io
import json
from pathlib import Path
import struct
import zlib

PREFIX = "productivebeesgenesis_"


class NbtReader:
    # Same standard-library decoder as the existing performance snapshot reader.
    def __init__(self, data):
        self.stream = io.BytesIO(data)

    def number(self, code):
        return struct.unpack(">" + code, self.stream.read(struct.calcsize(">" + code)))[0]

    def text(self):
        raw = self.stream.read(self.number("H")).replace(b"\xc0\x80", b"\x00")
        text = raw.decode("utf-8", errors="surrogatepass")
        return text.encode("utf-16", errors="surrogatepass").decode("utf-16")

    def payload(self, kind):
        if kind in (1, 2, 3, 4, 5, 6):
            return self.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 7:
            return self.stream.read(self.number("i"))
        if kind == 8:
            return self.text()
        if kind == 9:
            child, length = self.number("B"), self.number("i")
            return [self.payload(child) for _ in range(length)]
        if kind == 10:
            value = {}
            while (child := self.number("B")):
                name = self.text()
                value[name] = self.payload(child)
            return value
        if kind in (11, 12):
            return [self.number("i" if kind == 11 else "q") for _ in range(self.number("i"))]
        raise ValueError(f"Unsupported NBT tag {kind}")

    def read(self):
        kind = self.number("B")
        self.text()
        return self.payload(kind)


def read_nbt(path):
    data = path.read_bytes()
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return NbtReader(data).read()


def utc(timestamp):
    return datetime.fromtimestamp(timestamp, timezone.utc).isoformat(timespec="seconds")


def json_safe(value):
    if isinstance(value, bytes):
        return list(value)
    if isinstance(value, dict):
        return {key: json_safe(child) for key, child in value.items()}
    if isinstance(value, list):
        return [json_safe(child) for child in value]
    return value


def region_chunks(path):
    data = path.read_bytes()
    rx, rz = map(int, path.stem.split(".")[1:])
    for index in range(1024):
        location = int.from_bytes(data[index * 4:index * 4 + 4], "big")
        if not location:
            continue
        offset = (location >> 8) * 4096
        length = int.from_bytes(data[offset:offset + 4], "big")
        compression = data[offset + 4]
        cx, cz = rx * 32 + index % 32, rz * 32 + index // 32
        chunk_data = data[offset + 5:offset + 4 + length]
        if compression & 128:
            chunk_data = path.with_name(f"c.{cx}.{cz}.mcc").read_bytes()
        codec = compression & 127
        if codec == 1:
            chunk_data = gzip.decompress(chunk_data)
        elif codec == 2:
            chunk_data = zlib.decompress(chunk_data)
        elif codec != 3:
            raise ValueError(f"{path.name} chunk {cx},{cz}: unsupported compression {codec}")
        saved = int.from_bytes(data[4096 + index * 4:4100 + index * 4], "big")
        yield NbtReader(chunk_data).read(), utc(saved) if saved else None


def dimension_regions(world):
    for folder, dimension in (("region", "minecraft:overworld"),
                              ("DIM-1/region", "minecraft:the_nether"),
                              ("DIM1/region", "minecraft:the_end")):
        if (world / folder).is_dir():
            yield world / folder, dimension
    dimensions = world / "dimensions"
    if dimensions.is_dir():
        for folder in sorted(dimensions.rglob("region")):
            parts = folder.relative_to(dimensions).parts[:-1]
            yield folder, parts[0] + ":" + "/".join(parts[1:])


def select_source(source, latest):
    if latest:
        candidates = list(source.glob("*/end-machines.nbt")) + list(source.glob("*/start-machines.nbt"))
        return max(candidates, key=lambda path: path.stat().st_mtime)
    if source.is_dir() and not (source / "level.dat").is_file():
        snapshots = [source / name for name in ("end-machines.nbt", "start-machines.nbt")]
        return next(path for path in snapshots if path.is_file())
    return source


def position(tag):
    return tuple(tag[key] for key in ("x", "y", "z"))


def report_rows(report, name):
    rows = report.get(name, [])
    return {position(row): row for row in rows} if isinstance(rows, list) else {}


def storage_family(identifier):
    namespace, _, name = identifier.partition(":")
    if namespace == "ae2":
        return "AE2"
    if namespace in ("sophisticatedstorage", "sophisticatedbackpacks"):
        return "Sophisticated"
    if namespace in ("functionalstorage", "storagedrawers"):
        return "抽屉存储"
    if namespace in ("extendedae", "advanced_ae", "ae2lt", "ae2ext", "appmek", "appflux"):
        return namespace
    if namespace == "minecraft" and name in ("chest", "trapped_chest", "barrel", "shulker_box", "hopper"):
        return "原版容器"
    if any(word in name for word in ("storage", "chest", "barrel", "drawer", "tank", "bin")):
        return namespace + "（按方块实体 ID）"
    return None


def machine_row(tag, dimension, saved, report, energy, inputs, neighbors):
    paid = tag.get(PREFIX + "pb_paid_work", {})
    pending = tag.get(PREFIX + "pb_committed_pending", [])
    output_buffer = tag.get(PREFIX + "output_buffer", {})
    production = output_buffer.get("production", {})
    coord = position(tag)
    accelerated = {position(row) for row in report.get("accelerated_targets", [])}
    acceleration = "未知（保存状态不含运行时倍率）"
    if coord in accelerated:
        acceleration = f"探针统一加速 ×{report.get('acceleration_multiplier', '?')}"
    elif report:
        acceleration = "未列入探针统一加速（原有加速未知）"
    items = tag.get("items", [])
    inventory_count = sum(row.get("item", {}).get("count", 0) for row in items)
    ae = {key.removeprefix(PREFIX): value for key, value in tag.items()
          if key.startswith(PREFIX) and ("ae" in key or "filter" in key)}
    upgrades = {key.removeprefix(PREFIX): value for key, value in tag.items() if "upgrade" in key}
    pending_items = sum(sum(item.get(PREFIX + "count", 0) for item in entry.get("items", []))
                        for entry in pending)
    pending_fluid = sum(entry.get("fluid_amount", 0) for entry in pending)
    custody = tag.get("component_ejector", {}).get("output_custody", {})
    if custody or any(value and (key.startswith("invalid") or "unknown" in key)
                      for key, value in production.items()):
        state = "有待核对状态"
    elif pending or production.get("items") or production.get("fluid_amount"):
        state = "有待排产物"
    elif tag.get("active_state"):
        state = "保存时活跃"
    else:
        state = "保存时未活跃"
    return {
        "dimension": dimension, "position": list(coord), "type": tag["id"],
        "kind": "蜂箱" if "apiary" in tag["id"] else "离心机" if "centrifuge" in tag["id"] else "其它",
        "state": state, "saved_utc": saved, "acceleration": acceleration,
        "energy": sum(entry.get("stored", 0) for entry in tag.get("energy_containers", [])),
        "inventory_count": inventory_count, "occupied_slots": len(items),
        "slots": items, "bee_slots": tag.get(PREFIX + "apiary_bee_slots", []),
        "feeder_slots": tag.get(PREFIX + "feeder_slots", []),
        "progress": tag.get(PREFIX + "pb_progress", tag.get("progress")),
        "processing_time": tag.get(PREFIX + "pb_processing_time"),
        "paid_work": paid, "active_paid_lanes": len(paid.get("entries", [])),
        "paused_work_count": len(paid.get("paused", [])), "pending": pending,
        "pending_items": pending_items, "pending_fluid": pending_fluid,
        "output_buffer": output_buffer, "custody": custody, "ae": ae, "upgrades": upgrades,
        "fluids": {key: value for key, value in tag.items() if "tank" in key or "fluid" in key},
        "resources": tag.get("neoforge:attachments", {}),
        "adjacent_block_entities": neighbors, "probe_energy": energy.get(coord),
        "probe_input": inputs.get(coord), "raw": tag
    }


def inspect(source, latest=False):
    selected = select_source(source, latest)
    report = {}
    errors = []
    entities = {}
    machines = []
    input_files = []
    external_types = Counter()
    storage = Counter()
    if selected.is_dir():
        mode = "world"
        metadata = read_nbt(selected / "level.dat").get("Data", {})
        for region_dir, dimension in dimension_regions(selected):
            for path in sorted(region_dir.glob("r.*.*.mca")):
                input_files.append(path)
                try:
                    for chunk, saved in region_chunks(path):
                        for tag in chunk.get("block_entities", []):
                            identifier = tag.get("id", "")
                            entities[(dimension, *position(tag))] = identifier
                            if identifier.startswith("productivebeesgenesis:"):
                                machines.append((tag, dimension, saved))
                            else:
                                external_types[identifier] += 1
                                family = storage_family(identifier)
                                if family:
                                    storage[family] += 1
                except (OSError, ValueError, EOFError, struct.error, zlib.error, IndexError) as error:
                    errors.append(f"{path}: {error}")
        source_info = {"kind": mode, "path": str(selected), "saved_game_time": metadata.get("Time"),
                       "world_name": metadata.get("LevelName"), "data_version": metadata.get("DataVersion")}
    else:
        mode = "snapshot"
        root = read_nbt(selected)
        input_files.append(selected)
        report_path = selected.parent / "result.json"
        if report_path.is_file():
            report = json.loads(report_path.read_text(encoding="utf-8"))
        for tag in root["machines"]:
            machines.append((tag, "minecraft:overworld", utc(selected.stat().st_mtime)))
        source_info = {"kind": mode, "path": str(selected), "probe_report": str(report_path) if report else None,
                       "probe_started_utc": report.get("started_utc"), "sampling_ticks": report.get("sampling_ticks"),
                       "probe_passed": report.get("passed"),
                       "snapshot_phase": selected.stem.removesuffix("-machines")}
    energy, inputs = report_rows(report, "machine_energy"), report_rows(report, "input_flow")
    rows = []
    for tag, dimension, saved in machines:
        x, y, z = position(tag)
        adjacent = []
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            key = dimension, x + dx, y + dy, z + dz
            if key in entities:
                adjacent.append({"position": list(key[1:]), "type": entities[key]})
        rows.append(machine_row(tag, dimension, saved, report, energy, inputs, adjacent))
    rows.sort(key=lambda row: (row["dimension"], *row["position"]))
    coverage = {
        "machine_types": dict(Counter(row["type"] for row in rows)),
        "dimensions": dict(Counter(row["dimension"] for row in rows)),
        "states": dict(Counter(row["state"] for row in rows)),
        "acceleration_evidence": dict(Counter(row["acceleration"] for row in rows)),
        "storage_families": dict(storage), "external_block_entity_types": dict(external_types),
        "probe_mods": report.get("mods", []),
        "note": "自然调用与原有加速不能仅由机器 NBT 分开；统一加速标签来自同目录探针报告。存储统计仅表示所读区域中的方块实体，六邻接不证明 AE 网格连接。快照不含外部容器时存储覆盖未知。"
    }
    result = {"read_utc": utc(datetime.now().timestamp()), "source": source_info, "coverage": coverage,
              "machine_count": len(rows), "read_files": len(input_files),
              "latest_file_utc": utc(max(path.stat().st_mtime for path in input_files)) if input_files else None,
              "errors": errors, "machines": rows,
              "scope": "只读已保存数据；运行中未保存的变化不可见，跨区块保存时刻可能不同。快照为探针选定机器，世界模式遍历已保存区域。槽位编号保持原始 NBT，不推断未知宿主的输入输出布局。"}
    return json_safe(result)


def browser_data(value):
    # MCP clients may use JavaScript Number; preserve every Java long exactly.
    if isinstance(value, int) and abs(value) > 9_007_199_254_740_991:
        return str(value)
    if isinstance(value, dict):
        return {key: browser_data(child) for key, child in value.items()}
    if isinstance(value, list):
        return [browser_data(child) for child in value]
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=Path, help="world 路径、探针目录或 machines.nbt")
    parser.add_argument("--latest", action="store_true", help="选取 source 下最新机器快照（直属运行子目录）")
    parser.add_argument("--output", type=Path, help="JSON 导出路径，例如 build/machine-inspector/current.json")
    args = parser.parse_args()
    source = args.source.resolve()
    data = inspect(source, args.latest)
    if args.output:
        output = args.output.resolve().with_suffix(".json")
        if output.is_relative_to(source) and source.is_dir() and (source / "level.dat").exists():
            parser.error("导出路径应放在世界目录之外，例如 build/machine-inspector/current.json")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"machines": data["machine_count"], "errors": data["errors"],
                          "json": str(output)}, ensure_ascii=False))
    else:
        print(json.dumps(data, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
