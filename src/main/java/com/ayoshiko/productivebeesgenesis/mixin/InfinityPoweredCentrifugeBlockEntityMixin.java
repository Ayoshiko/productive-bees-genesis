package com.ayoshiko.productivebeesgenesis.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ayoshiko.productivebeesgenesis.InfinityCreationEventHandler;

import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.PoweredCentrifugeBlockEntity;

/**
 * 无尽·创世 动力离心机Mixin：PoweredCentrifugeBlockEntity 重写了 canOperate()，需独立注入（无尽·创世体系）
 * <p>
 * 继承关系: CentrifugeBlockEntity → PoweredCentrifugeBlockEntity → HeatedCentrifugeBlockEntity
 * <ul>
 *   <li>Powered 重写 canOperate() 仅检查能量，忽略输出槽空间检查 → 需要此Mixin</li>
 *   <li>completeRecipeProcessing 未被重写 → 父类 InfinityCentrifugeBlockEntityMixin 已覆盖</li>
 * </ul>
 * 与 {@link PoweredCentrifugeBlockEntityMixin} 并存，两者互不干扰：
 * <ul>
 *   <li>本 Mixin 调用 {@link InfinityCreationEventHandler}，仅处理 infinitycreation 蜜脾/蜜脾块</li>
 *   <li>原 Mixin 调用 MyriadCreationsEventHandler，仅处理 myriadcreations 蜜脾</li>
 * </ul>
 * 方法名使用 productivebeesgenesis$infinity$ 前缀以与原 Mixin 区分。
 * 公共逻辑委托给 {@link CentrifugeMixinHelper}。
 */
@Mixin(PoweredCentrifugeBlockEntity.class)
public class InfinityPoweredCentrifugeBlockEntityMixin {

	/** canOperate RETURN — 能量充足但输出满时阻止启动（仅对无尽·创世蜜脾/蜜脾块生效） */
	@Inject(method = "canOperate", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$infinity$checkOutputSpace(CallbackInfoReturnable<Boolean> cir) {
		CentrifugeMixinHelper.checkCanOperate(cir, (CentrifugeBlockEntity) (Object) this, InfinityCreationEventHandler::shouldBlockOperation);
	}
}
