package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** 服务端已合并配方的只读投影；不携带账户、所有者或操作权限。 */
public record ResourceRecipeView(ResourceLocation id, ResourceLocation bee,
		List<ResourceProductionRecipe.Output> outputs, int processingTime, boolean includePbOutputs) {
	public ResourceRecipeView(ResourceLocation id, ResourceLocation bee,
			List<ResourceProductionRecipe.Output> outputs, int processingTime) {
		this(id, bee, outputs, processingTime, false);
	}
	public ResourceRecipeView {
		java.util.Objects.requireNonNull(id);
		java.util.Objects.requireNonNull(bee);
		outputs = List.copyOf(outputs);
		if (id.toString().length() > 256 || bee.toString().length() > 256 || processingTime < 0
				|| outputs.isEmpty() || outputs.size() > ResourceProductionRecipe.MAX_OUTPUTS) {
			throw new IllegalArgumentException("Invalid resource recipe view");
		}
	}

	public boolean centrifuging() { return processingTime != 0; }

	/** 保守内存预算包含身份和条目开销，不依赖外部资源 codec。 */
	public long budgetBytes() {
		long bytes = 256L + 2L * (id.toString().length() + bee.toString().length());
		for (var output : outputs) bytes += 128L + output.key().save().sizeInBytes() + 2L * output.exactAmount().toString().length();
		return bytes;
	}

	public static ResourceRecipeView read(RegistryFriendlyByteBuf buffer) {
		var id = ResourceLocation.parse(buffer.readUtf(256));
		var bee = ResourceLocation.parse(buffer.readUtf(256));
		int time = buffer.readVarInt();
		int size = buffer.readVarInt();
		if (size < 1 || size > ResourceProductionRecipe.MAX_OUTPUTS) throw new IllegalArgumentException("Invalid output count");
		var outputs = new java.util.ArrayList<ResourceProductionRecipe.Output>(size);
		for (int i = 0; i < size; i++) outputs.add(ResourceOutputCodecs.read(buffer));
		return new ResourceRecipeView(id, bee, outputs, time, buffer.readBoolean());
	}

	public void write(RegistryFriendlyByteBuf buffer) {
		buffer.writeUtf(id.toString(), 256);
		buffer.writeUtf(bee.toString(), 256);
		buffer.writeVarInt(processingTime);
		buffer.writeVarInt(outputs.size());
		for (var output : outputs) ResourceOutputCodecs.write(buffer, output);
		buffer.writeBoolean(includePbOutputs);
	}

	/** 离心同蜂种只展示运行期实际选中的首个配方。 */
	public static List<ResourceRecipeView> collect(ResourceRecipeIndex index) {
		var result = new java.util.ArrayList<ResourceRecipeView>();
		for (var bee : index.beeTypes()) {
			for (var holder : index.forBee(bee)) {
				result.add(new ResourceRecipeView(holder.id(), bee, holder.value().outputs(), 0));
			}
		}
		var seen = new java.util.HashSet<ResourceLocation>();
		for (var holder : index.centrifugeRecipes()) {
			var recipe = holder.value();
			if (seen.add(recipe.bee())) result.add(new ResourceRecipeView(holder.id(), recipe.bee(), recipe.outputs(), recipe.processingTime(), recipe.includePbOutputs()));
		}
		result.sort(java.util.Comparator.comparing(recipe -> recipe.id().toString()));
		ResourceCatalogBuffer.checkBudget(result);
		return List.copyOf(result);
	}
}
