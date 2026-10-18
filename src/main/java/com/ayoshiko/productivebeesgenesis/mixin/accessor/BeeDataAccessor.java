package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 读取包私有 BeeData 的当前占巢快照，避免反射或扩大其访问权限。 */
@Mixin(targets = "net.minecraft.world.level.block.entity.BeehiveBlockEntity$BeeData")
public interface BeeDataAccessor {
	/** 调用原始 BeeData.toOccupant()，不递增计时。 */
	@Invoker("toOccupant")
	BeehiveBlockEntity.Occupant productivebeesgenesis$toOccupant();
}
