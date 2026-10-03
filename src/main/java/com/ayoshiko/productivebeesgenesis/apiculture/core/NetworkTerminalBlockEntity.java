package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 仅持有当前连接代际；每次最多检查六个已加载邻格，不保存核心或资产身份。 */
public final class NetworkTerminalBlockEntity extends BlockEntity {
	private NetworkCoreBlockEntity connected;
	private Object connectionToken = new Object();
	public NetworkTerminalBlockEntity(BlockPos pos, BlockState state) { super(NetworkContent.TERMINAL_TILE.get(), pos, state); }
	public TerminalScope scope() { return ((NetworkTerminalBlock) getBlockState().getBlock()).scope(); }
	Object token() { return connectionToken; }
	NetworkCoreBlockEntity connection() {
		if (!(level instanceof ServerLevel server) || !server.getServer().isSameThread()) return null;
		NetworkCoreBlockEntity candidate = null;
		if (!isRemoved()) for (var direction : Direction.values()) {
			var pos = worldPosition.relative(direction);
			var chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
			// 未加载邻格可能藏有第二个核心；未知时禁止选择或转绑。
			if (chunk == null) { candidate = null; break; }
			if (chunk.getBlockEntity(pos) instanceof NetworkCoreBlockEntity core && !core.isRemoved()) {
				if (candidate != null) { candidate = null; break; }
				candidate = core;
			}
		}
		if (candidate != connected) { connected = candidate; connectionToken = new Object(); }
		return candidate;
	}
	@Override public void setRemoved() { connected = null; connectionToken = new Object(); super.setRemoved(); }
}
