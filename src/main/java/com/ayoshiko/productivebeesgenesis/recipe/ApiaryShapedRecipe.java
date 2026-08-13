package com.ayoshiko.productivebeesgenesis.recipe;

import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.mojang.serialization.MapCodec;
import mekanism.common.recipe.upgrade.MekanismShapedRecipe;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
	 * 自定义 ShapedRecipe 子类 — 修复合成升级时蜜蜂数据丢失问题
	 * <br/>
	 * 继承 {@link MekanismShapedRecipe}，重写 {@link #assemble} 方法：
	 * <ol>
	 *   <li>先调用 {@code super.assemble()} 处理 MEK 标准升级数据（速度/能量等，走 RecipeUpgradeData 路径）</li>
	 *   <li>super 返回非空后，从 {@link CraftingInput} 中查找输入的机器方块（通过 BlockItem 类型识别），
	 *       读取输入机器方块的 BLOCK_ENTITY_DATA 并合并到结果（蜜蜂槽/PB升级/喂食槽等）</li>
	 *   <li>super 返回 EMPTY 时（输出槽有物品导致 MEK ItemRecipeData 转移失败）走降级路径：
	 *       手动转移完整 BLOCK_ENTITY_DATA，保证输出槽有物品时仍可合成且数据不丢失</li>
	 * </ol>
	 *
	 * <p><b>根因 A（蜜蜂丢失）</b>：{@link MekanismShapedRecipe#assemble} 只处理 {@code RecipeUpgradeType}
	 * 路径的 MEK 标准升级数据，不转移 BLOCK_ENTITY_DATA 中的自定义 NBT 字段。蜂箱内的蜜蜂/PB升级数据
	 * 存储在 BLOCK_ENTITY_DATA 的自定义 NBT 字段，不在任何 RecipeUpgradeType 路径，合成升级时全部丢失。
	 *
	 * <p><b>根因 B（assemble 从未执行）</b>：MEK_DATA 序列化器反序列化配方时创建的是
	 * {@link MekanismShapedRecipe} 实例（{@code MekanismShapedRecipe::new}），本类的 {@link #assemble}
	 * 覆盖从未被调用。修复：注册自定义序列化器 {@link #SERIALIZER}（{@code productivebeesgenesis:apiary_shaped}），
	 * 反序列化时创建本类实例，使 {@link #assemble} 覆盖真正生效。
	 *
	 * <p><b>方案优势</b>：相比事件处理器（{@code ItemCraftedEvent}），在 assemble 中转移数据更可靠：
	 * CraftingInput 是合成前的完整快照，inv.getItem(i) 返回未消耗的输入物品，避免事件处理器在
	 * 输入被消耗后读到空物品的时序问题。
	 *
	 * <p><b>合并策略</b>（多输入场景，如 4 个基础蜂箱合成 1 个 BASIC 工厂蜂箱）：
	 * <ul>
	 *   <li>蜜蜂槽：取并集，按输入顺序填充到目标容量上限，超出部分丢弃并 WARN（仅蜂箱适用）</li>
	 *   <li>PB 升级数量：累加所有输入的数量，按 {@link PbUpgradeType#getMaxCount()} 受类型上限限制</li>
	 *   <li>其他字段（喂食槽/能量槽/流体罐/蜂笼 I/O 槽/选中槽位等）：取首个非空</li>
	 * </ul>
	 *
	 * <p><b>设计原则</b>：
	 * <ul>
	 *   <li>SRP：仅负责合成路径的数据转移与合并，不涉及持久化序列化逻辑</li>
	 *   <li>OCP：继承 MekanismShapedRecipe 扩展 assemble，不修改父类</li>
	 *   <li>DIP：依赖 PbUpgradeType 抽象获取升级上限，不硬编码</li>
	 * </ul>
	 *
	 * <p><b>线程安全</b>：assemble 在服务端主线程触发，CraftingInput 为合成矩阵快照，无需额外同步。
	 */
public final class ApiaryShapedRecipe extends MekanismShapedRecipe {

	/**
	 * 自定义配方序列化器 — 修复运行时反序列化丢失 assemble 覆盖的问题
	 * <br/>
	 * <b>根因</b>：MEK_DATA 序列化器（{@code SimpleCraftingRecipeSerializer} 包装
	 * {@code MekanismShapedRecipe::new}）在游戏加载配方时创建的是 <b>MekanismShapedRecipe</b>
	 * 实例，本类的 {@link #assemble} 覆盖从未被执行，导致合成升级时蜜蜂/PB升级数据丢失。
	 * <p>
	 * <b>修复</b>：注册本序列化器（id = {@code productivebeesgenesis:apiary_shaped}），
	 * 反序列化时通过 {@link ApiaryShapedRecipeSerializer#fromJson} 创建 ApiaryShapedRecipe 实例，
	 * 使 {@link #assemble} 覆盖生效。datagen 输出 JSON 格式与 vanilla ShapedRecipe 完全一致。
	 */
	public static final RecipeSerializer<ApiaryShapedRecipe> SERIALIZER = new ApiaryShapedRecipeSerializer();

	// ===== NBT key 常量 — 统一引用 apiary 包内 public 常量（单一事实来源）=====
	// ApiarySlotSerializer.NBT_KEY_BEE_SLOTS / ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS /
	// ApiarySlotManager.DEFAULT_BEE_SLOT_COUNT 已提升为 public，此处直接引用避免跨包重复字面量

	/** 用于 WARN 日志的 feature 标识（DevLog 节流） */
	private static final String DEV_FEATURE = "crafting_upgrade";

	/**
	 * 构造自定义 ShapedRecipe
	 *
	 * @param internal 内部 ShapedRecipe（由 MekanismShapedRecipe 包装）
	 */
	public ApiaryShapedRecipe(ShapedRecipe internal) {
		super(internal);
	}

	/**
	 * 返回自定义序列化器 {@link #SERIALIZER}
	 * <br/>
	 * 必须返回本模组注册的 {@code apiary_shaped} 序列化器：datagen 按此序列化器 id 输出
	 * 配方 JSON，游戏加载时经 {@link ApiaryShapedRecipeSerializer#fromJson} 创建
	 * {@link ApiaryShapedRecipe} 实例，确保 {@link #assemble} 覆盖生效。
	 */
	@Override
	public RecipeSerializer<?> getSerializer() {
		return SERIALIZER;
	}

	/**
	 * 重写 assemble — 在 super.assemble 处理 MEK 标准升级数据后，转移/合并 BLOCK_ENTITY_DATA
	 * <br/>
	 * CraftingInput 是合成前的完整快照，inv.getItem(i) 返回未消耗的输入物品，
	 * 避免 ItemCraftedEvent 在输入被消耗后读到空物品的时序问题。
	 *
	 * @param inv      合成矩阵快照
	 * @param provider 注册表访问器
	 * @return 合成结果物品（已转移自定义 NBT），失败时返回 super.assemble 结果
	 */
	@Override
	public ItemStack assemble(CraftingInput inv, HolderLookup.Provider provider) {
		// 0. 预取输出模板，确定输出方块类型（super 可能因输出槽有物品返回 EMPTY，需先确定类型）
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
		if (machineInputs.isEmpty()) {
			// 无机器方块输入（新合成机器方块），委托 super
			return super.assemble(inv, provider);
		}

		// 1. 调用 super 处理 MEK 标准升级数据（速度/能量等）
		ItemStack result = super.assemble(inv, provider);
		if (result.isEmpty()) {
			// 2. 降级路径：super 因输出槽有物品（MEK ItemRecipeData 转移失败）返回 EMPTY。
			//    先复制输入机器的全部组件（mekanism:upgrades 升级/能量/流体/配置等），
			//    再覆盖 BLOCK_ENTITY_DATA（含蜜蜂/PB升级/输出槽物品），
			//    保证输出槽有物品时仍可合成且 MEK 升级与自定义数据均不丢失。
			ItemStack fallback = template.copy();
			fallback.applyComponents(machineInputs.get(0).getComponents());
			ApiaryCraftingDataTransfer.transferAllBlockEntityData(machineInputs, fallback, outputBlock, isApiary);
			return fallback;
		}

		// 3. 转移/合并自定义 BLOCK_ENTITY_DATA（蜜蜂槽/PB升级等）
		try {
			ApiaryCraftingDataTransfer.transferAllBlockEntityData(machineInputs, result, outputBlock, isApiary);
		} catch (RuntimeException e) {
			// 防御：数据转移失败不应影响正常合成流程，返回 super.assemble 的结果
			DevLog.error("合成升级: BLOCK_ENTITY_DATA 转移失败,返回未转移自定义数据的结果", e);
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
