package com.ayoshiko.productivebeesgenesis.recipe;

import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.mojang.serialization.MapCodec;
import mekanism.common.attachments.containers.ContainerType;
import mekanism.common.recipe.upgrade.MekanismShapedRecipe;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * 蜂箱和离心机的合成升级按目标布局迁移库存与流体，单输入继承完整保存组件。
 * 自定义 NBT 不属于 Mekanism 标准升级数据，必须在预览和实际合成时一并转移。
 * 任一资产无法完整接收时返回空结果，所有输入快照保持只读。
 */
public final class ApiaryShapedRecipe extends MekanismShapedRecipe {

	/** JSON 与网络解码均包装本类，确保样板预览使用完整机器迁移流程。 */
	public static final RecipeSerializer<ApiaryShapedRecipe> SERIALIZER = new ApiaryShapedRecipeSerializer();

	/**
	 * 构造自定义 ShapedRecipe
	 *
	 * @param internal 内部 ShapedRecipe（由 MekanismShapedRecipe 包装）
	 */
	public ApiaryShapedRecipe(ShapedRecipe internal) {
		super(internal);
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return SERIALIZER;
	}

	@Override
	public boolean matches(CraftingInput input, Level level) {
		return getInternal().matches(input, level) && !assemble(input, level.registryAccess()).isEmpty();
	}

	/** 完整转移库存、流体和自定义状态；任一资产无法容纳时拒绝消耗输入。 */
	@Override
	public ItemStack assemble(CraftingInput inv, HolderLookup.Provider provider) {
		// 输出物品的注册布局决定库存与流体迁移目标。
		ItemStack template = getResultItem(provider);
		if (template.isEmpty()) {
			return ItemStack.EMPTY;
		}
		if (!(template.getItem() instanceof BlockItem blockItem)) {
			// 非方块输出：委托 super（本配方理论只用于机器方块合成）
			return super.assemble(inv, provider);
		}
		Block outputBlock = blockItem.getBlock();
		boolean isApiary = outputBlock instanceof MekApiaryBlock<?, ?>;
		boolean isCentrifuge = outputBlock instanceof MekCentrifugeBlock<?, ?>;
		if (!isApiary && !isCentrifuge) {
			// 非本模组机器：委托 super
			return super.assemble(inv, provider);
		}
		// 扫描合成矩阵，收集同类型机器方块输入
		List<ItemStack> machineInputs = ApiaryCraftingDataTransfer.collectMachineInputs(inv, isApiary, isCentrifuge);
		for (ItemStack input : inv.items()) {
			if (input.getItem() instanceof BlockItem ingredient
					&& (ingredient.getBlock() instanceof MekApiaryBlock<?, ?>
							|| ingredient.getBlock() instanceof MekCentrifugeBlock<?, ?>)
					&& !machineInputs.contains(input) && !input.isComponentsPatchEmpty()) {
				// 跨宿主辅助机器没有配置、蜜蜂和已付工作的接收方，只能消耗未携带数据的物品。
				return ItemStack.EMPTY;
			}
		}
		if (machineInputs.isEmpty()) {
			// 无机器方块输入（新合成机器方块），委托 super
			return super.assemble(inv, provider);
		}

		// MEK 通用合成会重建源流体罐，并按可插入顺序重排库存。
		// 拆卸物品的保存布局由本模组迁移；标准升级、能量和权限仍交给 MEK。
		List<ItemStack> standardInputs = new ArrayList<>(inv.size());
		for (ItemStack input : inv.items()) {
			ItemStack standard = input;
			if (machineInputs.contains(input)) {
				standard = input.copy();
				standard.remove(ContainerType.ITEM.getComponentType());
				standard.remove(ContainerType.FLUID.getComponentType());
			}
			standardInputs.add(standard);
		}
		ItemStack result = super.assemble(CraftingInput.of(inv.width(), inv.height(), standardInputs), provider);
		if (result.isEmpty()) {
			return ItemStack.EMPTY;
		}
		if (machineInputs.size() == 1) {
			// 名称、红石、频率和其他保存组件沿单输入升级原样继承，不覆盖目标物品的默认定义。
			result.applyComponents(machineInputs.getFirst().getComponentsPatch());
		}
		boolean inventoryTransferred = isApiary
				? ApiaryCraftingInventoryTransfer.transfer(machineInputs, result)
				: CentrifugeCraftingInventoryTransfer.transfer(machineInputs, result);
		if (!inventoryTransferred || !MachineCraftingFluidTransfer.transfer(machineInputs, result)) {
			return ItemStack.EMPTY;
		}

		// 蜜蜂、已付工作和自定义配置共用原有保存合同。
		try {
			ApiaryCraftingDataTransfer.transferAllBlockEntityData(machineInputs, result, outputBlock, isApiary);
		} catch (RuntimeException e) {
			DevLog.error("合成升级: 自定义数据无法完整转移，拒绝合成", e);
			return ItemStack.EMPTY;
		}

		return result;
	}

	/**
	 * ApiaryShapedRecipe 序列化器 — 委托 vanilla {@link ShapedRecipe.Serializer} 的 Codec
	 * <br/>
	 * 1.21.1 的配方加载/同步完全基于 {@link MapCodec} 与 {@link StreamCodec}（无 fromJson）。
	 * 本序列化器委托 vanilla CODEC/STREAM_CODEC，解码（反序列化）后包装为 {@link ApiaryShapedRecipe}，
	 * 使服务端加载配方时得到本类实例，确保 {@link #assemble} 覆盖（合成升级数据转移）真正生效。
	 * JSON/网络格式与 vanilla ShapedRecipe 完全一致，兼容 datagen 输出。
	 * <p>
	 * <b>实现细节</b>：xmap 的 from（编码方向）必须使用
	 * {@link mekanism.common.recipe.WrappedShapedRecipe#getInternal}（与 MEK_DATA 的
	 * {@code MekanismRecipeSerializer.wrapped} 完全一致），将包装配方还原为构造时传入的
	 * internal ShapedRecipe 后再交给 vanilla CODEC 编码。使用 identity 函数（{@code recipe -> recipe}）
	 * 直接编码子类实例会导致 datagen 编码失败（ItemStack.STRICT_CODEC 报
	 * "Item must not be minecraft:air"），原因在于 vanilla CODEC 的 forGetter 按 ShapedRecipe
	 * 字段布局访问，包装链上的字段访问路径不一致。
	 */
	public static final class ApiaryShapedRecipeSerializer implements RecipeSerializer<ApiaryShapedRecipe> {

		/** JSON 解码 Codec — 委托 vanilla CODEC，解码后包装为 ApiaryShapedRecipe */
		private static final MapCodec<ApiaryShapedRecipe> CODEC = ShapedRecipe.Serializer.CODEC.xmap(
				ApiaryShapedRecipe::new,
				mekanism.common.recipe.WrappedShapedRecipe::getInternal);

		/** 网络同步 StreamCodec — 委托 vanilla STREAM_CODEC，解码后包装为 ApiaryShapedRecipe */
		private static final StreamCodec<RegistryFriendlyByteBuf, ApiaryShapedRecipe> STREAM_CODEC =
				ShapedRecipe.Serializer.STREAM_CODEC.map(
						ApiaryShapedRecipe::new,
						mekanism.common.recipe.WrappedShapedRecipe::getInternal);

		@Override
		public MapCodec<ApiaryShapedRecipe> codec() {
			return CODEC;
		}

		@Override
		public StreamCodec<RegistryFriendlyByteBuf, ApiaryShapedRecipe> streamCodec() {
			return STREAM_CODEC;
		}
	}
}
