package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeGeneration;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeGenerator;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkExecutor;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.mek.WeightedTypeSelector;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.MultiFlowerBeeAdapter;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.ayoshiko.productivebeesgenesis.util.WannaBeeAmberAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 只在动态周期边界捕获喂食快照；外部生成发生在宿主发布请求之后。 */
public final class DynamicBeeAdapter {
	public static BeeGenerator generator(ResourceLocation type) {
		if (type == null) return BeeGenerator.STATIC;
		if (PBConstants.WANNA_TYPE.equals(type)) return BeeGenerator.WANNA_BEE;
		if (PBConstants.MYRIADCREATIONS_TYPE.equals(type)) return BeeGenerator.MYRIAD;
		return MultiFlowerBeeAdapter.isMultiFlowerBee(type) ? BeeGenerator.MULTI_FLOWER : BeeGenerator.STATIC;
	}
	public static String recipe(ResourceLocation type) {
		return "productivebeesgenesis:generated/" + type.getNamespace() + "/" + type.getPath();
	}
	public static boolean sameRequest(BeeRecord expected, BeeRecord current) {
		return current.generation() != null && current.generation().phase() == BeeGeneration.Phase.STARTED
				&& expected.id().equals(current.id()) && expected.member().equals(current.member())
				&& expected.random().equals(current.random()) && expected.plan().equals(current.plan())
				&& expected.generation().source().equals(current.generation().source());
	}
	public static boolean flower(ResourceLocation type, ItemStack stack) {
		if (generator(type) == BeeGenerator.MYRIAD)
			return BlockFlowerMatcher.matches(stack, BeeInfoHelper.getFlowerPreference(type));
		if (generator(type) != BeeGenerator.WANNA_BEE) return MultiFlowerBeeAdapter.matchesFlower(type, stack);
		var data = AmberEntityFlowerHelper.getAmberEntityData(stack);
		if (data == null) return false;
		var id = ResourceLocation.tryParse(data.copyTag().getString("id"));
		return id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id);
	}
	public static BeeWorkExecutor.Context prepare(ServerLevel level, BeeRecord bee, List<FeedingSlotStore.Slot> slots,
			int ticks, BeeWorkExecutor.Cycle cycle, BeeWorkExecutor.Context context) {
		if (bee.plan().generator() == BeeGenerator.STATIC || ticks == 0 || !bee.drained()) return context;
		int required = cycle == null ? bee.plan().cycleTicks() : cycle.cycleTicks();
		if ((long) bee.progress() + ticks < required) return context;
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Capture bee generation on the server thread");
		int group = slots.get(bee.slot()).group();
		var items = new ListTag();
		for (var slot : slots) if (slot.group() == group && slot.active()) items.add(slot.item().unit().copy());
		if (items.isEmpty()) return context;
		var source = new CompoundTag();
		source.putString("bee", bee.plan().beeType());
		source.putLong("epoch", ProductiveBeesGenesis.RECIPE_VERSION.get());
		source.putBoolean("allFlowers", BalanceConfig.apiaryProduceAllFlowers());
		source.put("items", items);
		return context.withGenerationSource(new AssetImage(source));
	}
	public static BeeGeneration generate(ServerLevel level, BlockPos origin, BeeRecord bee) {
		var request = bee.generation();
		if (!level.getServer().isSameThread() || request == null || request.phase() != BeeGeneration.Phase.STARTED)
			throw new IllegalStateException("No published bee generation request");
		try {
			var source = request.source().copy();
			if (!source.getAllKeys().equals(Set.of("bee", "epoch", "allFlowers", "items"))
					|| !bee.plan().beeType().equals(source.getString("bee")) || !source.contains("items", Tag.TAG_LIST))
				throw new IllegalArgumentException("Invalid bee generation source");
			if (bee.plan().generator() == BeeGenerator.MYRIAD) return request.ready(myriadPool(level, bee));
			var stacks = new ArrayList<ItemStack>();
			for (var raw : source.getList("items", Tag.TAG_COMPOUND))
				stacks.add(StaticFeedingAdapter.parse((CompoundTag) raw, level.registryAccess()));
			List<ItemStack> pool;
			if (bee.plan().generator() == BeeGenerator.WANNA_BEE) {
				var candidates = new ArrayList<CustomData>();
				for (var stack : stacks) {
					var entity = AmberEntityFlowerHelper.getAmberEntityData(stack);
					if (entity != null && flower(PBConstants.WANNA_TYPE, stack)) candidates.add(entity);
				}
				pool = WannaBeeAmberAdapter.generateEventPool(level, origin, candidates);
			} else if (bee.plan().generator() == BeeGenerator.MULTI_FLOWER) {
				// 私有喂食视图只复用现行选择／染料映射；不注册实体或执行旧机器 tick。
				var feeder = new FeederSlotManager(stacks.size(), stacks.size(), 1);
				feeder.buildFeederSlots(null);
				for (int index = 0; index < stacks.size(); index++) feeder.getFeederSlot(index).setStack(stacks.get(index));
				var type = ResourceLocation.parse(bee.plan().beeType());
				pool = source.getBoolean("allFlowers") ? MultiFlowerBeeAdapter.allProduceFromFeeder(type, feeder, level)
						: MultiFlowerBeeAdapter.sampleProduceFromFeeder(type, feeder, level);
			} else throw new IllegalArgumentException("Static bee used a dynamic generator");
			var drops = new ArrayList<BeeGeneration.Drop>();
			for (var stack : pool) if (!stack.isEmpty()) {
				var sourceKey = ProductKeyCodec.item(stack, level.registryAccess());
				var output = StaticApiaryAdapter.output(sourceKey, bee.plan().generatedCombBlock(), level.registryAccess());
				drops.add(new BeeGeneration.Drop(output, stack.getCount()));
			}
			return request.ready(drops);
		} catch (RuntimeException failure) {
			ProductiveBeesGenesis.LOGGER.error("Bee generation requires recovery: member={} bee={} cycle={} origin={}",
					bee.member(), bee.id(), bee.random().cursor(), origin, failure);
			return request.unknown(failure.getClass().getName() + ": " + String.valueOf(failure.getMessage()));
		}
	}
	private static List<BeeGeneration.Drop> myriadPool(ServerLevel level, BeeRecord bee) {
		var snapshot = MyriadBeeTypeCache.snapshot();
		boolean blocks = bee.plan().generatedCombBlock();
		var types = blocks ? snapshot.combBlockBeeTypes() : snapshot.beeTypes();
		if (!MyriadBeeTypeCache.isWarmupComplete()) throw new IllegalStateException("Myriad candidates are not ready");
		if (types.isEmpty()) return List.of();
		var selector = WeightedTypeSelector.getInstance();
		selector.rebuildWeightsIfNeeded(level);
		var selected = selector.selectForProcess(0, 1, level, types, null);
		if (selected.isEmpty()) throw new IllegalStateException("Myriad selection lost eligible candidates");
		var templates = snapshot.selectTemplates(selected, blocks, level.getRandom());
		var weights = selector.getWeightsFor(selected);
		var drops = new ArrayList<BeeGeneration.Drop>(selected.size());
		for (int index = 0; index < selected.size(); index++) {
			var template = templates.get(selected.get(index));
			if (template == null || template.isEmpty()) throw new IllegalStateException("Missing selected Myriad template");
			drops.add(new BeeGeneration.Drop(ProductKeyCodec.item(template, level.registryAccess()), 1, weights[index]));
		}
		return drops;
	}
	private DynamicBeeAdapter() { }
}
