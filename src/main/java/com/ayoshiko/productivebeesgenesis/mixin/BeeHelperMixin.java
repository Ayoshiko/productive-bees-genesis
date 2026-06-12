package com.ayoshiko.productivebeesgenesis.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.util.BeeHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * BeeHelper Mixin：为万象创世蜜蜂注入额外随机蜜脾产出
 * <p>
 * 注入目标: {@link BeeHelper#getBeeProduce} 方法返回前(RETURN)
 * <br>PB原版bee_produce配方保证100%产出万象创世蜜脾，Mixin额外追加随机蜜脾：
 * <ul>
 *   <li>无Omega升级：追加1个随机蜜脾</li>
 *   <li>有Omega升级：追加4个随机蜜脾块</li>
 * </ul>
 */
@Mixin(BeeHelper.class)
public class BeeHelperMixin {

	@Inject(method = "getBeeProduce", at = @At("RETURN"), cancellable = true)
	private static void productivebeesgenesis$appendRandomHoneycomb(
			Level level,
			Bee beeEntity,
			boolean hasCombBlockUpgrade,
			double modifier,
			CallbackInfoReturnable<List<ItemStack>> cir) {
		try {
			if (!(beeEntity instanceof ConfigurableBee configurableBee)) return;
			ResourceLocation beeType = configurableBee.getBeeType();
			if (!MyriadCreationsEventHandler.MYRIADCREATIONS_TYPE.equals(beeType)) return;

			List<ItemStack> originalOutput = new ArrayList<>(cir.getReturnValue());
			List<ItemStack> enhancedOutput = new ArrayList<>();

			// 处理原版产出：Omega升级时将万象创世蜜脾转换为蜜脾块
			for (ItemStack stack : originalOutput) {
				if (hasCombBlockUpgrade && MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(stack)) {
					// 转换为随机蜜脾块（与PB的getCombBlockFromHoneyComb逻辑一致）
					enhancedOutput.add(MyriadCreationsEventHandler.getRandomCombBlock());
				} else {
					enhancedOutput.add(stack);
				}
			}

			// 追加随机产出：Omega时追加4个随机蜜脾块，否则追加1个随机蜜脾
			int extraCount = hasCombBlockUpgrade ? 4 : 1;
			for (int i = 0; i < extraCount; i++) {
				ItemStack randomOutput = hasCombBlockUpgrade
						? MyriadCreationsEventHandler.getRandomCombBlock()
						: MyriadCreationsEventHandler.getRandomHoneycomb();
				if (!randomOutput.isEmpty()) {
					enhancedOutput.add(randomOutput);
				}
			}
			cir.setReturnValue(enhancedOutput);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("BeeHelper Mixin 异常", e);
		}
	}
}