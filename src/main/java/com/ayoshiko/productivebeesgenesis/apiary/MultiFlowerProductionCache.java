package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.BeeTypeNormalizer;
import com.ayoshiko.productivebeesgenesis.util.MultiFlowerBeeAdapter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 每台蜂箱独占的全花源模板缓存；仅在服务器线程访问，模板只读。 */
final class MultiFlowerProductionCache {

	private static final int MAX_TYPES = 3;
	private final Map<ResourceLocation, List<ItemStack>> outputs = new HashMap<>();
	private FeederSlotManager lastFeeder;
	private Level lastLevel;
	private int lastFeederVersion;
	private long lastRecipeVersion;

	List<ItemStack> get(ResourceLocation beeType, FeederSlotManager feeder, Level level) {
		if (beeType == null || feeder == null) return List.of();
		int feederVersion = feeder.getFlowerCacheVersion();
		long recipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (lastFeeder != feeder || lastLevel != level || lastFeederVersion != feederVersion
				|| lastRecipeVersion != recipeVersion) {
			outputs.clear();
			lastFeeder = feeder;
			lastLevel = level;
			lastFeederVersion = feederVersion;
			lastRecipeVersion = recipeVersion;
		}
		ResourceLocation key = BeeTypeNormalizer.resolveLoadedBeeType(beeType);
		List<ItemStack> cached = outputs.get(key);
		if (cached != null) return cached;
		List<ItemStack> resolved = List.copyOf(MultiFlowerBeeAdapter.allProduceFromFeeder(key, feeder, level));
		// 三种已支持策略之外的请求不能让实例缓存无界增长。
		if (outputs.size() < MAX_TYPES && MultiFlowerBeeAdapter.isMultiFlowerBee(key)) outputs.put(key, resolved);
		return resolved;
	}
}
