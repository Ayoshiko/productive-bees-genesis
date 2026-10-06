package com.ayoshiko.productivebeesgenesis.domainprobe;

import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import appeng.core.definitions.AEBlocks;
import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeNode;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 正式 AE2 CPU 和计划引擎，有限测试库存及可暂停的外部加工方。 */
final class MeCraftingAeFixture {
	static IGrid grid;
	private static NetworkCoreBlockEntity core;
	private static BlockPos cpuPos;
	private static final AEItemKey STONE = AEItemKey.of(Items.STONE), IRON = AEItemKey.of(Items.IRON_INGOT);
	private static final KeyCounter stock = new KeyCounter();
	private static final List<IPatternDetails> patterns = new ArrayList<>();
	static long held;
	static boolean busy = true;
	private static final MEStorage storage = new MEStorage() {
		public Component getDescription() { return Component.literal("Finite ME crafting fixture"); }
		public void getAvailableStacks(KeyCounter out) { for (var entry : stock) if (entry.getLongValue()>0) out.add(entry.getKey(), entry.getLongValue()); }
		public long insert(AEKey key, long amount, Actionable action, IActionSource source) { if (action == Actionable.MODULATE) stock.add(key, amount); return amount; }
		public long extract(AEKey key, long amount, Actionable action, IActionSource source) { long taken=Math.min(amount, stock.get(key)); if (action == Actionable.MODULATE) stock.remove(key,taken); return taken; }
	};
	private static final IStorageProvider storageProvider = mounts -> mounts.mount(storage, 0);
	private static final ICraftingProvider provider = new ICraftingProvider() {
		public List<IPatternDetails> getAvailablePatterns() { return patterns; }
		public boolean isBusy() { return busy; }
		public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
			if (busy || !pattern.getPrimaryOutput().what().equals(IRON)) return false;
			for (var input : inputs) for (var entry : input) { require(entry.getKey().equals(STONE), "Unexpected processing ingredient"); held+=entry.getLongValue(); }
			return true;
		}
	};
	static void seed(NetworkCoreBlockEntity host) {
		core=host; var bridge=(MeBridgeBlockEntity) host.getLevel().getBlockEntity(host.getBlockPos().above());
		grid=((MeBridgeNode)bridge.link()).getGridNode(Direction.UP).getGrid();
		stock.reset(); stock.add(STONE,64); held=0; busy=true; patterns.clear();
		for (var item : List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.DIAMOND, Items.EMERALD, Items.COAL, Items.REDSTONE, Items.LAPIS_LAZULI, Items.QUARTZ)) {
			var encoded=PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(STONE,2)), List.of(new GenericStack(AEItemKey.of(item),1)));
			patterns.add(Objects.requireNonNull(PatternDetailsHelper.decodePattern(encoded,host.getLevel())));
		}
		grid.getStorageService().addGlobalStorageProvider(storageProvider); grid.getStorageService().invalidateCache(); grid.getCraftingService().addGlobalCraftingProvider(provider);
		cpuPos=host.getBlockPos().above(2).east(); host.getLevel().setBlockAndUpdate(cpuPos, AEBlocks.CRAFTING_STORAGE_1K.block().defaultBlockState());
	}
	static boolean ready() { return !grid.getCraftingService().getCpus().isEmpty(); }
	static long jobs() { return grid.getCraftingService().getCpus().stream().filter(ICraftingCPU::isBusy).count(); }
	static long stone() { return stock.get(STONE); }
	static long iron() { return stock.get(IRON); }
	static void deliver() {
		long amount=held/2; require(amount==2,"Wrong external processing amount: "+held);
		// 独立下单的 CPU 只确认成品，不保管最终结果；完整入网路径会继续存入存储。
		long accepted=grid.getStorageService().getInventory().insert(IRON, amount, Actionable.MODULATE, IActionSource.empty());
		require(accepted==2,"ME network refused expected completed output"); held-=accepted*2;
	}
	static void close() {
		require(jobs()==0 && held==0 && stone()==60 && iron()==2,"Final ME material accounting changed");
		grid.getCraftingService().removeGlobalCraftingProvider(provider); grid.getStorageService().removeGlobalStorageProvider(storageProvider);
		core.getLevel().setBlockAndUpdate(cpuPos, Blocks.AIR.defaultBlockState());
	}
}
