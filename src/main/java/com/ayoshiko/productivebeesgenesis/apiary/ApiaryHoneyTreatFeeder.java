package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.ProductiveBees;
import cy.jdkdigital.productivebees.common.item.HoneyTreat;
import mekanism.api.Action;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;

/**
 * 机械蜂箱内基因小食喂食逻辑。
 * <p>
 * 蜂箱里的蜜蜂以 NBT 形式存放在 {@link BeeSlot} 中而非世界实体，因此喂食时创建一个
 * <b>不加入世界</b>的临时蜜蜂实体，施加基因后再把 NBT 回写槽位。
 * <p>
 * 提供两条路径：
 * <ul>
 *   <li>{@link #feedGeneTreat}：玩家手持小食点击蜜蜂格子。直接调用 PB 原版
 *       {@code HoneyTreat.interactLivingEntity}，完整复用原版概率、治疗、幼蜂成长与提示。</li>
 *   <li>{@link #feedGeneTreatFromSlot}：无玩家的自动化路径。<b>不能</b>调用原版
 *       {@code interactLivingEntity}——该方法内部无条件执行 {@code player.swing(hand)}，
 *       传 null 会直接 NPE 打断 tick；故按原版语义逐步复刻（见 {@link #applyTreatWithoutPlayer}）。</li>
 * </ul>
 * <p>
 * 两条路径共用同一套「创建临时实体 → 施加 → 回写 NBT」骨架（{@link #withTemporaryBee}），
 * 避免行为分叉。回写成功后才扣除物品，保证失败时不丢小食。
 * <p>
 * 线程安全：必须在服务端主线程调用。
 */
public final class ApiaryHoneyTreatFeeder {

	private ApiaryHoneyTreatFeeder() {
	}

	/**
	 * 对临时蜜蜂实体执行的操作。
	 *
	 * @param <T> 额外上下文类型
	 */
	private interface BeeTreatAction {
		/**
		 * @param level 服务端世界
		 * @param bee   已从槽位 NBT 加载、未加入世界的临时蜜蜂
		 * @return true 表示操作生效、应回写 NBT 并消耗小食
		 */
		boolean apply(ServerLevel level, Bee bee);
	}

	/**
	 * 给指定蜂箱槽位内的蜜蜂喂食光标上的基因小食（玩家路径）。
	 * <p>
	 * 小食先用单份副本执行原版逻辑，只有蜜蜂 NBT 成功回写后才扣除真实光标物品，
	 * 避免实体恢复失败造成物品丢失。
	 *
	 * @param apiary     目标机械蜂箱
	 * @param slotIndex  蜜蜂槽位索引
	 * @param player     执行操作的服务端玩家
	 * @param cursorItem 玩家容器光标上的物品
	 * @return 成功执行并回写蜜蜂数据时返回 {@code true}
	 */
	public static boolean feedGeneTreat(TileEntityMekApiary apiary, int slotIndex,
			ServerPlayer player, ItemStack cursorItem) {
		if (player == null || !isFeedableTreat(cursorItem)) return false;
		HoneyTreat honeyTreat = (HoneyTreat) cursorItem.getItem();

		boolean fed = withTemporaryBee(apiary, slotIndex, "apiary_honey_treat_failure",
				(level, bee) -> {
					ItemStack singleTreat = cursorItem.copyWithCount(1);
					InteractionResult result = honeyTreat.interactLivingEntity(
							singleTreat, player, bee, InteractionHand.MAIN_HAND);
					return result == InteractionResult.CONSUME;
				});
		if (!fed) return false;
		cursorItem.shrink(1);
		return true;
	}

	/**
	 * 无玩家的自动化喂食 + 小食槽消耗（供 {@link GeneTreatAutoFeeder} 使用）。
	 * <br/>
	 * 喂食成功后从输入槽扣除 1 个小食；失败不消耗，避免自动化路径吞物品。
	 *
	 * @param apiary     目标机械蜂箱
	 * @param slotIndex  蜜蜂槽位索引
	 * @param treatStack 输入槽中的小食（只读，方法内部使用单份副本）
	 * @param treatSlot  小食所在槽（成功后 shrink 1）
	 * @return 成功喂食并消耗小食时返回 {@code true}
	 */
	public static boolean feedGeneTreatFromSlot(TileEntityMekApiary apiary, int slotIndex,
			ItemStack treatStack, BasicInventorySlot treatSlot) {
		if (treatSlot == null || !isFeedableTreat(treatStack)) return false;
		// 含 TYPE 基因的小食在 PB 原版会被判为 invalid_use（不施加任何基因），自动化不应消耗
		if (HoneyTreat.hasBeeType(treatStack)) return false;

		ItemStack singleTreat = treatStack.copyWithCount(1);
		boolean fed = withTemporaryBee(apiary, slotIndex, "apiary_honey_treat_auto_failure",
				(level, bee) -> applyTreatWithoutPlayer(level, singleTreat, bee));
		if (!fed) return false;
		treatSlot.shrinkStack(1, Action.EXECUTE);
		return true;
	}

	/**
	 * 按 PB 原版 {@code HoneyTreat.interactLivingEntity} 的语义施加小食，但<b>不依赖玩家</b>。
	 * <br/>
	 * 与原版逐项对齐（仅省略玩家相关副作用）：
	 * <ul>
	 *   <li>清除愤怒计时 / 允许进巢 / 治疗满血 / 幼蜂成长 —— 与原版一致</li>
	 *   <li>{@code getData(ATTRIBUTE_HANDLER)} 先初始化属性 —— 与 PB
	 *       {@code AdvancedBeehiveBlockEntityAbstract} 装入蜜蜂时的做法一致，
	 *       保证刷怪蛋直接放入的蜜蜂也能被喂食（否则 {@code hasData} 为 false 会白扣小食）</li>
	 *   <li>{@code applyGenesToBee} —— 直接复用原版静态方法，概率与纯度语义完全一致</li>
	 *   <li><b>省略</b>：{@code player.swing}（无玩家）、成就触发、粒子与音效
	 *       （临时实体不在世界中，播放会指向错误位置）</li>
	 * </ul>
	 *
	 * @return 恒为 true（施加过程无失败分支；基因是否命中由 PB 概率决定，未命中也应消耗小食，与原版一致）
	 */
	private static boolean applyTreatWithoutPlayer(ServerLevel level, ItemStack singleTreat, Bee bee) {
		// 停止攻击 + 允许进巢 + 治疗（与原版同序）
		bee.setRemainingPersistentAngerTime(0);
		bee.setStayOutOfHiveCountdown(0);
		bee.heal(bee.getMaxHealth());
		if (bee.isBaby()) {
			bee.ageUp((int) ((float) (-bee.getAge() / 20) * 0.1F), true);
		}
		// 先初始化属性附件，再施加基因（PB 装入蜜蜂时的同款做法）
		bee.getData(ProductiveBees.ATTRIBUTE_HANDLER);
		HoneyTreat.applyGenesToBee(level, singleTreat, bee);
		return true;
	}

	/**
	 * 通用骨架：创建临时蜜蜂 → 执行操作 → 成功则回写 NBT 并标记保存。
	 * <br/>
	 * 临时实体<b>不加入世界</b>，仅作为 PB 属性逻辑的载体；用完即弃，由 GC 回收，
	 * 不会产生实体泄漏或区块加载副作用。
	 *
	 * @param apiary       目标蜂箱
	 * @param slotIndex    蜜蜂槽位索引
	 * @param logThrottleKey 异常日志节流键
	 * @param action       对临时蜜蜂执行的操作
	 * @return 操作生效且 NBT 已回写时返回 {@code true}
	 */
	private static boolean withTemporaryBee(TileEntityMekApiary apiary, int slotIndex,
			String logThrottleKey, BeeTreatAction action) {
		if (!(apiary.getLevel() instanceof ServerLevel level)
				|| slotIndex < 0 || slotIndex >= apiary.getBeeSlotCount()) {
			return false;
		}
		BeeSlot slot = apiary.getBeeSlot(slotIndex);
		CompoundTag beeData = slot.getBeeData();
		if (beeData == null) return false;

		EntityType<?> entityType = BeeNbtHelper.resolveEntityType(beeData);
		if (entityType == null) return false;

		try {
			Entity entity = entityType.create(level);
			if (!(entity instanceof Bee bee)) return false;
			bee.load(beeData);
			BlockPos apiaryPos = apiary.getBlockPos();
			bee.setPos(apiaryPos.getX() + 0.5D, apiaryPos.getY() + 0.5D, apiaryPos.getZ() + 0.5D);
			if (!bee.isAlive()) return false;

			if (!action.apply(level, bee)) return false;

			CompoundTag updatedBeeData = beeData.copy();
			bee.saveWithoutId(updatedBeeData);
			slot.setBeeData(updatedBeeData);
			apiary.setChanged();
			return true;
		} catch (RuntimeException e) {
			LogThrottle.warn(logThrottleKey,
					"机械蜂箱内基因小食喂食失败：槽位 {}，蜜蜂类型 {}",
					slotIndex, entityType, e);
			return false;
		}
	}

	/** 是否为可用于喂食的带基因小食。 */
	private static boolean isFeedableTreat(ItemStack stack) {
		return stack != null && !stack.isEmpty()
				&& stack.getItem() instanceof HoneyTreat
				&& HoneyTreat.hasGene(stack);
	}
}
