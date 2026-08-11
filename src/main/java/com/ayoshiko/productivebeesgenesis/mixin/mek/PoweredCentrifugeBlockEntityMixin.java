package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeMixinHelper;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.PoweredCentrifugeBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * 动力离心机Mixin：PoweredCentrifugeBlockEntity 重写了 canOperate()，需独立注入（万象创世体系）
	 * <p>
	 * 继承关系: HeatedCentrifugeBlockEntity → PoweredCentrifugeBlockEntity → CentrifugeBlockEntity
	 * <ul>
	 *   <li>Powered 重写 canOperate() 仅检查能量，忽略输出槽空间检查 → 需要此Mixin</li>
	 *   <li>completeRecipeProcessing 未被重写 → 父类 CentrifugeBlockEntityMixin 已覆盖</li>
	 * </ul>
	 * 公共逻辑委托给 {@link CentrifugeMixinHelper}。
	 */
@Mixin(PoweredCentrifugeBlockEntity.class)
public abstract class PoweredCentrifugeBlockEntityMixin {

	/** canOperate RETURN — 能量充足但输出满时阻止启动 */
	@Inject(method = "canOperate", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$checkOutputSpace(CallbackInfoReturnable<Boolean> cir) {
		CentrifugeMixinHelper.checkCanOperate(cir, (CentrifugeBlockEntity) (Object) this, MyriadCreationsEventHandler::shouldBlockOperation);
	}
}
