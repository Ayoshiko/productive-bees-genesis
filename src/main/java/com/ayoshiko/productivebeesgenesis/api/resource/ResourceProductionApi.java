package com.ayoshiko.productivebeesgenesis.api.resource;

import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRecipeIndex;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRollPlan;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import com.ayoshiko.productivebeesgenesis.port.RecipeHolder;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.LinkedHashMap;

/**
 * 扩展资源产物的注册、查询与份数计算入口。常驻 API 不引用 AE2 或第三方资源类。
 * 注册使用 {@link RegisterResourceRecipesEvent}；数据包使用相同的独立配方类型。
 * 本入口不授予资产写权限。第三方机器必须在其实际消费边界预留整份空间，
 * 只抽样并持久化一次结果；异常未知时保留该结果，不能重新调用计划补发。
 */
public final class ResourceProductionApi {
	/** 当前 API 合同版本；资源身份与存档 schema 分别由各自类型定义。 */
	public static final int API_VERSION = 1;

	private ResourceProductionApi() {}

	/**
	 * 返回服务器线程上的不可变快照。按 revision 失效缓存；
	 * 已冻结的旧计划继续使用其原输出，不按新配方重算。
	 */
	public static ResourceRecipeIndex snapshot(MinecraftServer server) {
		return ResourceRuntime.recipes(server);
	}

	/**
	 * 为第三方周期消费者构造与 Genesis 相同的份数计划，不抽样、不扣费或修改世界。
	 * rolls 是每周期产量轮数，gene 是 PB 生产力等级 0..3；原始 amount 不作为物品栈缩放。
	 * 蜂种／花源资格、资源适配器可用性、库存容量与原子提交仍由实际宿主检查。
	 */
	public static ResourceRollPlan plan(ResourceRecipeIndex snapshot, ResourceLocation bee,
			double rolls, int gene, long seed) {
		var recipes = snapshot.forBee(bee);
		if (recipes.isEmpty()) throw new IllegalArgumentException("No resource production recipe for " + bee);
		return new ResourceRollPlan(recipes.stream().flatMap(holder -> holder.value().outputs().stream()).toList(),
				rolls, gene, seed);
	}

	/**
	 * 为已选定的资源离心配方构造统一产物计划。先用 recipe.units(input) 验证蜂种；
	 * 对 n 个输入调用 maximum/sample(n * units)，蜜脾块每个包含四份独立抽样。
	 * productivity 是每份产量轮数，不包含机器并行数；stability 为 [0,1] 的概率加成，
	 * 明确声明 chance=0 的输出保持禁用。计划只计算，不消耗输入或授予库存写权限。
	 */
	public static ResourceRollPlan planCentrifuging(
			com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe recipe,
			int productivity, double stability, long seed) {
		if (productivity < 1 || !Double.isFinite(stability) || stability < 0 || stability > 1) {
			throw new IllegalArgumentException("Invalid resource centrifuge modifiers");
		}
		var outputs = recipe.outputs().stream().map(out -> new ResourceProductionRecipe.Output(
				out.key(), out.exactAmount(), out.chance() == 0 ? 0 : Math.min(1, out.chance() + stability))).toList();
		return new ResourceRollPlan(outputs, productivity, 0, seed);
	}

	/** 运行期发布入口。先收齐默认注册，再由数据包按 ID 覆盖；构建失败不发布部分索引。 */
	@ApiStatus.Internal
	public static ResourceRecipeIndex collect(long revision,
			Collection<RecipeHolder<ResourceProductionRecipe>> datapackRecipes) {
		return collect(revision, datapackRecipes, java.util.List.of());
	}

	/** 两种配方在一个事件和一个不可变代际内发布。 */
	@ApiStatus.Internal
	public static ResourceRecipeIndex collect(long revision,
			Collection<RecipeHolder<ResourceProductionRecipe>> datapackRecipes,
			Collection<RecipeHolder<com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe>> centrifugeRecipes) {
		var event = new RegisterResourceRecipesEvent(revision);
		try {
			MinecraftForge.EVENT_BUS.post(event);
		} catch (RuntimeException | LinkageError failure) {
			try { event.finish(); } catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
			throw failure;
		}
		var merged = new LinkedHashMap<ResourceLocation, RecipeHolder<ResourceProductionRecipe>>();
		for (var recipe : event.finish()) merged.put(recipe.getId(), recipe);
		var centrifuging = new LinkedHashMap<ResourceLocation, RecipeHolder<com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe>>();
		for (var recipe : event.finishedCentrifuging()) centrifuging.put(recipe.getId(), recipe);
		var seenDatapackIds = new java.util.HashSet<ResourceLocation>();
		for (var recipe : datapackRecipes) {
			if (!seenDatapackIds.add(recipe.getId())) throw new IllegalArgumentException("Duplicate datapack recipe id");
			merged.put(recipe.getId(), recipe);
			centrifuging.remove(recipe.getId());
		}
		for (var recipe : centrifugeRecipes) {
			if (!seenDatapackIds.add(recipe.getId())) throw new IllegalArgumentException("Duplicate datapack recipe id");
			centrifuging.put(recipe.getId(), recipe);
			merged.remove(recipe.getId());
		}
		return ResourceRecipeIndex.build(revision, merged.values(), centrifuging.values());
	}
}
