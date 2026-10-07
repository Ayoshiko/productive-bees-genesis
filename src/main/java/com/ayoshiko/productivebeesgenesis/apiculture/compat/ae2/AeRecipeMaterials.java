package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.config.FuzzyMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageHelper;
import appeng.me.helpers.PlayerSource;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalBudget;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** 只存活于一次服务器请求；按配方物品索引查询，实际提取使用同一玩家和封存节点。 */
final class AeRecipeMaterials implements TerminalMaterialSource {
	private final MeBridgeNode bridgeNode;
	private final MeBridgeBlockEntity bridge;
	private final ServerPlayer player;
	private final NetworkSavedData excluded;
	private final IGridNode node;
	private final IGrid grid;
	private final IActionSource source;
	AeRecipeMaterials(MeBridgeNode bridgeNode, MeBridgeBlockEntity bridge, ServerPlayer player, NetworkSavedData excluded) {
		this.bridgeNode = bridgeNode; this.bridge = bridge; this.player = player; this.excluded = excluded;
		node = Objects.requireNonNull(bridgeNode.getGridNode(Direction.UP)); grid = node.getGrid();
		source = new PlayerSource(player, () -> node) {
			@Override public <T> Optional<T> context(Class<T> type) {
				return type == AeRecipeMaterials.class ? Optional.of(type.cast(AeRecipeMaterials.this)) : super.context(type);
			}
		};
	}
	boolean excludes(NetworkSavedData data) { return data == excluded; }
	@Override public boolean valid() {
		return player.server.isSameThread() && player.isAlive() && !player.isRemoved() && !player.isSpectator()
				&& player.level() == bridge.getLevel() && bridge.owner().equals(player.getUUID())
				&& player.serverLevel().mayInteract(player, bridge.getBlockPos())
				&& bridgeNode.status() == MeBridgeStatus.ONLINE && bridgeNode.getGridNode(Direction.UP) == node && node.getGrid() == grid;
	}
	@Override public List<ItemStack> candidates(CraftingRecipe recipe, int limit) {
		if (!valid() || !MeTerminalBudget.expensive(player.server)) return null;
		var inventory = grid.getStorageService().getCachedInventory();
		if (bridgeNode.aggregationFaulted()) return null;
		var items = new LinkedHashSet<Item>(); int examples = 0;
		for (var ingredient : recipe.getIngredients()) {
			var values = ingredient.getItems(); examples += values.length; if (examples > 256) return null;
			for (var stack : values) if (!stack.isEmpty()) items.add(stack.getItem());
		}
		var result = new ArrayList<ItemStack>(); int visited = 0;
		for (var item : items) for (var entry : inventory.findFuzzy(AEItemKey.of(item), FuzzyMode.IGNORE_ALL)) {
			if (++visited > 128) return null;
			if (!(entry.getKey() instanceof AEItemKey key) || entry.getLongValue() <= 0) continue;
			var unit = key.toStack(1);
			if (unit.getItem() instanceof WirelessTerminalItem || recipe.getIngredients().stream().noneMatch(i -> i.test(unit.copy()))) continue;
			if (!key.equals(AEItemKey.of(unit)) || !valid()) return null;
			// 同网蜂业账本已由内部事务计入；模拟和执行都排除该挂载，不能减饱和汇总猜外部数量。
			long available = grid.getStorageService().getInventory().extract(key, 576, appeng.api.config.Actionable.SIMULATE, source);
			if (available < 0 || available > 576) throw new IllegalStateException("Invalid ME material simulation amount");
			if (available > 0) {
				if (result.size() == limit) return null;
				result.add(unit.copyWithCount((int) available));
			}
		}
		return List.copyOf(result);
	}
	@Override public int extract(ItemStack requested) {
		if (!valid()) return 0;
		var key = AEItemKey.of(requested);
		if (key == null || requested.isEmpty() || requested.getCount() > Math.min(64, requested.getMaxStackSize())) throw new IllegalArgumentException("Invalid ME material request");
		long actual = StorageHelper.poweredExtraction(grid.getEnergyService(), grid.getStorageService().getInventory(), key, requested.getCount(), source);
		if (actual < 0 || actual > requested.getCount()) throw new IllegalStateException("Invalid actual ME material amount");
		return (int) actual;
	}
	@Override public String description() { return bridge.getLevel().dimension().location() + " " + bridge.getBlockPos().toShortString(); }
}
