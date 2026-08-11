package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.api.tier.BaseTier;
import mekanism.api.tier.ITier;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeTier;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.tier.FactoryTier;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.upgrade.IUpgradeData;
import mekanism.common.upgrade.MachineUpgradeData;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
	 * 工厂版通用机械蜂箱方块实体
	 * <br/>
	 * 继承 {@link TileEntityMekApiary}，实现 {@link ITier} 接口获取工厂等级。
	 * <p>
	 * 关键设计决策：蜂箱工厂不继承 {@code TileEntityFactory}，不走 MEK CachedRecipe 管线。
	 * 蜜蜂生产逻辑完全复用父类 {@link TileEntityMekApiary} 的 {@link ApiaryTickHandler}。
	 * 工厂等级仅通过 {@link AttributeTier} 区分（LED 颜色 + 物品名称颜色）。
	 * <p>
	 * 模板方法模式：通过重写 {@link #createSlotManager()} 和 {@link #createFeederSlotManager()}
	 * 返回工厂版参数的槽位管理器，父类核心逻辑无需修改。
	 * <p>
	 * 工厂版参数表（spec.md 表 2.1）：
	 * <ul>
	 *   <li>Basic: 5 蜂蜂(1×5)/9 输出(3×3)/9 喂食(3×3)/256,000 mB/128,000 FE</li>
	 *   <li>Advanced: 10 蜂蜂(2×5)/12 输出(3×4)/12 喂食(3×4)/512,000 mB/256,000 FE</li>
	 *   <li>Elite: 15 蜂蜂(3×5)/15 输出(3×5)/15 喂食(3×5)/768,000 mB/512,000 FE</li>
	 *   <li>Ultimate: 20 蜂蜂(4×5)/18 输出(3×6)/21 喂食(3×7)/1,024,000 mB/1,024,000 FE</li>
	 * </ul>
	 * <p>
	 * 排序功能：实现 sorting 字段和 {@link #toggleSorting()}/{@link #isSorting()} 方法，
	 * 供自定义 {@code GuiApiarySortingTab} 使用（不依赖 MEK 的 PacketGuiInteract.AUTO_SORT_BUTTON，
	 * 因为后者检查 {@code tile instanceof TileEntityFactory}）。
	 */
public class TileEntityMekApiaryFactory extends TileEntityMekApiary implements ITier {

	/** NBT key — sorting 状态 */
	private static final String NBT_KEY_SORTING = "productivebeesgenesis_apiary_sorting";

	/** 工厂等级 — 在 presetVariables() 中从 BlockType 读取 */
	protected FactoryTier tier;

	/** 排序开关 — 供 GuiApiarySortingTab 使用 */
	private boolean sorting;

	/** 排序需求标志 — 输出槽变化时设为 true，触发排序检查 */
	private boolean sortingNeeded = true;

	public TileEntityMekApiaryFactory(Holder<Block> blockProvider, net.minecraft.core.BlockPos pos, BlockState state) {
		super(blockProvider, pos, state);
	}

	/**
	 * 重写 presetVariables — 在 super() 构造期间从 BlockType 读取 FactoryTier
	 * <br/>
	 * 调用时机：在 {@code TileEntityMekanism} 构造函数第 282 行，
	 * 早于 {@code getInitialInventory()} 和 {@code getInitialFluidTanks()}。
	 * 因此 tier 字段在 {@link #createSlotManager()} 被调用时已初始化。
	 */
	@Override
	protected void presetVariables() {
		super.presetVariables();
		tier = Attribute.getTier(getBlockHolder(), FactoryTier.class);
	}

	/**
	 * 重写 createSlotManager — 返回工厂版参数的 ApiarySlotManager
	 * <br/>
	 * 根据工厂等级动态配置蜜蜂槽/输出槽数量和流体罐容量。
	 * 调用时机：super() 构造期间通过 slotManager() → createSlotManager() 调用，
	 * 此时 tier 已通过 presetVariables() 初始化。
	 */
	@Override
	protected ApiarySlotManager createSlotManager() {
		FactoryApiaryConfig config = FactoryApiaryConfig.forTier(tier);
		return new ApiarySlotManager(this,
				config.beeSlotCount, config.beeCols, config.beeRows,
				config.outputSlotCount, config.outputCols, config.outputRows,
				config.fluidTankCapacity);
	}

	/**
	 * 重写 createFeederSlotManager — 返回工厂版参数的 FeederSlotManager
	 * <br/>
	 * 喂食槽数量由 {@link FactoryApiaryConfig} 按 ceil(max(蜂蜂数,9)/3)*3 计算（严格矩形 3 列）。
	 */
	@Override
	protected FeederSlotManager createFeederSlotManager() {
		FactoryApiaryConfig config = FactoryApiaryConfig.forTier(tier);
		return new FeederSlotManager(config.feederSlotCount, config.feederCols, config.feederRows);
	}

	// ===== ITier 接口实现 =====

	@Override
	public BaseTier getBaseTier() {
		return tier == null ? BaseTier.BASIC : tier.getBaseTier();
	}

	/** 获取工厂等级 — 供 GUI/BlockItem 使用 */
	public FactoryTier getTier() {
		return tier;
	}

	// ===== GUI 布局参数 getter（供客户端 Screen 使用） =====
	// 原因：FactoryApiaryConfig 为包私有，client 包无法直接访问。
	// 通过 public getter 暴露必要参数，遵循迪米特法则。

	/** 获取蜜蜂列数 — GUI布局用（工厂版固定 5） */
	@Override
	public int getBeeCols() {
		return FactoryApiaryConfig.forTier(tier).beeCols;
	}

	/** 获取蜜蜂行数 — GUI布局用（Basic=1/Advanced=2/Elite=3/Ultimate=4） */
	@Override
	public int getBeeRows() {
		return FactoryApiaryConfig.forTier(tier).beeRows;
	}

	/** 获取输出列数 — GUI布局用（Basic=3/Advanced=4/Elite=5/Ultimate=6） */
	@Override
	public int getOutputCols() {
		return FactoryApiaryConfig.forTier(tier).outputCols;
	}

	/** 获取输出行数 — GUI布局用（固定 3） */
	@Override
	public int getOutputRows() {
		return FactoryApiaryConfig.forTier(tier).outputRows;
	}

	// ===== 排序功能（供 GuiApiarySortingTab 使用） =====

	/** 是否正在排序 */
	public boolean isSorting() {
		return sorting;
	}

	/** 切换排序开关 — 由自定义网络包调用 */
	public void toggleSorting() {
		sorting = !sorting;
		markForSave();
	}

	/** 是否需要排序 — 输出槽变化时设为 true */
	public boolean isSortingNeeded() {
		return sortingNeeded;
	}

	/** 标记需要排序 — 供 Container/Slot 监听器调用 */
	public void setSortingNeeded(boolean needed) {
		this.sortingNeeded = needed;
	}

	// ===== 容器数据同步 =====

	/**
	 * 重写容器数据同步 — 在父类基础上额外同步 sorting 状态
	 * <br/>
	 * 父类已同步蜜蜂 state/progress/hasNectar，工厂版额外同步 sorting 开关，
	 * 供客户端 {@link com.ayoshiko.productivebeesgenesis.apiary.client.GuiApiarySortingTab} 显示 OnOff 状态。
	 */
	@Override
	public void addContainerTrackers(MekanismContainer container) {
		super.addContainerTrackers(container);
		container.track(SyncableBoolean.create(this::isSorting, value -> sorting = value));
	}

	// ===== NBT 持久化 =====

	@Override
	public void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.saveAdditional(nbt, provider);
		nbt.putBoolean(NBT_KEY_SORTING, sorting);
	}

	@Override
	public void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		super.loadAdditional(nbt, provider);
		sorting = nbt.getBoolean(NBT_KEY_SORTING);
	}

	/**
	 * 重写扳手拆卸数据保存 — 在父类基础上额外保存 sorting 字段
	 * <br/>
	 * 父类 {@link TileEntityMekApiary#saveCustomDataForItem} 通过 nbtSerializer 保存蜂箱通用状态
	 * （蜜蜂/花朵/PB升级/流体罐/蜂笼输出槽等），但不包含工厂版特有的 sorting 开关。
	 * 此处在 super 调用后追加保存 sorting，确保扳手拆卸后重新放置时排序状态不丢失。
	 */
	@NotNull
	@Override
	public CompoundTag saveCustomDataForItem(@NotNull HolderLookup.Provider provider) {
		CompoundTag nbt = super.saveCustomDataForItem(provider);
		nbt.putBoolean(NBT_KEY_SORTING, sorting);
		return nbt;
	}

	// ===== 升级数据保存/恢复（ItemTierInstaller 升级时调用） =====

	/**
	 * 升级数据中的排序开关状态 — 重写模板方法，返回工厂版 sorting 字段
	 * <br/>
	 * 基类 {@link TileEntityMekApiary#getUpgradeData} 通过此方法将 sorting 状态
	 * 写入 {@link ApiaryUpgradeData}，工厂版无需重复实现 getUpgradeData 的序列化细节
	 * （蜜蜂槽/喂食槽/PB升级/流体罐/蜂笼输出槽/选中槽等均由基类 nbtSerializer 统一处理）。
	 */
	@Override
	protected boolean getSortingForUpgradeData() {
		return isSorting();
	}

	/**
	 * 升级数据恢复排序开关 — 重写模板方法,实际设置工厂版 sorting 字段
	 * <br/>
	 * 修复 MEDIUM-2: 由 {@link ApiaryNbtSerializer#applyUpgradeData} 在恢复流程末尾调用,
	 * 显式恢复 SORTING 字段。消除 ApiaryUpgradeData 分支中重复的 sorting 赋值,
	 * 集中管理 sorting 恢复逻辑,避免新增 apiary 等级时遗漏 sorting 恢复。
	 */
	@Override
	protected void setSortingFromUpgradeData(boolean sorting) {
		this.sorting = sorting;
	}

	/**
	 * 恢复升级数据 — 将旧方块的状态恢复到新方块
	 * <br/>
	 * 支持 {@link ApiaryUpgradeData}（蜂箱工厂间升级）和 {@link MachineUpgradeData}
	 * （从非工厂蜂箱升级到工厂蜂箱）两种数据类型。
	 * <p>
	 * ApiaryUpgradeData 分支委托基类 {@link TileEntityMekApiary#parseUpgradeData}，
	 * 由 {@link ApiaryNbtSerializer#applyUpgradeData} 统一恢复蜂箱通用状态
	 * （红石/能量/进度/槽位/组件/蜜蜂槽/喂食槽/PB升级/流体罐/蜂笼输出槽/选中槽/SORTING）。
	 * 修复 MEDIUM-2: sorting 恢复已下沉到 nbtSerializer 通过模板方法处理,本分支无需重复设置。
	 * <p>
	 * MachineUpgradeData 分支为从非工厂蜂箱升级到工厂版的兼容路径，
	 * 基类 nbtSerializer 不识别此类型（返回 false 后会走 MEK 默认恢复，丢失蜂笼/输出槽映射），
	 * 故工厂版自定义处理并额外设置 sorting（此分支不经过 nbtSerializer,需手动恢复 sorting）。
	 * <p>
	 * 槽位数量适配：升级到更高等级时仅恢复旧方块拥有的槽位数据，新增槽位保持空状态；
	 * 降级时多余数据由 loadBeeSlots/loadFeederSlots 的边界检查处理。
	 * 流体罐使用 insert 方法恢复，自动尊重新罐容量（降级时截断多余流体）。
	 *
	 * @param provider    注册表访问器
	 * @param upgradeData 升级数据
	 */
	@Override
	public void parseUpgradeData(HolderLookup.Provider provider, @NotNull IUpgradeData upgradeData) {
		if (upgradeData instanceof ApiaryUpgradeData data) {
			// 委托基类恢复蜂箱通用状态（nbtSerializer.applyUpgradeData 统一处理,含 SORTING 恢复）
			super.parseUpgradeData(provider, upgradeData);
		} else if (upgradeData instanceof MachineUpgradeData data) {
			// 兼容普通 MachineUpgradeData（从非工厂蜂箱 TileEntityMekApiary 升级到工厂版）
			redstone = data.redstone;
			setControlType(data.controlType);
			getEnergyContainer().setEnergy(data.energyContainer.getEnergy());
			sorting = data.sorting;
			getEnergySlot().deserializeNBT(provider, data.energySlot.serializeNBT(provider));
			if (data.progress.length > 0) {
				setOperatingTicks(data.progress[0]);
			}
			if (!data.inputSlots.isEmpty()) {
				getCageInSlot().deserializeNBT(provider, data.inputSlots.get(0).serializeNBT(provider));
			}
			List<BasicInventorySlot> currentOutputs = getOutputSlots();
			for (int i = 0; i < data.outputSlots.size() && i < currentOutputs.size(); i++) {
				// v9-P1 修复：与 ApiaryNbtSerializer.applyUpgradeData 保持一致，copy() 防止
				// 升级数据与原方块共享同一 ItemStack 引用，导致升级后双方状态互相干扰
				currentOutputs.get(i).setStack(data.outputSlots.get(i).getStack().copy());
			}
			for (ITileComponent component : getComponents()) {
				component.read(data.components, provider);
			}
		} else {
			super.parseUpgradeData(provider, upgradeData);
		}
	}
}
