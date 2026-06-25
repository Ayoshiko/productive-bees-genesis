package com.ayoshiko.productivebeesgenesis.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.ayoshiko.productivebeesgenesis.InfinityCreationEventHandler;
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
			boolean isMyriad = MyriadCreationsEventHandler.MYRIADCREATIONS_TYPE.equals(beeType);
			boolean isInfinity = InfinityCreationEventHandler.INFINITY_CREATION_TYPE.equals(beeType);
			if (!isMyriad && !isInfinity) return;

			List<ItemStack> originalOutput = new ArrayList<>(cir.getReturnValue());
			List<ItemStack> enhancedOutput = new ArrayList<>();

			// 处理原版产出
			for (ItemStack stack : originalOutput) {
				if (isMyriad && hasCombBlockUpgrade && MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(stack)) {
					// Omega升级时将万象创世蜜脾转换为随机蜜脾块
					enhancedOutput.add(MyriadCreationsEventHandler.getRandomCombBlock());
				} else if (isInfinity && hasCombBlockUpgrade && InfinityCreationEventHandler.isInfinityCreationHoneycomb(stack)) {
					// Omega升级时将无尽·创世蜜脾转换为无尽·创世蜜脾块（保持数量）
					ItemStack blockStack = new ItemStack(
							com.ayoshiko.productivebeesgenesis.init.ModItems.INFINITY_CREATION_COMB_BLOCK_ITEM.get(),
							stack.getCount());
					enhancedOutput.add(blockStack);
				} else {
					enhancedOutput.add(stack);
				}
			}

			// 万象创世追加随机产出：Omega时追加4个随机蜜脾块，否则追加1个随机蜜脾
			if (isMyriad) {
				int extraCount = hasCombBlockUpgrade ? 4 : 1;
				for (int i = 0; i < extraCount; i++) {
					ItemStack randomOutput = hasCombBlockUpgrade
							? MyriadCreationsEventHandler.getRandomCombBlock()
							: MyriadCreationsEventHandler.getRandomHoneycomb();
					if (!randomOutput.isEmpty()) {
						enhancedOutput.add(randomOutput);
					}
				}
			}

			cir.setReturnValue(enhancedOutput);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("BeeHelper Mixin 异常", e);
		}
	}
}