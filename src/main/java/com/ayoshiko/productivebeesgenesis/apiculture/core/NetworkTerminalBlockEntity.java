package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 每次最多检查六个已加载邻格；合成资产独立保存，方块实体仅保存引用标记。 */
public final class NetworkTerminalBlockEntity extends BlockEntity {
	private NetworkCoreBlockEntity connected;
	private Object connectionToken = new Object();
	private boolean craftingReferenced;
	private TerminalCraftingAccount crafting;
	private String craftingFailure;
	public NetworkTerminalBlockEntity(BlockPos pos, BlockState state) { super(NetworkContent.TERMINAL_TILE.get(), pos, state); }
	public TerminalScope scope() { return ((NetworkTerminalBlock) getBlockState().getBlock()).scope(); }
	public boolean combined() { return ((NetworkTerminalBlock) getBlockState().getBlock()).combined(); }
	Object token() { return connectionToken; }
	boolean craftingReferenced() { return craftingReferenced; }
	void referenceCrafting() { if (!craftingReferenced) { craftingReferenced = true; setChanged(); } }
	TerminalCraftingAccount crafting(NetworkCoreBlockEntity core) {
		if (craftingFailure != null) return null;
		try {
			if (crafting == null || !crafting.matches(core.owner(), level.dimension().location(), worldPosition)) crafting = TerminalCraftingAccount.attach(this, core);
			return crafting.available() && crafting.matches(core.owner(), level.dimension().location(), worldPosition) ? crafting : null;
		} catch (RuntimeException failure) {
			craftingFailure = failure.toString();
			com.mojang.logging.LogUtils.getLogger().error("Crafting account unavailable at {} {}", level.dimension().location(), worldPosition, failure); return null;
		}
	}
	@Override protected void loadAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries); craftingReferenced = tag.getBoolean("craftingReferenced"); crafting = null; craftingFailure = null;
	}
	@Override protected void saveAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries); if (craftingReferenced) tag.putBoolean("craftingReferenced", true);
	}
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
		if (candidate != connected) { connected = candidate; connectionToken = new Object(); crafting = null; craftingFailure = null; }
		return candidate;
	}
	@Override public void setRemoved() { connected = null; crafting = null; connectionToken = new Object(); super.setRemoved(); }
}
