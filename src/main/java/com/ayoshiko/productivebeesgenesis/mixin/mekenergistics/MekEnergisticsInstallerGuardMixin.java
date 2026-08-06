package com.ayoshiko.productivebeesgenesis.mixin.mekenergistics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

/**
 * 阻止 Mek Energistics 将我们的离心机误判为 ME 电力熔炼炉/熔炼工厂。
 * <br/>
 * 背景：离心机为支持「熔炼 + PB 离心」双配方，复用 Mekanism 工厂体系并以
 * {@code FactoryType.SMELTING} 注册方块，因此方块携带 Mekanism 的
 * {@code AttributeFactoryType}。Mek Energistics 的
 * {@code MekanismMachineProvider.resolveOriginalMachine} 读取该属性后，
 * 会把我们的基础离心机解析为 {@code me_energized_smelter}、把工厂解析为对应
 * ME 熔炼工厂，导致 ME 工厂安装器把离心机错误转换成熔炼机器。
 * <p>
 * 本 Mixin 在解析入口直接返回 null（不可转换）。mekenergistics 作者后续会加
 * 判断逻辑，但本侧守卫可独立保留，防止未来回归；不依赖 mekenergistics 编译期
 * 类，通过 {@code targets} 字符串解耦可选依赖。
 *
 * @since 2.0.6
 */
@Mixin(targets = "com.beipuo.mekenergistics.compat.provider.MekanismMachineProvider", remap = false)
public abstract class MekEnergisticsInstallerGuardMixin {

	private MekEnergisticsInstallerGuardMixin() {
	}

	/**
	 * 拦截 {@code resolveOriginalMachine(BlockState)}：我们的离心机方块一律视为
	 * 不可转换，返回 null，避免被安装器替换为 ME 熔炼机器。
	 */
	@Inject(method = "resolveOriginalMachine", at = @At("HEAD"), cancellable = true, remap = false)
	private void productivebeesgenesis$blockCentrifugeConversion(BlockState state,
			CallbackInfoReturnable<Object> cir) {
		if (state != null && isOurCentrifuge(state.getBlock())) {
			cir.setReturnValue(null);
		}
	}

	/**
	 * 判断方块是否为本模组离心机系列（基础机器 + 各级工厂）。
	 * <br/>
	 * 命名约定统一包含 {@code mek_centrifuge}：
	 * <ul>
	 *   <li>基础机器：{@code mek_centrifuge}</li>
	 *   <li>原版工厂：{@code basic/advanced/elite/ultimate_mek_centrifuge_factory}</li>
	 *   <li>EM 工厂：{@code overclocked/quantum/..._mek_centrifuge_factory}</li>
	 *   <li>ME 工厂：{@code absolute/..._extra_mek_centrifuge_factory}</li>
	 *   <li>EME 工厂：{@code ..._emextra_mek_centrifuge_factory}</li>
	 * </ul>
	 */
	private static boolean isOurCentrifuge(Block block) {
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
		return id != null
				&& ProductiveBeesGenesis.MOD_ID.equals(id.getNamespace())
				&& id.getPath().contains("mek_centrifuge");
	}
}
