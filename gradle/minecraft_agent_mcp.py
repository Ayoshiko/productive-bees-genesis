#!/usr/bin/env python3
"""Persistent stdio bridge to the installed Minecraft HTTP MCP mod."""
import argparse
import json
import sys
import urllib.request
import uuid

ENDPOINT = "http://127.0.0.1:18781/mcp"
REQUEST_ID = 0


def rpc(method, params):
    global REQUEST_ID
    REQUEST_ID += 1
    request = urllib.request.Request(
        ENDPOINT,
        json.dumps({"jsonrpc": "2.0", "id": REQUEST_ID,
                    "method": method, "params": params}).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"})
    # The installed HTTP server is stateless. A failed call is returned, never retried.
    with urllib.request.urlopen(request, timeout=15) as response:
        message = json.load(response)
    if "error" in message:
        raise RuntimeError(json.dumps(message["error"], ensure_ascii=False))
    return message["result"]


def result(data, is_error=False):
    return {"content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}],
            "structuredContent": data, "isError": is_error}


def text_data(response):
    if response.get("isError"):
        raise RuntimeError(json.dumps(response, ensure_ascii=False))
    return json.loads(next(part["text"] for part in response["content"] if part["type"] == "text"))


def minecraft_call(name, args=None):
    """Pass through the real tool result, including images and upstream errors."""
    return rpc("tools/call", {"name": name, "arguments": args or {}})


def minecraft_tools(name=None):
    tools = rpc("tools/list", {})["tools"]
    if name is not None:
        tools = [tool for tool in tools if tool["name"] == name]
    return result({"endpoint": ENDPOINT, "tools": tools})


def minecraft_status():
    upstream = minecraft_call("get_player_info")
    if upstream.get("isError"):
        return upstream
    player = text_data(upstream)
    return result({"endpoint": ENDPOINT, "http_connected": True,
                   "player_present": "dimension" in player,
                   "player": {key: player[key] for key in (
                       "name", "dimension", "position", "blockPosition", "gameMode", "error")
                       if key in player},
                   "scope": "Current live client/player; no saved-world data."})


def worldedit_command(command=None, commands=None):
    if (command is None) == (commands is None):
        raise ValueError("Provide exactly one of command or commands.")
    requested = [command] if command is not None else commands
    encoded = []
    for entry in requested:
        entry = entry.strip()
        if entry.startswith("//") and not entry.startswith("///"):
            # Mod 1.1.0 strips one slash in readCommands and another before sendCommand.
            # SafetyValidator sees //pos1 and matches the allowed root /pos1.
            encoded.append("/" + entry)
        elif entry == "/worldedit" or entry.startswith("/worldedit "):
            encoded.append(entry)
        else:
            raise ValueError("Use a WorldEdit //command or /worldedit command.")
    upstream = minecraft_call("execute_commands", {"commands": encoded, "validate_safety": True})
    return result({"commands": requested, "upstream": upstream, "applied": None,
                   "verification": "Upstream confirms sending only. Inspect blocks/NBT or a fresh chat receipt to confirm execution."},
                  upstream.get("isError", False))


def live_block_nbt(pos, dimension=None, nbt_path="{}"):
    player_result = minecraft_call("get_player_info")
    if player_result.get("isError"):
        return player_result
    player = text_data(player_result)
    current_dimension = player["dimension"]
    if dimension is not None and dimension != current_dimension:
        raise ValueError("Player is in " + current_dimension
                         + "; switch to the requested dimension before querying its block.")
    coordinates = " ".join(str(pos[axis]) for axis in ("x", "y", "z"))
    marker = "PBG_NBT_" + uuid.uuid4().hex
    end_marker = marker + "_END"
    # Vanilla 1.21.1 root NBT path {} returns saveWithFullMetadata from the server.
    component = [{"text": marker},
                 {"nbt": nbt_path, "block": coordinates, "interpret": False},
                 {"text": end_marker}]
    command = "tellraw @s " + json.dumps(component, separators=(",", ":"))
    sent = minecraft_call("execute_commands", {"commands": [command], "validate_safety": True})
    if sent.get("isError"):
        return sent
    receipt = text_data(sent)
    if receipt.get("acceptedCount") != 1:
        return result({"position": pos, "dimension": current_dimension,
                       "server_confirmed": False, "command_receipt": receipt}, True)
    chat = minecraft_call("wait_for_chat", {"text": marker, "timeout_ms": 5000})
    if chat.get("isError"):
        return chat
    captured = text_data(chat)
    if not captured.get("matched"):
        return result({"position": pos, "dimension": current_dimension,
                       "server_confirmed": False, "command_receipt": receipt,
                       "chat_receipt": captured}, True)
    message = captured["message"]
    raw = message["text"]
    start = raw.index(marker) + len(marker)
    end = raw.index(end_marker, start)
    snbt = raw[start:end]
    return result({"position": pos, "dimension": current_dimension, "nbt_path": nbt_path,
                   "server_confirmed": True,
                   "block_entity_found": bool(snbt) if nbt_path == "{}" else None,
                   "path_has_text": bool(snbt), "nbt_snbt": snbt if nbt_path == "{}" else None,
                   "nbt_text": snbt, "chat_timestamp_ms": message["timestampMs"],
                   "chat_source": message["source"],
                   "scope": "Server saveWithFullMetadata. Root {} is exact SNBT; child paths use Minecraft Tag.getAsString and join multiple matches. Empty child text does not prove the block entity is absent."})


def live_machine_status(pos, dimension=None):
    player = text_data(minecraft_call("get_player_info"))
    current_dimension = player["dimension"]
    if dimension is not None and dimension != current_dimension:
        raise ValueError("Player is in " + current_dimension
                         + "; switch to the requested dimension before querying its machine.")
    request_id = uuid.uuid4().hex
    marker = "PBG_MACHINE_" + request_id + " "
    coordinates = " ".join(str(pos[axis]) for axis in ("x", "y", "z"))
    command = "execute as @s run productivebeesgenesis dev machine " + coordinates + " " + request_id
    sent = text_data(minecraft_call("execute_commands", {"commands": [command], "validate_safety": True}))
    if sent.get("acceptedCount") != 1:
        return result({"server_confirmed": False, "command_receipt": sent}, True)
    captured = text_data(minecraft_call("wait_for_chat", {"text": marker, "timeout_ms": 10000}))
    if not captured.get("matched"):
        return result({"server_confirmed": False, "message": "No current machine diagnostic receipt.",
                       "command_receipt": sent, "chat_receipt": captured}, True)
    message = captured["message"]
    payload = message["text"].split(marker, 1)[1]
    data = json.loads(payload)
    return result({"server_confirmed": True, "position": pos, "dimension": current_dimension,
                   "machine": data, "chat_timestamp_ms": message["timestampMs"],
                   "scope": "Current server-thread runtime state; no cached saved-world snapshot."})


POSITION = {"type": "object", "required": ["x", "y", "z"],
            "properties": {axis: {"type": "integer"} for axis in ("x", "y", "z")},
            "additionalProperties": False}
TOOLS = [
    {"name": "minecraft_status",
     "description": "查看实时 Minecraft HTTP 连接、当前玩家、维度和坐标。游戏关闭时直接返回连接错误，不自动重试。",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "minecraft_tools",
     "description": "从运行中的真实 Minecraft MCP 发现可用工具和完整 schema；可按工具名筛选，不缓存旧能力。",
     "inputSchema": {"type": "object", "properties": {"name": {"type": "string"}}}},
    {"name": "minecraft_call",
     "description": "原样调用 minecraft_tools 返回的真实工具名与参数，保留文本、图像和错误。编辑必须遵守已授权范围；发送成功不等于服务器执行成功。",
     "inputSchema": {"type": "object", "required": ["name"], "properties": {
         "name": {"type": "string"}, "args": {"type": "object"}}}},
    {"name": "worldedit_command",
     "description": "通过现有 execute_commands 执行 WorldEdit //命令，适配双斜杠并始终 validate_safety=true。需要实例 allowedCommands 精确包含 /pos1、/copy 等根；不启用 unsafe。返回发送回执，须另读方块/NBT确认执行。",
     "inputSchema": {"type": "object", "properties": {
         "command": {"type": "string"},
         "commands": {"type": "array", "items": {"type": "string"}, "minItems": 1}},
         "oneOf": [{"required": ["command"]}, {"required": ["commands"]}]}},
    {"name": "live_machine_status",
     "description": "按坐标读取本模组机器的实时服务器诊断：输入开关、过滤器保管、AE供能与频道、节点状态及取料退避。需要包含dev machine诊断命令的版本；仅查询单机，不扫描世界。",
     "inputSchema": {"type": "object", "required": ["pos"], "properties": {
         "pos": POSITION, "dimension": {"type": "string"}}}},
    {"name": "live_block_nbt",
     "description": "读取当前玩家维度指定方块的服务端 NBT。nbt_path默认{}返回完整SNBT，也可只取指定Minecraft NBT路径以缩小结果；子路径返回Minecraft文本表示，空结果不证明没有方块实体。唯一聊天标记关联本次回执，需要allowedCommands包含tellraw。",
     "inputSchema": {"type": "object", "required": ["pos"], "properties": {
         "pos": POSITION, "dimension": {"type": "string"},
         "nbt_path": {"type": "string", "default": "{}",
                      "description": "Minecraft NBT path, e.g. energy_containers[0].stored; default {} reads the full root."}}}}
]
FUNCTIONS = {tool["name"]: globals()[tool["name"]] for tool in TOOLS}
for tool in TOOLS:
    tool["inputSchema"]["additionalProperties"] = False
    readonly = tool["name"] in ("minecraft_status", "minecraft_tools", "live_block_nbt", "live_machine_status")
    tool["annotations"] = {"readOnlyHint": readonly, "destructiveHint": not readonly,
                           "idempotentHint": readonly, "openWorldHint": False}


def reply(request_id, data=None, error=None):
    payload = {"jsonrpc": "2.0", "id": request_id}
    payload["error" if error is not None else "result"] = error if error is not None else data
    print(json.dumps(payload, ensure_ascii=False), flush=True)


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
            version = request.get("params", {}).get("protocolVersion", "2025-06-18")
            reply(request_id, {"protocolVersion": version, "capabilities": {"tools": {}},
                               "serverInfo": {"name": "pbg-minecraft-agent", "version": "1.0.0"},
                               "instructions": "先 minecraft_status / minecraft_tools。离线错误不重试。命令回执只证明已发送，另取世界或唯一聊天回执验证。"})
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
                reply(request_id, FUNCTIONS[name](**params.get("arguments", {})))
            except Exception as error:
                # Protocol boundary: retain the actual failure without retry or success fallback.
                reply(request_id, {"content": [{"type": "text",
                                               "text": type(error).__name__ + ": " + str(error)}],
                                   "isError": True})
        else:
            reply(request_id, error={"code": -32601, "message": "Method not found: " + method})


def main():
    global ENDPOINT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default=ENDPOINT)
    ENDPOINT = parser.parse_args().endpoint
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    serve()


if __name__ == "__main__":
    main()
