package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.common.item.HoneyTreat;
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
 * 仅在玩家操作时创建一个不加入世界的临时蜜蜂实体，并调用 Productive Bees 原版
 * {@link HoneyTreat#interactLivingEntity}，从而复用基因概率、幼蜂成长、治疗、消耗和提示行为。
 */
public final class ApiaryHoneyTreatFeeder {

	private ApiaryHoneyTreatFeeder() {
	}

	/**
	 * 给指定蜂箱槽位内的蜜蜂喂食光标上的基因小食。
	 * <p>
	 * 方法必须在服务端主线程调用。小食先使用单份副本执行原版逻辑，只有蜜蜂 NBT
	 * 成功回写后才扣除真实光标物品，避免实体恢复失败造成物品丢失。
	 *
	 * @param apiary     目标机械蜂箱
	 * @param slotIndex  蜜蜂槽位索引
	 * @param player     执行操作的服务端玩家
	 * @param cursorItem 玩家容器光标上的物品
	 * @return 成功执行并回写蜜蜂数据时返回 {@code true}
	 */
	public static boolean feedGeneTreat(TileEntityMekApiary apiary, int slotIndex,
			ServerPlayer player, ItemStack cursorItem) {
		if (!(apiary.getLevel() instanceof ServerLevel level)
				|| slotIndex < 0 || slotIndex >= apiary.getBeeSlotCount()
				|| !(cursorItem.getItem() instanceof HoneyTreat honeyTreat)
				|| !HoneyTreat.hasGene(cursorItem)) {
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

			ItemStack singleTreat = cursorItem.copyWithCount(1);
			InteractionResult result = honeyTreat.interactLivingEntity(
					singleTreat, player, bee, InteractionHand.MAIN_HAND);
			if (result != InteractionResult.CONSUME) return false;

			CompoundTag updatedBeeData = beeData.copy();
			bee.saveWithoutId(updatedBeeData);
			slot.setBeeData(updatedBeeData);
			cursorItem.shrink(1);
			apiary.setChanged();
			return true;
		} catch (RuntimeException e) {
			LogThrottle.warn("apiary_honey_treat_failure",
					"机械蜂箱内基因小食喂食失败：槽位 {}，蜜蜂类型 {}",
					slotIndex, entityType, e);
			return false;
		}
	}
}
