package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeMixinHelper;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.HeatedCentrifugeBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * PB 13.13.5 的热能离心机兼容注入。
	 *
	 * PB 13.14.0 将 {@code canOperate()} 提升为从 PoweredCentrifugeBlockEntity 继承，
	 * 该 Mixin 由配置插件仅在目标类实际声明旧方法时应用。
	 */
@Mixin(HeatedCentrifugeBlockEntity.class)
public abstract class HeatedCentrifugeLegacyCanOperateMixin {

	/** 旧版热能离心机的输出空间检查。 */
	@Inject(method = "canOperate", at = @At("RETURN"), cancellable = true)
	private void productivebeesgenesis$checkOutputSpace(CallbackInfoReturnable<Boolean> cir) {
		CentrifugeMixinHelper.checkCanOperate(cir, (CentrifugeBlockEntity) (Object) this,
			MyriadCreationsEventHandler::shouldBlockOperation);
	}
}
