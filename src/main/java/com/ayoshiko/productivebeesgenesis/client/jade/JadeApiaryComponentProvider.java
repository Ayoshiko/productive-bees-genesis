package com.ayoshiko.productivebeesgenesis.client.jade;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.BeeSlot;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
	 * Jade 通用机械蜂箱状态显示组件
	 * <br/>
	 * 显示蜂箱运行关键信息：
	 * <ul>
	 *   <li>蜜蜂：活跃蜜蜂数 / 总蜜蜂槽位</li>
	 *   <li>状态：各状态蜜蜂数量汇总（工作中 / 等待能量 / 等待花朵 / 等待输出）</li>
	 *   <li>进度：所有非空蜜蜂槽的平均生产进度百分比</li>
	 * </ul>
	 * <p>
	 * <b>注意</b>：能量（FE）信息由 Mekanism 父类 {@code TileEntityElectricMachine} 的 Jade 插件
	 * 自动提供，此处不重复显示。
	 * <p>
	 * <b>数据同步</b>：服务端 {@link #appendServerData} 将汇总数据写入 NBT，
	 * 客户端 {@link #appendTooltip} 读取并渲染。仅传递数量与状态汇总，
	 * 不传递蜜蜂 NBT 数据，避免泄露未加载蜜蜂的类型/名称等信息。
	 * <p>
	 * <b>显示条件</b>：仅当方块实体为 {@link TileEntityMekApiary} 时显示。
	 * 复用 {@link JadeAe2StatusProvider} 的服务端/客户端双端单例模式。
	 */
public final class JadeApiaryComponentProvider
		implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

	/** 无状态单例 — 服务端和客户端共用同一实例 */
	static final JadeApiaryComponentProvider INSTANCE = new JadeApiaryComponentProvider();

	/** 插件唯一 ID — 用于 Jade 配置页面切换开关 */
	static final ResourceLocation UID =
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "apiary_status");

	// ===== NBT 键（带模组前缀避免冲突） =====
	private static final String NBT_BEE_COUNT = "productivebeesgenesis_apiary_bee_count";
	private static final String NBT_BEE_SLOTS = "productivebeesgenesis_apiary_bee_slots";
	private static final String NBT_WORKING = "productivebeesgenesis_apiary_working";
	private static final String NBT_WAITING_ENERGY = "productivebeesgenesis_apiary_waiting_energy";
	private static final String NBT_WAITING_FLOWER = "productivebeesgenesis_apiary_waiting_flower";
	private static final String NBT_WAITING_OUTPUT = "productivebeesgenesis_apiary_waiting_output";
	private static final String NBT_PROGRESS = "productivebeesgenesis_apiary_progress";

	// ===== 翻译键 =====
	private static final String KEY_BEES = "productivebeesgenesis.jade.apiary.bees";
	private static final String KEY_STATUS = "productivebeesgenesis.jade.apiary.status";
	private static final String KEY_PROGRESS = "productivebeesgenesis.jade.apiary.progress";
	private static final String KEY_STATE_WORKING = "productivebeesgenesis.jade.apiary.state.working";
	private static final String KEY_STATE_WAITING_ENERGY = "productivebeesgenesis.jade.apiary.state.waiting_energy";
	private static final String KEY_STATE_WAITING_FLOWER = "productivebeesgenesis.jade.apiary.state.waiting_flower";
	private static final String KEY_STATE_WAITING_OUTPUT = "productivebeesgenesis.jade.apiary.state.waiting_output";
	/** 状态行与进度合并后的前缀翻译键，用于缩短Jade tooltip总高度 */
	private static final String KEY_STATUS_WITH_PROGRESS = "productivebeesgenesis.jade.apiary.status_with_progress";

	@Override
	public ResourceLocation getUid() {
		return UID;
	}

	/**
	 * 设置Jade tooltip provider优先级。
	 * <br/>
	 * 返回较高优先级（先于Mekanism默认的JadeTooltipRenderer执行），使我们的蜜蜂信息
	 * 排在MEK能量条/流体条上方，能量条因此位于tooltip更下方，避免其顶部文字被
	 * tooltip上边界裁剪。
	 */
	@Override
	public int getDefaultPriority() {
		return 100;
	}

	// ===== 服务端数据同步 =====

	@Override
	public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
		BlockEntity be = accessor.getBlockEntity();
		if (!(be instanceof TileEntityMekApiary apiary)) {
			return;
		}

		// 蜜蜂槽统计 — 仅统计数量与状态，不读取 beeData 内容
		BeeSlot[] slots = apiary.getBeeSlots();
		int beeCount = 0;
		int working = 0;
		int waitingEnergy = 0;
		int waitingFlower = 0;
		int waitingOutput = 0;
		float progressSum = 0.0f;

		for (BeeSlot slot : slots) {
			if (slot.isEmpty()) continue;
			beeCount++;
			progressSum += slot.getProgress();
			switch (slot.getState()) {
				case WORKING -> working++;
				case WAITING_ENERGY -> waitingEnergy++;
				case WAITING_FLOWER -> waitingFlower++;
				case WAITING_OUTPUT -> waitingOutput++;
				default -> { /* IDLE 不计入等待状态 */ }
			}
		}

		tag.putInt(NBT_BEE_COUNT, beeCount);
		tag.putInt(NBT_BEE_SLOTS, apiary.getBeeSlotCount());
		tag.putInt(NBT_WORKING, working);
		tag.putInt(NBT_WAITING_ENERGY, waitingEnergy);
		tag.putInt(NBT_WAITING_FLOWER, waitingFlower);
		tag.putInt(NBT_WAITING_OUTPUT, waitingOutput);
		// 平均进度（无蜜蜂时为 0）
		tag.putFloat(NBT_PROGRESS, beeCount > 0 ? progressSum / beeCount : 0.0f);
	}

	// ===== 客户端显示 =====

	@Override
	public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
		CompoundTag data = accessor.getServerData();
		// 无蜜蜂槽数据则不显示（非蜂箱方块实体）
		if (!data.contains(NBT_BEE_SLOTS)) return;

		int beeCount = data.getInt(NBT_BEE_COUNT);
		int beeSlots = data.getInt(NBT_BEE_SLOTS);
		int working = data.getInt(NBT_WORKING);
		int waitingEnergy = data.getInt(NBT_WAITING_ENERGY);
		int waitingFlower = data.getInt(NBT_WAITING_FLOWER);
		int waitingOutput = data.getInt(NBT_WAITING_OUTPUT);
		float progress = data.getFloat(NBT_PROGRESS);

		// Bug 1：在线/离线状态由 JadeAe2StatusProvider 提供（与离心机/AE2原版一致），此处不重复显示

		// 蜜蜂行
		tooltip.add(Component.translatable(KEY_BEES, beeCount, beeSlots)
				.withStyle(ChatFormatting.GRAY));

		// 状态行 + 进度行 — 仅有蜜蜂时显示
		// 将状态和进度合并到同一行，减少Jade tooltip总高度。
		if (beeCount > 0) {
			int progressPercent = Math.round(progress * 100.0f);
			tooltip.add(buildStatusWithProgressLine(working, waitingEnergy, waitingFlower, waitingOutput, progressPercent));
		}
		// 无蜜蜂时不添加任何内容，避免产生可见空行
	}

	/**
	 * 构建状态与进度合并行 — 各状态蜜蜂数量用对应颜色显示，末尾追加进度百分比。
	 * <br/>
	 * 将原本分开的两行合并为一行，减少Jade tooltip总高度，缓解MEK能量条顶部文字
	 * 被tooltip边界裁剪的问题。颜色与 {@link BeeState#getColor()} 的 GUI 状态灯一致。
	 */
	private static Component buildStatusWithProgressLine(int working, int waitingEnergy,
			int waitingFlower, int waitingOutput,
			int progressPercent) {
		MutableComponent line = Component.translatable(KEY_STATUS_WITH_PROGRESS).withStyle(ChatFormatting.GRAY);
		line.append(Component.literal(" "));
		boolean first = true;
		if (working > 0) {
			line.append(Component.translatable(KEY_STATE_WORKING, working)
					.withStyle(ChatFormatting.GREEN));
			first = false;
		}
		if (waitingEnergy > 0) {
			if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			line.append(Component.translatable(KEY_STATE_WAITING_ENERGY, waitingEnergy)
					.withStyle(ChatFormatting.RED));
			first = false;
		}
		if (waitingFlower > 0) {
			if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			line.append(Component.translatable(KEY_STATE_WAITING_FLOWER, waitingFlower)
					.withStyle(ChatFormatting.GOLD));
			first = false;
		}
		if (waitingOutput > 0) {
			if (!first) line.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
			line.append(Component.translatable(KEY_STATE_WAITING_OUTPUT, waitingOutput)
					.withStyle(ChatFormatting.YELLOW));
		}
		line.append(Component.literal(" — ").withStyle(ChatFormatting.GRAY));
		line.append(Component.translatable(KEY_PROGRESS, progressPercent).withStyle(ChatFormatting.GRAY));
		return line;
	}
}
