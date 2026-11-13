#!/usr/bin/env python3
"""Read-only MCP stdio tools for saved PBG worlds and existing probe snapshots.
No SDK is required. stdout contains only newline-delimited JSON-RPC messages.
"""
import argparse
import json
from pathlib import Path
import sys

import inspect_machines as backend


WORKSPACE = Path(__file__).resolve().parents[1]
ROOTS = []
DEFAULT_SOURCE = None
SUMMARY_FIELDS = (
    "dimension", "position", "type", "kind", "state", "saved_utc", "acceleration",
    "energy", "inventory_count", "occupied_slots", "progress", "processing_time",
    "active_paid_lanes", "paused_work_count", "pending_items", "pending_fluid"
)


def resolve_source(source=None):
    path = Path(source).expanduser() if source else DEFAULT_SOURCE
    path = (WORKSPACE / path).resolve() if not path.is_absolute() else path.resolve()
    if not any(path.is_relative_to(root) for root in ROOTS):
        raise ValueError("Source is outside configured roots. Use list_machine_worlds.")
    if path.is_dir() and not (path / "level.dat").is_file():
        if not any((path / name).is_file() for name in ("end-machines.nbt", "start-machines.nbt")):
            path = backend.select_source(path, latest=True).resolve()
        else:
            path = backend.select_source(path, latest=False).resolve()
        if not any(path.is_relative_to(root) for root in ROOTS):
            raise ValueError("Selected snapshot is outside configured roots.")
    return path


def read_source(source=None):
    path = resolve_source(source)
    return backend.inspect(path)


def context(data):
    return {
        "source": data["source"], "read_utc": data["read_utc"],
        "source_latest_mtime_utc": data["latest_file_utc"],
        "read_files": data["read_files"], "total_machines": data["machine_count"],
        "errors": data["errors"], "scope": data["scope"],
        "integer_encoding": "Integers beyond ±9007199254740991 are exact decimal strings."
    }


def page(items, offset, limit):
    if offset < 0 or not 1 <= limit <= 200:
        raise ValueError("offset must be >= 0; limit must be between 1 and 200.")
    end = offset + limit
    return {"total": len(items), "offset": offset,
            "next_offset": end if end < len(items) else None, "items": items[offset:end]}


def source_entry(path, kind):
    metadata = path / "level.dat" if kind == "world" else path
    return {"source": str(path), "kind": kind,
            "mtime_utc": backend.utc(metadata.stat().st_mtime)}


def list_machine_worlds(offset=0, limit=50):
    """Discover only configured roots, direct worlds and the existing probe layout."""
    found = {}
    for root in ROOTS:
        if not root.exists():
            continue
        if root.is_file():
            found[str(root)] = source_entry(root, "snapshot")
            continue
        directories = [root, *(path for path in root.iterdir() if path.is_dir())]
        for directory in directories:
            for world in (directory, directory / "saves" / "world"):
                if (world / "level.dat").is_file():
                    actual = world.resolve()
                    if any(actual.is_relative_to(base) for base in ROOTS):
                        found[str(actual)] = source_entry(actual, "world")
            for name in ("end-machines.nbt", "start-machines.nbt"):
                snapshot = directory / name
                if snapshot.is_file():
                    actual = snapshot.resolve()
                    if any(actual.is_relative_to(base) for base in ROOTS):
                        found[str(actual)] = source_entry(actual, "snapshot")
    entries = sorted(found.values(), key=lambda row: (row["mtime_utc"], row["source"]), reverse=True)
    return {"roots": [str(root) for root in ROOTS], "default_source": str(DEFAULT_SOURCE),
            "discovery": "Configured roots, direct child worlds and probe-directory saves/world; no whole-disk scan.",
            **page(entries, offset, limit)}


def inspect_machines(world_or_snapshot=None, type=None, position=None, state=None,
                     dimension=None, offset=0, limit=30):
    data = read_source(world_or_snapshot)
    rows = [row for row in data["machines"]
            if (not type or type in row["type"])
            and (position is None or row["position"] == position)
            and (not state or row["state"] == state)
            and (not dimension or row["dimension"] == dimension)]
    summaries = [{name: row[name] for name in SUMMARY_FIELDS} for row in rows]
    return {**context(data), **page(summaries, offset, limit)}


def get_machine(position, dimension="minecraft:overworld", source=None, include_nbt=False):
    data = read_source(source)
    match = next((row for row in data["machines"]
                  if row["position"] == position and row["dimension"] == dimension), None)
    if match is None:
        return {**context(data), "machine": None, "message": "No saved machine at this position and dimension."}
    machine = dict(match)
    raw = machine.pop("raw")
    machine["raw_nbt_keys"] = list(raw)
    if include_nbt:
        machine["raw"] = raw
    return {**context(data), "machine": machine}


def machine_coverage(source=None):
    data = read_source(source)
    return {**context(data), "coverage": data["coverage"]}


SOURCE = {"type": "string", "description": "Optional absolute source returned by list_machine_worlds. Omit for configured default/latest probe snapshot."}
POSITION = {"type": "array", "items": {"type": "integer"}, "minItems": 3, "maxItems": 3,
            "description": "Exact [x,y,z] block coordinates, including negatives."}
PAGING = {
    "offset": {"type": "integer", "minimum": 0, "default": 0},
    "limit": {"type": "integer", "minimum": 1, "maximum": 200, "default": 30}
}
TOOLS = [
    {"name": "list_machine_worlds",
     "description": "列出限定 roots 下可用的世界和机器快照，含实际绝对路径、mtime 和默认源；分页，避免猜路径。",
     "inputSchema": {"type": "object", "properties": PAGING}},
    {"name": "inspect_machines",
     "description": "查询已保存机器摘要，可按机器类型片段、坐标、维度、状态过滤并分页。不会默认返回槽位明细或完整 NBT；省略源使用默认/最新快照。",
     "inputSchema": {"type": "object", "properties": {
         "world_or_snapshot": SOURCE, "type": {"type": "string", "description": "Machine ID substring, e.g. apiary or mek_centrifuge."},
         "position": POSITION, "state": {"type": "string", "description": "Exact state from a prior response."},
         "dimension": {"type": "string"}, **PAGING}}},
    {"name": "get_machine",
     "description": "读取指定单机的槽位和完整组件、蜂槽、已付/暂停工作、待排/custody、能量、AE过滤、升级与探针指标；include_nbt=true 才附完整原始 NBT。",
     "inputSchema": {"type": "object", "required": ["position"], "properties": {
         "position": POSITION, "dimension": {"type": "string", "default": "minecraft:overworld"},
         "source": SOURCE, "include_nbt": {"type": "boolean", "default": False}}}},
    {"name": "machine_coverage",
     "description": "查看本次保存数据覆盖：机器类型/维度/状态、探针统一加速证据、离线外部存储方块实体。未知运行时倍率和AE连通性保持未知。",
     "inputSchema": {"type": "object", "properties": {"source": SOURCE}}}
]
FUNCTIONS = {tool["name"]: globals()[tool["name"]] for tool in TOOLS}
for tool in TOOLS:
    tool["inputSchema"]["additionalProperties"] = False
    tool["annotations"] = {"readOnlyHint": True, "destructiveHint": False,
                           "idempotentHint": True, "openWorldHint": False}


def reply(request_id, result=None, error=None):
    response = {"jsonrpc": "2.0", "id": request_id}
    response["error" if error is not None else "result"] = error if error is not None else result
    sys.stdout.write(json.dumps(response, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def serve():
    for line in sys.stdin:
        try:
            request = json.loads(line)
        except json.JSONDecodeError as error:
            reply(None, error={"code": -32700, "message": str(error)})
            continue
        if "id" not in request:
            continue
        request_id, method = request["id"], request["method"]
        if method == "initialize":
            requested = request.get("params", {}).get("protocolVersion")
            version = requested if requested in ("2024-11-05", "2025-03-26", "2025-06-18") else "2025-06-18"
            reply(request_id, {"protocolVersion": version, "capabilities": {"tools": {}},
                               "serverInfo": {"name": "pbg-machine-inspector", "version": "1.0.0"},
                               "instructions": "只读保存世界/探针快照。先 list_machine_worlds 或省略 source；查询用分页摘要，单机才取 NBT。未保存的运行时变化不可见。"})
        elif method == "ping":
            reply(request_id, {})
        elif method == "tools/list":
            reply(request_id, {"tools": TOOLS})
        elif method == "tools/call":
            params = request.get("params", {})
            name = params.get("name")
            if name not in FUNCTIONS:
                reply(request_id, error={"code": -32602, "message": "Unknown tool: " + str(name)})
                continue
            try:
                data = backend.browser_data(FUNCTIONS[name](**params.get("arguments", {})))
                reply(request_id, {"content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}],
                                   "structuredContent": data, "isError": False})
            except Exception as error:
                # The MCP boundary reports a failed read; it never returns an empty success.
                reply(request_id, {"content": [{"type": "text", "text": type(error).__name__ + ": " + str(error)}],
                                   "isError": True})
        else:
            reply(request_id, error={"code": -32601, "message": "Method not found: " + method})


def main():
    global ROOTS, DEFAULT_SOURCE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", action="append", type=Path,
                        help="Allowed source root; repeat for multiple roots.")
    parser.add_argument("--default-source", type=Path,
                        help="World, snapshot or parent of probe runs (latest snapshot selected per call).")
    args = parser.parse_args()
    ROOTS = [path.resolve() for path in (args.root or [
        WORKSPACE / "run" / "saves", WORKSPACE / "build" / "performance-20261110"])]
    DEFAULT_SOURCE = (args.default_source or WORKSPACE / "build" / "performance-20261110").resolve()
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    serve()


if __name__ == "__main__":
    main()
