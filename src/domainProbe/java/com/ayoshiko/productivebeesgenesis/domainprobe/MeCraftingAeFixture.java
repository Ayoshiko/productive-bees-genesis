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
import java.math.BigInteger;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import net.minecraft.server.level.ServerPlayer;
import appeng.me.cluster.implementations.CraftingCPUCluster;
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
	private static ServerPlayer owner;
	private static MeBridgeNode bridgeNode;
	private static Map<ProductKey, ProductAmount> beforeBalances;
	private static BigInteger initialInput;
	private static boolean cancellationRestored;
	private static int reloadStage;
	private static Collection<net.minecraft.world.item.crafting.RecipeHolder<?>> savedRecipes;
	static boolean storageVerified;
	private static boolean networkStorage() { return MeBridgeProbe.storage(); }
	private static AEItemKey input() { return networkStorage() ? AEItemKey.of(Items.RAW_IRON) : STONE; }
	private static AEItemKey output() { return networkStorage() ? AEItemKey.of(Items.RAW_GOLD) : IRON; }
	private static ProductKey product(AEItemKey key) { return ProductKeyCodec.item(key.toStack(), owner.registryAccess()); }
	private static BigInteger amount(AEItemKey key) { return core.ownership().readyAuthority().checkpoint().ledger().balances().getOrDefault(product(key), ProductAmount.ZERO).exact(); }
	private static CraftingCPUCluster cpu() { return (CraftingCPUCluster) grid.getCraftingService().getCpus().iterator().next(); }
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
			if (busy || !pattern.getPrimaryOutput().what().equals(output())) return false;
			for (var input : inputs) for (var entry : input) { require(entry.getKey().equals(input()), "Unexpected processing ingredient"); held+=entry.getLongValue(); }
			return true;
		}
	};
	static void seed(NetworkCoreBlockEntity host, ServerPlayer player) {
		core=host; owner=player; var bridge=(MeBridgeBlockEntity) host.getLevel().getBlockEntity(host.getBlockPos().above());
		bridgeNode=(MeBridgeNode)bridge.link(); grid=bridgeNode.getGridNode(Direction.UP).getGrid();
		stock.reset(); stock.add(STONE,64); held=0; busy=true; patterns.clear();
		if (networkStorage()) {
			beforeBalances=core.ownership().readyAuthority().checkpoint().ledger().balances();
			var source=IActionSource.ofPlayer(owner);
			for (long quantity : new long[] {Long.MAX_VALUE, Long.MAX_VALUE, 64}) require(bridgeNode.storage().insert(input(),quantity,Actionable.MODULATE,source)==quantity,"Cannot seed exact crafting input");
			initialInput=amount(input());
		}
		for (var item : List.of(Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.DIAMOND, Items.EMERALD, Items.COAL, Items.REDSTONE, Items.LAPIS_LAZULI, Items.QUARTZ)) {
			var result=networkStorage() && item==Items.IRON_INGOT ? output() : AEItemKey.of(item);
			var encoded=PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(input(),2)), List.of(new GenericStack(result,1)));
			patterns.add(Objects.requireNonNull(PatternDetailsHelper.decodePattern(encoded,host.getLevel())));
		}
		if (!networkStorage()) grid.getStorageService().addGlobalStorageProvider(storageProvider);
		grid.getStorageService().invalidateCache(); grid.getCraftingService().addGlobalCraftingProvider(provider);
		cpuPos=host.getBlockPos().above(2).east(); host.getLevel().setBlockAndUpdate(cpuPos, AEBlocks.CRAFTING_STORAGE_1K.block().defaultBlockState());
	}
	static boolean ready() { return !grid.getCraftingService().getCpus().isEmpty() && (!networkStorage() || grid.getStorageService().getCachedInventory().get(input())==Long.MAX_VALUE); }
	static void beforeCancel() { if (networkStorage()) require(core.setProductionRunning(false), "Cannot pause return destination"); }
	static boolean cancellationReady() {
		if (!networkStorage()) return jobs()==0 && stone()==64;
		if (jobs()!=0) return false;
		if (!cancellationRestored) {
			require(stone()==56 && cpu().craftingLogic.getInventory().list.get(input())==8,"Paused cancellation lost CPU custody");
			savedRecipes=List.copyOf(owner.serverLevel().getRecipeManager().getRecipes());
			var retained=savedRecipes.stream().filter(holder -> !(holder.value() instanceof cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe recipe)
					|| recipe.getRecipeOutputs().keySet().stream().noneMatch(stack -> stack.is(Items.RAW_IRON))).toList();
			require(retained.size()<savedRecipes.size(),"No raw iron recipe removed");
			owner.serverLevel().getRecipeManager().replaceRecipes(retained);
			com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex.rebuild(owner.serverLevel().getRecipeManager());
			com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
			require(core.setProductionRunning(true),"Cannot resume return destination"); cancellationRestored=true; reloadStage=1; return false;
		}
		if (com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies.peek(owner.serverLevel())==null) return false;
		if (reloadStage==1) {
			require(stone()==56 && cpu().craftingLogic.getInventory().list.get(input())==8,"Removed recipe returned illegal stock or lost CPU custody");
			require(bridgeNode.storage().insert(input(),1,Actionable.SIMULATE,cpu().getSrc())==0,"Removed product admitted");
			require(bridgeNode.storage().extract(input(),1,Actionable.SIMULATE,cpu().getSrc())==1,"Old owned product became unrecoverable");
			owner.serverLevel().getRecipeManager().replaceRecipes(savedRecipes);
			com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex.rebuild(owner.serverLevel().getRecipeManager());
			com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet(); reloadStage=2; return false;
		}
		return stone()==64;
	}
	static long jobs() { return grid.getCraftingService().getCpus().stream().filter(ICraftingCPU::isBusy).count(); }
	static long stone() { return networkStorage() ? amount(input()).subtract(initialInput).longValueExact()+64 : stock.get(STONE); }
	static long iron() { return networkStorage() ? amount(output()).subtract(beforeBalances.getOrDefault(product(output()),ProductAmount.ZERO).exact()).longValueExact() : stock.get(IRON); }
	static void deliver() {
		long amount=held/2; require(amount==2,"Wrong external processing amount: "+held);
		// 独立下单的 CPU 只确认成品，不保管最终结果；完整入网路径会继续存入存储。
		long accepted=grid.getStorageService().getInventory().insert(output(), amount, Actionable.MODULATE, networkStorage() ? cpu().getSrc() : IActionSource.empty());
		require(accepted==2,"ME network refused expected completed output"); held-=accepted*2;
	}
	static void close() {
		require(jobs()==0 && held==0 && stone()==60 && iron()==2,"Final ME material accounting changed");
		grid.getCraftingService().removeGlobalCraftingProvider(provider);
		if (networkStorage()) {
			var source=IActionSource.ofPlayer(owner);
			for (long quantity : new long[] {Long.MAX_VALUE, Long.MAX_VALUE, 60}) require(bridgeNode.storage().extract(input(),quantity,Actionable.MODULATE,source)==quantity,"Cannot return remaining crafting input");
			require(bridgeNode.storage().extract(output(),2,Actionable.MODULATE,source)==2,"Cannot return completed crafting output");
			require(beforeBalances.equals(core.ownership().readyAuthority().checkpoint().ledger().balances()),"Crafting round trip changed other ledger assets");
			require(cancellationRestored && reloadStage==2,"Missing paused cancellation and recipe restoration"); storageVerified=true;
			MeStorageAeFixture.finish(core);
		} else grid.getStorageService().removeGlobalStorageProvider(storageProvider);
		core.getLevel().setBlockAndUpdate(cpuPos, Blocks.AIR.defaultBlockState());
	}
}
