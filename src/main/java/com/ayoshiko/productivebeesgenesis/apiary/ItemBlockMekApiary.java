package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.ItemStackBlockEntityDataHelper;
import com.ayoshiko.productivebeesgenesis.util.NumberFormatter;
import mekanism.api.Upgrade;
import mekanism.common.attachments.component.UpgradeAware;
import mekanism.common.attachments.containers.energy.EnergyContainersBuilder;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeUpgradeSupport;
import mekanism.common.item.block.ItemBlockTooltip;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.util.StorageUtils;
import mekanism.common.util.text.UpgradeDisplay;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
	 * 通用机械蜂箱 BlockItem — 五个等级（基础/工厂/EM/ME/EME）共用基类
	 * <br/>
	 * 继承 ItemBlockTooltip，复用 Mekanism 原版 tooltip 系统（统计信息 + Shift 详情 + Shift+N 描述）。
	 * <p>
	 * 等级结构与一致性保证：
	 * <ul>
	 *   <li>基础蜂箱：直接使用本类</li>
	 *   <li>工厂蜂箱（Basic/Advanced/Elite/Ultimate）：使用 {@link ItemBlockMekApiaryFactory}（继承本类）</li>
	 *   <li>EM 蜂箱（Overclocked/Quantum/Dense/Multiversal/Creative）：使用 {@link ItemBlockMekApiaryFactory}</li>
	 *   <li>ME 蜂箱（Absolute/Supreme/Cosmic/Infinite）：使用 {@link ItemBlockMekApiaryFactory}</li>
	 *   <li>EME 蜂箱（Absolute Overclocked/Supreme Quantum/Cosmic Dense/Infinite Multiversal）：使用 {@link
	 * ItemBlockMekApiaryFactory}</li>
	 * </ul>
	 * 工厂版子类不重写任何 tooltip 方法，五个等级的 tooltip 行为完全一致。
	 * <p>
	 * 设计原则：单一职责，本类仅负责物品 tooltip 行为；颜色处理与 SORTING 组件由子类负责。
	 * DataComponents（EJECTOR/SIDE_CONFIG/SECURITY/REDSTONE/UPGRADES）由 ModItems.machineItemProperties() 统一添加。
	 * <p>
	 * Bug 5 修复：完整实现 tooltip 基类通用信息 + 蜂箱专属 Shift 详情：
	 * <ul>
	 *   <li>addStats：默认显示蜜蜂数量、PB升级总数、蜂箱类型（无需按 Shift）</li>
	 *   <li>addDetails：Shift 显示 MEK 标准详情 + 蜂箱专属详情（蜜蜂种类列表、各 PB 升级类型数量、选中槽位、工作中蜜蜂数量）</li>
	 * </ul>
	 * Bug 7 修复：重写 addTypeDetails 防止扳手拆卸后 DataComponents 缺失导致 NPE 崩溃。
	 * <p>
	 * 蜜蜂种类详情：Shift 时按 bee_type 分组统计内部蜜蜂数量，通过 BeeNbtHelper 解析类型键、
	 * BeeInfoHelper 获取本地化名称，按数量降序显示"蜜蜂种类名称: 数量"。
	 */
public class ItemBlockMekApiary extends ItemBlockTooltip<MekApiaryBlock<?, ?>> {

	public ItemBlockMekApiary(MekApiaryBlock<?, ?> block, Item.Properties properties) {
		// hasDescription=true，启用Shift+N描述显示
		super(block, true, properties);
	}

	/**
	 * 重写类型详情 — 蜂箱不显示MEK配方类型
	 * <br/>
	 * 原理：基类ItemBlockTooltip.addTypeDetails仅显示储能（exposesEnergyCapOrTooltips时）。
	 * 蜂箱的TileEntityMekApiary.getRecipeType()返回SMELTING仅作占位（避免NPE），
	 * 蜂箱实际产出由BeeProduceProcessor处理，不走MEK配方体系。
	 * 此处不调用super，直接复制基类的储能显示逻辑并添加try-catch防御，
	 * 避免扳手拆卸后DataComponents不完整时StorageUtils.addStoredEnergy抛出NPE。
	 */
	@Override
	protected void addTypeDetails(@NotNull ItemStack stack, @NotNull Item.TooltipContext context,
			@NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
		try {
			if (exposesEnergyCapOrTooltips()) {
				StorageUtils.addStoredEnergy(stack, tooltip, false);
			}
		} catch (Exception e) {
			// 防御：扳手拆卸后能量容器DataComponents可能缺失，跳过储能显示
			ProductiveBeesGenesis.LOGGER.warn("蜂箱tooltip储能显示异常（可能为拆卸后组件缺失）", e);
		}
	}

	/**
	 * Bug 5：默认tooltip统计信息 — 显示蜂箱核心数量统计
	 * <br/>
	 * 原理：appendHoverText在未按Shift时调用addStats，
	 * 此处从BLOCK_ENTITY_DATA读取扳手拆卸时保存的蜂箱自定义数据，
	 * 统计蜜蜂数量、PB升级总数，让玩家无需按Shift即可了解蜂箱内容。
	 * <p>
	 * 数据来源：getDrops()写入BLOCK_ENTITY_DATA的saveCustomDataForItem()NBT。
	 */
	@Override
	protected void addStats(@NotNull ItemStack stack, @NotNull Item.TooltipContext context,
							@NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
		super.addStats(stack, context, tooltip, flag);
		CompoundTag customNbt = ItemStackBlockEntityDataHelper.readCustomBlockEntityData(stack);
		if (customNbt == null) return;

		int beeCount = countBeesFromNbt(customNbt);
		int pbUpgradeTotal = countPbUpgradesFromNbt(customNbt);

		// 蜜蜂数量（大数字使用 K/M/G/T 格式化）
		tooltip.add(Component.translatable("tooltip.productivebeesgenesis.bee_count", NumberFormatter.format(beeCount))
				.withStyle(ChatFormatting.GRAY));
		// PB升级总数
		if (pbUpgradeTotal > 0) {
			tooltip.add(Component.translatable("tooltip.productivebeesgenesis.pb_upgrade_count",
					NumberFormatter.format(pbUpgradeTotal))
					.withStyle(ChatFormatting.GRAY));
		}
		// 蜂箱类型
		tooltip.add(Component.translatable("tooltip.productivebeesgenesis.apiary_type",
						getBlock().getName())
				.withStyle(ChatFormatting.GRAY));
	}

	/**
	 * Bug 5：Shift详情 — 完整MEK标准详情 + 蜂箱专属Shift详情
	 * <br/>
	 * 原理：appendHoverText在按住detailsKey（Shift）时调用addDetails。
	 * 基类addDetails依次读取Security/Energy/Fluid/Inventory/Upgrades组件。
	 * 此处包装super调用防止DataComponents缺失NPE，并在super调用之后追加蜂箱专属信息：
	 * <ul>
	 *   <li>各PB升级类型的具体安装数量（按类型分列）</li>
	 *   <li>选中的蜜蜂槽位（若已选择）</li>
	 * </ul>
	 * <p>
	 * 修复 v14：super 抛异常时独立渲染 UPGRADES tooltip（防重复：super 成功时不重复调用）。
	 * super.addDetails 成功时已通过 MEK 原版逻辑渲染 UPGRADES，无需重复；
	 * super 失败（被 try-catch 吞掉）时调用 addMekUpgradesDetails 独立渲染升级信息。
	 */
	@Override
	protected void addDetails(@NotNull ItemStack stack, @NotNull Item.TooltipContext context,
			@NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
		boolean superSucceeded = false;
		try {
			super.addDetails(stack, context, tooltip, flag);
			superSucceeded = true;
		} catch (Exception e) {
			// 防御：拆卸后组件不完整时防止客户端tooltip崩溃
			ProductiveBeesGenesis.LOGGER.warn("显示蜂箱Shift详情时异常（可能为拆卸后组件缺失）", e);
		}
		// 修复 v14：super 抛异常时独立渲染 UPGRADES tooltip（防重复：super 成功时不重复渲染）
		if (!superSucceeded) {
			addMekUpgradesDetails(stack, tooltip);
		}
		// 追加蜂箱专属Shift详情
		addApiarySpecificDetails(stack, tooltip);
	}

	/**
	 * 独立渲染 MEK 升级 tooltip — 模仿 MEK 原版 ItemBlockTooltip.addDetails 中的升级渲染逻辑
	 * <br/>
	 * 修复 v14：当 super.addDetails() 抛异常被 try-catch 吞掉时，UPGRADES tooltip 仍能独立渲染。
	 * 渲染逻辑与 MEK 原版 {@link ItemBlockTooltip#addDetails} 完全一致：
	 * <ul>
	 *   <li>检查 {@link AttributeUpgradeSupport} 属性</li>
	 *   <li>读取 {@link MekanismDataComponents#UPGRADES} 获取 {@link UpgradeAware}</li>
	 *   <li>遍历 {@code upgradeAware.upgrades().entrySet()}，使用
	 *       {@link UpgradeDisplay#of(Upgrade, int)} 渲染每个升级类型和数量</li>
	 * </ul>
	 * 格式与 MEK 原版完全一致（使用相同的 UpgradeDisplay），保持风格统一。
	 *
	 * @param stack    物品栈
	 * @param tooltip  tooltip 列表
	 */
	private void addMekUpgradesDetails(@NotNull ItemStack stack, @NotNull List<Component> tooltip) {
		try {
			if (!Attribute.has(getBlock(), AttributeUpgradeSupport.class)) return;
			UpgradeAware upgradeAware = stack.get(MekanismDataComponents.UPGRADES);
			if (upgradeAware == null) return;
			for (Map.Entry<Upgrade, Integer> entry : upgradeAware.upgrades().entrySet()) {
				tooltip.add(UpgradeDisplay.of(entry.getKey(), entry.getValue()).getTextComponent());
			}
		} catch (Exception e) {
			// 防御：UPGRADES 组件缺失或格式异常时跳过，不影响其他 tooltip 渲染
			ProductiveBeesGenesis.LOGGER.warn("渲染蜂箱 MEK 升级 tooltip 时异常", e);
		}
	}

	/**
	 * 蜂箱专属Shift详情 — 显示蜜蜂种类列表、各PB升级类型安装数量、选中蜜蜂槽位、工作状态
	 * <br/>
	 * 原理：从BLOCK_ENTITY_DATA读取蜂箱自定义NBT，依次展示：
	 * <ol>
	 *   <li>蜜蜂种类列表：按 bee_type 分组统计，通过 BeeNbtHelper 解析类型键、BeeInfoHelper 获取本地化名称</li>
	 *   <li>各PB升级类型安装数量：遍历PB升级数量映射</li>
	 *   <li>选中蜜蜂槽位</li>
	 *   <li>工作状态：工作中蜜蜂数量</li>
	 * </ol>
	 */
	private void addApiarySpecificDetails(@NotNull ItemStack stack, @NotNull List<Component> tooltip) {
		CompoundTag customNbt = ItemStackBlockEntityDataHelper.readCustomBlockEntityData(stack);
		if (customNbt == null) return;

		// 蜜蜂种类列表（按数量降序）— 显示内部各蜜蜂种类及对应数量
		addBeeTypeDetails(customNbt, tooltip);

		// 各PB升级类型安装数量
		if (customNbt.contains(ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS, Tag.TAG_COMPOUND)) {
			CompoundTag countsTag = customNbt.getCompound(ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS);
			if (!countsTag.getAllKeys().isEmpty()) {
				tooltip.add(Component.translatable("tooltip.productivebeesgenesis.pb_upgrade_detail_header")
						.withStyle(ChatFormatting.LIGHT_PURPLE));
				for (String typeId : countsTag.getAllKeys()) {
					PbUpgradeType type = PbUpgradeType.byId(typeId);
					if (type != null) {
						int count = countsTag.getInt(typeId);
						tooltip.add(Component.literal(" ")
								.append(Component.translatable(type.getNameKey()))
								.append(Component.literal(": " + NumberFormatter.format(count))
										.withStyle(ChatFormatting.GRAY)));
					}
				}
			}
		}

		// 选中蜜蜂槽位 — 引用 ApiaryNbtSerializer.NBT_KEY_SELECTED_BEE 保持 NBT key 单一来源（DRY）
		if (customNbt.contains(ApiaryNbtSerializer.NBT_KEY_SELECTED_BEE, Tag.TAG_INT)) {
			int selected = customNbt.getInt(ApiaryNbtSerializer.NBT_KEY_SELECTED_BEE);
			if (selected >= 0) {
				tooltip.add(Component.translatable("tooltip.productivebeesgenesis.selected_bee_slot", selected)
						.withStyle(ChatFormatting.AQUA));
			}
		}

		// 蜂箱工作状态 — 显示工作中蜜蜂数量（仅当有活跃蜜蜂时）
		int workingCount = countWorkingBees(customNbt);
		if (workingCount > 0) {
			tooltip.add(Component.translatable("tooltip.productivebeesgenesis.working_bees",
					NumberFormatter.format(workingCount))
					.withStyle(ChatFormatting.GREEN));
		}
	}

	/**
	 * 统计NBT中的蜜蜂数量
	 * <br/>
	 * NBT结构：ListTag "productivebeesgenesis_apiary_bee_slots"，每个元素为一个非空蜜蜂槽。
	 * 直接返回list.size()即为非空蜜蜂数量。
	 */
	private static int countBeesFromNbt(@NotNull CompoundTag nbt) {
		if (!nbt.contains(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_LIST)) return 0;
		ListTag list = nbt.getList(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_COMPOUND);
		return list.size();
	}

	/**
	 * 统计NBT中的PB升级总数
	 * <br/>
	 * NBT结构：CompoundTag "productivebeesgenesis_pb_upgrade_counts"，key=类型id，value=数量。
	 * 遍历所有value累加得到总数。
	 */
	private static int countPbUpgradesFromNbt(@NotNull CompoundTag nbt) {
		if (!nbt.contains(ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS, Tag.TAG_COMPOUND)) return 0;
		CompoundTag countsTag = nbt.getCompound(ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS);
		int total = 0;
		for (String typeId : countsTag.getAllKeys()) {
			total += countsTag.getInt(typeId);
		}
		return total;
	}

	/**
	 * 蜜蜂种类详情 — 显示内部各蜜蜂种类及对应数量，按数量降序排列
	 * <br/>
	 * 原理：从蜜蜂槽 ListTag 解析每个槽位的 entity_data，
	 * 使用 {@link BeeNbtHelper#resolveBeeTypeKey} 统一提取蜜蜂类型键（兼容 PB Occupant 和蜂笼格式），
	 * 通过 {@link BeeInfoHelper#getBeeDisplayName} 获取本地化名称。
	 * 无蜜蜂时显示"无蜜蜂"提示。
	 *
	 * @param nbt      蜂箱自定义 NBT
	 * @param tooltip  tooltip 列表
	 */
	private static void addBeeTypeDetails(@NotNull CompoundTag nbt, @NotNull List<Component> tooltip) {
		List<Map.Entry<ResourceLocation, Integer>> beeTypes = collectBeeTypeCounts(nbt);
		if (beeTypes.isEmpty()) {
			// 无蜜蜂时显示提示
			tooltip.add(Component.translatable("tooltip.productivebeesgenesis.no_bees")
					.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
			return;
		}
		// 蜜蜂种类标题
		tooltip.add(Component.translatable("tooltip.productivebeesgenesis.bee_types_header")
				.withStyle(ChatFormatting.LIGHT_PURPLE));
		// 各蜜蜂种类及数量（按数量降序，大数字格式化）
		for (Map.Entry<ResourceLocation, Integer> entry : beeTypes) {
			Component beeName = BeeInfoHelper.getBeeDisplayName(entry.getKey());
			tooltip.add(Component.literal(" ")
					.append(Component.translatable("tooltip.productivebeesgenesis.bee_type_entry",
							beeName, NumberFormatter.format(entry.getValue())))
					.withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * 统计 NBT 中各蜜蜂类型的数量（按数量降序排列）
	 * <br/>
	 * 原理：遍历蜜蜂槽 ListTag，从每个槽位的 entity_data 中解析蜜蜂类型键，
	 * 使用 {@link BeeNbtHelper#resolveBeeTypeKey} 统一解析（兼容 PB Occupant 和蜂笼格式）。
	 * 结果按数量降序排列，便于玩家快速识别主要蜜蜂种类。
	 *
	 * @param nbt 蜂箱自定义 NBT
	 * @return 蜜蜂类型->数量 的有序列表（按数量降序），无蜜蜂返回空列表
	 */
	@NotNull
	private static List<Map.Entry<ResourceLocation, Integer>> collectBeeTypeCounts(@NotNull CompoundTag nbt) {
		if (!nbt.contains(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_LIST)) return List.of();
		ListTag list = nbt.getList(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_COMPOUND);
		if (list.isEmpty()) return List.of();

		// 按蜜蜂类型分组统计
		Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
		for (int i = 0; i < list.size(); i++) {
			CompoundTag slotNbt = list.getCompound(i);
			if (!slotNbt.contains("entity_data", Tag.TAG_COMPOUND)) continue;
			CompoundTag entityData = slotNbt.getCompound("entity_data");
			ResourceLocation beeType = BeeNbtHelper.resolveBeeTypeKey(entityData);
			if (beeType == null) continue;
			counts.merge(beeType, 1, Integer::sum);
		}

		// 按数量降序排列
		List<Map.Entry<ResourceLocation, Integer>> sorted = new ArrayList<>(counts.entrySet());
		sorted.sort(Comparator.comparingInt(Map.Entry<ResourceLocation, Integer>::getValue).reversed());
		return sorted;
	}

	/**
	 * 统计 NBT 中处于工作状态的蜜蜂数量
	 * <br/>
	 * 原理：遍历蜜蜂槽 ListTag，检查 state 字段是否为 WORKING。
	 * state 由 {@link ApiarySlotManager#saveBeeSlots} 写入，值为 {@link BeeState#name()}。
	 *
	 * @param nbt 蜂箱自定义 NBT
	 * @return 工作中的蜜蜂数量
	 */
	private static int countWorkingBees(@NotNull CompoundTag nbt) {
		if (!nbt.contains(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_LIST)) return 0;
		ListTag list = nbt.getList(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_COMPOUND);
		int working = 0;
		for (int i = 0; i < list.size(); i++) {
			CompoundTag slotNbt = list.getCompound(i);
			if ("WORKING".equals(slotNbt.getString("state"))) {
				working++;
			}
		}
		return working;
	}

	/**
	 * Task 2：公开父类protected方法，供 {@link MekApiaryContainerRegistrar} 注册能量容器创建器
	 * <br/>
	 * 原理：与 {@link com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge#buildDefaultEnergyContainers} 一致，
	 * 委托给父类 {@link ItemBlockTooltip#addDefaultEnergyContainers}，
	 * 根据 {@link mekanism.common.block.attribute.AttributeEnergy} 配置创建能量容器。
	 * 蜂箱支持 ENERGY 升级，走升级动态容量分支。
	 */
	public EnergyContainersBuilder buildDefaultEnergyContainers(EnergyContainersBuilder builder) {
		return addDefaultEnergyContainers(builder);
	}
}
