package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;

/**
 * CentrifugeBlockEntity 访问器：暴露 protected 方法供外部调用
 * <br/>
 * 原始方法签名：{@code protected int getProductivityModifier()}
 */
@Mixin(CentrifugeBlockEntity.class)
public interface CentrifugeBlockEntityAccessor {
	@Invoker("getProductivityModifier")
	int productivebeesgenesis$getProductivityModifier();
}
