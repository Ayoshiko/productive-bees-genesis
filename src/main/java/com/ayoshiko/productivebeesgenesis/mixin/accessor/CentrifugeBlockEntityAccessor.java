package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;

/**
 * CentrifugeBlockEntity 访问器：暴露 protected 方法供外部调用
 */
@Mixin(CentrifugeBlockEntity.class)
public interface CentrifugeBlockEntityAccessor {
	@Invoker("getProductivityModifier")
	int productivebeesgenesis_getProductivityModifier();
}
