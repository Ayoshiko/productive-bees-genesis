package com.ayoshiko.productivebeesgenesis.recipe;

import java.util.ArrayList;
import java.util.List;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.FactoryApiaryConfig;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugePbUpgradeHandler;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

import com.mojang.serialization.MapCodec;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.interfaces.IHasTileEntity;
import mekanism.common.recipe.upgrade.MekanismShapedRecipe;
import mekanism.common.tier.FactoryTier;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

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

	// ===== NBT key 常量（与 ApiarySlotSerializer / ApiaryPbUpgradeHandler 保持一致）=====
	// 注：原常量为包私有（apiary 包内），此处直接复用字符串字面量避免跨包暴露常量；
	// 任何 key 变更需同步本处（DRY 弱化换可读性，权衡可接受）。

	/** NBT key — 蜜蜂槽数组（与 ApiarySlotSerializer.NBT_KEY_BEE_SLOTS 一致） */
	private static final String NBT_KEY_BEE_SLOTS = "productivebeesgenesis_apiary_bee_slots";

	/** NBT key — 蜂箱 PB 升级安装数量（与 ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS 一致） */
	private static final String NBT_KEY_APIARY_PB_UPGRADE_COUNTS = "productivebeesgenesis_pb_upgrade_counts";

	/** 基础蜂箱（非工厂版）默认蜜蜂槽位 — 与 ApiarySlotManager.DEFAULT_BEE_SLOT_COUNT 一致 */
	private static final int BASIC_APIARY_BEE_SLOT_COUNT = 3;

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
		List<ItemStack> machineInputs = collectMachineInputs(inv, isApiary, isCentrifuge);
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
			transferAllBlockEntityData(machineInputs, fallback, outputBlock, isApiary);
			return fallback;
		}

		// 3. 转移/合并自定义 BLOCK_ENTITY_DATA（蜜蜂槽/PB升级等）
		try {
			transferAllBlockEntityData(machineInputs, result, outputBlock, isApiary);
		} catch (RuntimeException e) {
			// 防御：数据转移失败不应影响正常合成流程，返回 super.assemble 的结果
			DevLog.error("合成升级: BLOCK_ENTITY_DATA 转移失败,返回未转移自定义数据的结果", e);
		}

		return result;
	}

	/**
	 * 将机器输入的 BLOCK_ENTITY_DATA 转移到合成结果
	 * <br/>
	 * 单输入深拷贝完整数据；多输入按合并策略合并（蜜蜂槽取并集、PB升级累加、其他取首个非空）。
	 * 正常路径与降级路径共用，保证数据转移逻辑一致。
	 * <p>
	 * 崩溃修复：BLOCK_ENTITY_DATA 组件使用 {@code CustomData.CODEC_WITH_ID} 校验顶层
	 * {@code "id"}（BlockEntityType 注册键），必须写入目标方块的 BlockEntityType id，
	 * 否则保存玩家背包/物品时抛 "Missing id for entity" 崩溃。
	 *
	 * @param inputs      同类型机器方块输入（已确认非空）
	 * @param dest        合成结果物品
	 * @param outputBlock 输出方块（用于多输入时查询蜜蜂槽容量）
	 * @param isApiary    输出是否为蜂箱（决定是否合并蜜蜂槽）
	 */
	private static void transferAllBlockEntityData(List<ItemStack> inputs, ItemStack dest, Block outputBlock, boolean isApiary) {
		String targetTileId = resolveBlockEntityTypeId(outputBlock);
		if (inputs.size() == 1) {
			// 单输入场景：直接深拷贝 BLOCK_ENTITY_DATA 到输出
			transferBlockEntityDataDirect(inputs.get(0), dest, targetTileId);
		} else {
			// 多输入场景：按合并策略合并 NBT 后写入输出
			CompoundTag mergedNbt = mergeMachineInputs(inputs, outputBlock, isApiary, targetTileId);
			if (mergedNbt != null) {
				dest.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(mergedNbt));
			}
		}
	}

	/**
	 * 解析输出方块的 BlockEntityType 注册键（用于 BLOCK_ENTITY_DATA 的 "id" 字段）
	 * <br/>
	 * 本模组机器（蜂箱/离心机）实现 {@code IHasTileEntity}，通过 {@code getTileType()}
	 * 获取 {@link BlockEntityType}，再查 {@link BuiltInRegistries#BLOCK_ENTITY_TYPE} 的注册键。
	 * 解析失败返回 null（调用方保留输入 NBT 中已有的 id 作为兜底）。
	 *
	 * @param outputBlock 输出方块
	 * @return BlockEntityType 注册键字符串，解析失败返回 null
	 */
	@Nullable
	private static String resolveBlockEntityTypeId(Block outputBlock) {
		if (outputBlock instanceof IHasTileEntity<?> hasTile) {
			BlockEntityType<?> type = hasTile.getTileType().get();
			ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
			if (key != null) {
				return key.toString();
			}
		}
		return null;
	}

	/**
	 * 收集合成矩阵中同类型的机器方块输入
	 * <br/>
	 * "同类型" 指与输出同种机器（蜂箱 / 离心机），不区分工厂等级。
	 * 例如：输出为 BASIC 工厂蜂箱时，所有基础蜂箱 / 任意工厂蜂箱输入均被收集。
	 *
	 * @param inv              合成矩阵快照
	 * @param expectApiary     输出是否为蜂箱
	 * @param expectCentrifuge 输出是否为离心机
	 * @return 同类型机器方块输入列表（保持矩阵扫描顺序）
	 */
	private static List<ItemStack> collectMachineInputs(CraftingInput inv, boolean expectApiary, boolean expectCentrifuge) {
		List<ItemStack> inputs = new ArrayList<>(inv.size());
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (!(stack.getItem() instanceof BlockItem bi)) continue;
			Block b = bi.getBlock();
			if (expectApiary && b instanceof MekApiaryBlock<?, ?>) {
				inputs.add(stack);
			} else if (expectCentrifuge && b instanceof MekCentrifugeBlock<?, ?>) {
				inputs.add(stack);
			}
		}
		return inputs;
	}

	/**
	 * 单输入场景：直接将输入物品的 BLOCK_ENTITY_DATA 深拷贝到输出物品
	 * <br/>
	 * 原理：CustomData.copyTag() 返回 CompoundTag 的深拷贝，
	 * CustomData.of(CompoundTag) 包装为新组件，set() 写入输出 ItemStack。
	 * 输入无 BLOCK_ENTITY_DATA（新合成的机器方块）时直接返回，不抛 NPE。
	 * <p>
	 * 崩溃修复：BLOCK_ENTITY_DATA 组件（{@code CustomData.CODEC_WITH_ID}）校验顶层
	 * {@code "id"} 字段，<b>不能 remove</b>。将 "id" 替换为目标方块的 BlockEntityType 注册键，
	 * 既满足编码校验，又保证放置时方块实体类型匹配、数据正确加载。
	 * 目标 id 解析失败时保留输入 NBT 中已有的 id（兜底，避免编码崩溃）。
	 *
	 * @param src          输入机器方块物品（已确认同类型）
	 * @param dest         合成输出物品
	 * @param targetTileId 目标方块的 BlockEntityType 注册键，null 时保留源 id
	 */
	private static void transferBlockEntityDataDirect(ItemStack src, ItemStack dest, @Nullable String targetTileId) {
		CustomData srcData = src.get(DataComponents.BLOCK_ENTITY_DATA);
		if (srcData == null) {
			// 输入无 BLOCK_ENTITY_DATA（新合成机器方块），无需转移
			return;
		}
		CompoundTag nbt = srcData.copyTag();
		// 替换为目标方块的 BlockEntityType 注册键（不能 remove：CODEC_WITH_ID 校验顶层 id）
		if (targetTileId != null) {
			nbt.putString("id", targetTileId);
		}
		dest.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(nbt));
	}

	/**
	 * 多输入场景：按合并策略合并所有输入的 BLOCK_ENTITY_DATA
	 * <br/>
	 * 合并规则：
	 * <ul>
	 *   <li>蜜蜂槽（仅蜂箱）：取并集，按输入顺序填充到目标容量上限，超出 WARN</li>
	 *   <li>PB 升级数量：累加所有输入数量，按 {@link PbUpgradeType#getMaxCount()} 上限限制</li>
	 *   <li>其他字段：取首个非空（首输入的 NBT 作为 base，后续 merge 仅覆盖合并字段）</li>
	 *   <li>"id"：替换为目标方块的 BlockEntityType 注册键（不能 remove，见 {@link #transferBlockEntityDataDirect}）</li>
	 * </ul>
	 *
	 * @param inputs       同类型机器方块输入列表（已确认非空且 size >= 2）
	 * @param outputBlock  合成输出方块（用于查询目标容量）
	 * @param isApiary     输出是否为蜂箱（决定是否合并蜜蜂槽）
	 * @param targetTileId 目标方块的 BlockEntityType 注册键，null 时保留源 id
	 * @return 合并后的 CompoundTag，所有输入均无数据时返回 null
	 */
	private static CompoundTag mergeMachineInputs(List<ItemStack> inputs, Block outputBlock, boolean isApiary,
			@Nullable String targetTileId) {
		// 收集所有输入的 NBT（深拷贝）
		List<CompoundTag> nbts = new ArrayList<>(inputs.size());
		for (ItemStack input : inputs) {
			CustomData data = input.get(DataComponents.BLOCK_ENTITY_DATA);
			if (data == null) continue;
			try {
				nbts.add(data.copyTag());
			} catch (Exception e) {
				DevLog.error("合成升级: 读取输入 BLOCK_ENTITY_DATA 失败,跳过该输入", e);
			}
		}
		if (nbts.isEmpty()) {
			return null;
		}

		// 第一个非空 NBT 作为 base，保留所有 "其他字段"（喂食槽/能量槽/流体罐/蜂笼 I/O/选中槽位等）
		CompoundTag merged = nbts.get(0);

		// 合并蜜蜂槽（仅蜂箱）
		if (isApiary) {
			int targetCapacity = resolveApiaryBeeSlotCapacity(outputBlock);
			mergeBeeSlots(merged, nbts, targetCapacity);
		}

		// 合并 PB 升级数量（蜂箱用 NBT_KEY_APIARY_PB_UPGRADE_COUNTS,离心机用 MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS）
		String pbUpgradeKey = isApiary ? NBT_KEY_APIARY_PB_UPGRADE_COUNTS
				: MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS;
		mergePbUpgradeCounts(merged, nbts, pbUpgradeKey);

		// 替换为目标方块的 BlockEntityType 注册键（不能 remove：CODEC_WITH_ID 校验顶层 id）
		if (targetTileId != null) {
			merged.putString("id", targetTileId);
		}

		return merged;
	}

	/**
	 * 合并蜜蜂槽数据 — 取并集，按输入顺序填充到目标容量上限
	 * <br/>
	 * 原理：遍历所有输入 NBT 的 {@link #NBT_KEY_BEE_SLOTS} ListTag，
	 * 依次将每个蜜蜂槽 CompoundTag 追加到 merged 的 bee_slots 列表，
	 * 达到目标容量上限后停止追加，超出部分记录 WARN。
	 * <p>
	 * 向后兼容：输入 NBT 无 bee_slots 字段时跳过该输入。
	 *
	 * @param merged          合并目标 NBT（首输入 NBT 作为 base，已包含其 bee_slots）
	 * @param inputs          所有输入 NBT 列表
	 * @param targetCapacity  目标机器的蜜蜂槽容量上限
	 */
	private static void mergeBeeSlots(CompoundTag merged, List<CompoundTag> inputs, int targetCapacity) {
		// 从 base 中取出已有蜜蜂槽（首输入的蜜蜂）
		ListTag mergedBeeSlots = merged.contains(NBT_KEY_BEE_SLOTS, Tag.TAG_LIST)
				? merged.getList(NBT_KEY_BEE_SLOTS, Tag.TAG_COMPOUND)
				: new ListTag();
		// 容量校验：base 蜜蜂数已超目标容量时截断
		int dropped = 0;
		while (mergedBeeSlots.size() > targetCapacity) {
			mergedBeeSlots.remove(mergedBeeSlots.size() - 1);
			dropped++;
		}

		// 追加后续输入的蜜蜂槽（跳过首输入 NBT,已作为 base）
		for (int i = 1; i < inputs.size(); i++) {
			CompoundTag inputNbt = inputs.get(i);
			if (!inputNbt.contains(NBT_KEY_BEE_SLOTS, Tag.TAG_LIST)) continue;
			ListTag inputBeeSlots = inputNbt.getList(NBT_KEY_BEE_SLOTS, Tag.TAG_COMPOUND);
			for (int j = 0; j < inputBeeSlots.size(); j++) {
				if (mergedBeeSlots.size() >= targetCapacity) {
					// 剩余蜜蜂全部丢弃
					dropped += (inputBeeSlots.size() - j);
					break;
				}
				mergedBeeSlots.add(inputBeeSlots.getCompound(j).copy());
			}
		}

		// 超出容量警告
		if (dropped > 0) {
			DevLog.warn(DEV_FEATURE,
					"合成升级合并蜜蜂槽超出目标容量,丢弃 {} 只蜜蜂 (目标容量={})",
					dropped, targetCapacity);
		}

		// 写回合并后的蜜蜂槽（空列表也写入,确保字段存在）
		merged.put(NBT_KEY_BEE_SLOTS, mergedBeeSlots);
	}

	/**
	 * 合并 PB 升级数量 — 累加所有输入的数量，按类型上限限制
	 * <br/>
	 * 原理：遍历所有输入 NBT 的 PB 升级数量 CompoundTag（key=类型id,value=数量），
	 * 按类型累加数量，超过 {@link PbUpgradeType#getMaxCount()} 时截断并 WARN。
	 * <p>
	 * 向后兼容：输入 NBT 无 PB 升级字段时跳过该输入。
	 *
	 * @param merged       合并目标 NBT
	 * @param inputs       所有输入 NBT 列表
	 * @param pbUpgradeKey PB 升级数量的 NBT key（蜂箱 / 离心机不同）
	 */
	private static void mergePbUpgradeCounts(CompoundTag merged, List<CompoundTag> inputs, String pbUpgradeKey) {
		CompoundTag mergedCounts = merged.contains(pbUpgradeKey, Tag.TAG_COMPOUND)
				? merged.getCompound(pbUpgradeKey)
				: new CompoundTag();
		boolean overflowed = false;

		// 累加所有输入的数量（首输入已在 mergedCounts 中作为 base）
		for (int i = 1; i < inputs.size(); i++) {
			CompoundTag inputNbt = inputs.get(i);
			if (!inputNbt.contains(pbUpgradeKey, Tag.TAG_COMPOUND)) continue;
			CompoundTag inputCounts = inputNbt.getCompound(pbUpgradeKey);
			for (String typeId : inputCounts.getAllKeys()) {
				int inputValue = inputCounts.getInt(typeId);
				if (inputValue <= 0) continue;
				int current = mergedCounts.getInt(typeId);
				mergedCounts.putInt(typeId, current + inputValue);
			}
		}

		// 按类型上限截断（PB 升级类型限制由 PbUpgradeType.getMaxCount 决定）
		for (String typeId : mergedCounts.getAllKeys()) {
			PbUpgradeType type = PbUpgradeType.byId(typeId);
			if (type == null) {
				// 未知类型 ID（可能来自未来版本）,保留原值不截断
				continue;
			}
			int current = mergedCounts.getInt(typeId);
			int max = type.getMaxCount();
			if (current > max) {
				mergedCounts.putInt(typeId, max);
				overflowed = true;
				DevLog.warn(DEV_FEATURE,
						"合成升级合并 PB 升级 {} 超出上限,截断为 {} (输入合计={})",
						typeId, max, current);
			}
		}

		if (overflowed) {
			ProductiveBeesGenesis.LOGGER.debug(
					"[CraftingUpgrade] PB 升级合并发生超限截断,详见 DEV 日志");
		}

		// 写回合并后的 PB 升级数量（空 CompoundTag 也写入,确保字段存在）
		merged.put(pbUpgradeKey, mergedCounts);
	}

	/**
	 * 解析蜂箱输出方块的蜜蜂槽容量上限
	 * <br/>
	 * 通过 {@link Attribute#getTier(Block, Class)} 识别工厂等级：
	 * <ul>
	 *   <li>原版工厂（BASIC/ADVANCED/ELITE/ULTIMATE）：{@link FactoryApiaryConfig#forTier}</li>
	 *   <li>ME 工厂（ABSOLUTE/SUPREME/COSMIC/INFINITE）：通过 {@link MEContainerSlotHelper}（软依赖守卫）</li>
	 *   <li>EME 工厂：通过 {@link EMEContainerSlotHelper}（软依赖守卫）</li>
	 *   <li>基础蜂箱（非工厂）：返回 {@link #BASIC_APIARY_BEE_SLOT_COUNT}</li>
	 * </ul>
	 * <p>
	 * 性能：本方法仅在多输入合并路径调用,非热路径,无需缓存。
	 *
	 * @param outputBlock 输出方块
	 * @return 蜜蜂槽容量上限
	 */
	private static int resolveApiaryBeeSlotCapacity(Block outputBlock) {
		// 1. 原版工厂等级
		FactoryTier tier = Attribute.getTier(outputBlock, FactoryTier.class);
		if (tier != null) {
			return FactoryApiaryConfig.forTier(tier).beeSlotCount;
		}
		// 2. ME 工厂等级（软依赖守卫,避免 NoClassDefFoundError）
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			int beeSlotCount = MEContainerSlotHelper.getBeeSlotCount(outputBlock);
			if (beeSlotCount > 0) {
				return beeSlotCount;
			}
		}
		// 3. EME 工厂等级（软依赖守卫）
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			int beeSlotCount = EMEContainerSlotHelper.getBeeSlotCount(outputBlock);
			if (beeSlotCount > 0) {
				return beeSlotCount;
			}
		}
		// 4. 基础蜂箱（非工厂）
		return BASIC_APIARY_BEE_SLOT_COUNT;
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
