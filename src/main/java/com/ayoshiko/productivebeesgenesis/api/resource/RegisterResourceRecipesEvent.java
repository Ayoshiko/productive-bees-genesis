package com.ayoshiko.productivebeesgenesis.api.resource;

import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import net.minecraft.resources.ResourceLocation;
import com.ayoshiko.productivebeesgenesis.port.RecipeHolder;
import net.minecraftforge.eventbus.api.Event;
import org.jetbrains.annotations.ApiStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * 每个服务器配方代际首次构建时，在 NeoForge 游戏事件总线同步发布。
 * 第三方模组在监听器内注册默认资源配方；同 ID 的数据包配方优先。
 * 监听器不得递归查询当前快照、保存事件供以后修改，或向后台线程传递事件。
 * 此事件只描述产物，不创建蜜蜂、推进周期或提交资源库存。
 */
public final class RegisterResourceRecipesEvent extends Event {
	private final long revision;
	private final Thread owner = Thread.currentThread();
	private final LinkedHashMap<ResourceLocation, RecipeHolder<ResourceProductionRecipe>> recipes = new LinkedHashMap<>();
	private final LinkedHashMap<ResourceLocation, RecipeHolder<com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe>> centrifuging = new LinkedHashMap<>();
	private boolean open = true;

	@ApiStatus.Internal
	public RegisterResourceRecipesEvent(long revision) {
		if (revision < 0) throw new IllegalArgumentException("Invalid resource recipe revision");
		this.revision = revision;
	}

	/** 当前重载代际；仅用于使消费者缓存失效，不作为存档资源身份。 */
	public long revision() { return revision; }

	/** 注册一个默认配方。重复 ID 直接拒绝，不由监听器执行顺序决定最终产物。 */
	public void register(ResourceLocation recipeId, ResourceProductionRecipe recipe) {
		checkOpen();
		Objects.requireNonNull(recipeId);
		Objects.requireNonNull(recipe);
		if (recipes.containsKey(recipeId) || centrifuging.containsKey(recipeId)) throw new IllegalArgumentException("Duplicate resource recipe: " + recipeId);
		recipes.put(recipeId, new RecipeHolder<>(recipeId, recipe));
	}

	/** 与数据包输出使用相同的数量、概率和身份校验。 */
	public void register(ResourceLocation recipeId, ResourceLocation bee, List<ResourceProductionRecipe.Output> outputs) {
		register(recipeId, new ResourceProductionRecipe(bee, outputs));
	}

	/** 注册每个单蜜脾的离心资源产物；蜜脾块按 PB 原生四份语义处理。 */
	public void registerCentrifuging(ResourceLocation recipeId,
			com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe recipe) {
		checkOpen();
		Objects.requireNonNull(recipeId);
		Objects.requireNonNull(recipe);
		if (recipes.containsKey(recipeId) || centrifuging.containsKey(recipeId)) {
			throw new IllegalArgumentException("Duplicate resource recipe: " + recipeId);
		}
		centrifuging.put(recipeId, new RecipeHolder<>(recipeId, recipe));
	}

	@ApiStatus.Internal
	public List<RecipeHolder<com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe>> finishedCentrifuging() {
		if (open || Thread.currentThread() != owner) throw new IllegalStateException("Registration is not finished");
		return List.copyOf(centrifuging.values());
	}

	@ApiStatus.Internal
	public List<RecipeHolder<ResourceProductionRecipe>> finish() {
		checkOpen();
		open = false;
		return List.copyOf(recipes.values());
	}

	private void checkOpen() {
		if (Thread.currentThread() != owner || !open) {
			throw new IllegalStateException("Resource recipes can only be registered during the synchronous event");
		}
	}
}
