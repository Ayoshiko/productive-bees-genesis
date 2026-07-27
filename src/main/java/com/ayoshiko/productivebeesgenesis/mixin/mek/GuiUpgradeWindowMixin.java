package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.jerry.mekextras.api.ExtraUpgrade;

import mekanism.api.Upgrade;
import mekanism.client.gui.element.window.GuiUpgradeWindow;
import mekanism.common.tile.base.TileEntityMekanism;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * MEK 升级窗口 Tab 显示修复 Mixin — 修复 STACK 升级显示 "16/8" 应为 "16/16" 的 bug
 * <br/>
 * <b>背景</b>：{@link ExtraUpgradeStackMixin} 已将离心机工厂的 STACK 升级安装上限从 8 提升到 16
 * （受 {@code ModConfig.SERVER.mekCentrifugeMaxStackUpgrades} 控制），但
 * {@code GuiUpgradeWindow.renderForeground} 渲染升级数量时直接调用
 * {@code selectedType.getMax()}，返回 Upgrade 静态字段值（8），未受 Mixin 影响，
 * 导致 Tab 显示 "16/8" 而非 "16/16"。
 * <p>
 * <b>方案</b>：拦截 {@code renderForeground} 中 {@code Upgrade.getMax()} 调用，
 * 当升级为 {@link ExtraUpgrade#STACK} 且宿主为离心机工厂时返回配置值（16），
 * 与 {@link ExtraUpgradeStackMixin} 的安装上限保持一致。
 * <p>
 * <b>类加载安全</b>：本类引用 {@link ExtraUpgrade}（ME API 类），仅在 ME 已加载时由
 * {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin} 应用。
 * 未安装 ME 时本 Mixin 不会被加载，避免 NoClassDefFoundError。
 * <p>
 * <b>线程安全</b>：本 Mixin 仅在客户端渲染线程执行，ModConfig.SERVER 在模组加载后只读，无并发问题。
 * <p>
 * <b>优先级</b>：priority=500（低优先级），让其他模组的高优先级 Mixin 先应用。
 */
@Mixin(value = GuiUpgradeWindow.class, remap = false, priority = 500)
public class GuiUpgradeWindowMixin {

	/** GuiUpgradeWindow 持有的机器方块实体，用于判断是否为离心机工厂 */
	@Shadow
	@Final
	private TileEntityMekanism tile;

	/**
	 * 拦截升级数量显示的 getMax() 调用
	 * <br/>
	 * 当 upgrade 是 MEKExtras 的 STACK 升级且 tile 是离心机工厂时，
	 * 返回配置的 mekCentrifugeMaxStackUpgrades（默认 16），与安装上限一致。
	 * 其他情况通过 {@code original.call(upgrade)} 返回原值，不影响其他机器的显示。
	 * <p>
	 * 使用 {@link WrapOperation} 而非 {@code @Redirect}，避免独占式拦截导致与其他模组 Mixin
	 * 冲突；{@code require = 0} 容忍目标方法签名变化，提升向前兼容性。
	 *
	 * @param upgrade 被显示的升级类型（getMax() 的调用者）
	 * @param original 原始 getMax() 调用包装，通过 {@link Operation#call(Object)} 委托原方法
	 * @return STACK 升级在离心机工厂中的显示上限，或其他升级的原 maxStack
	 */
	@WrapOperation(
		method = "renderForeground",
		at = @At(value = "INVOKE", target = "Lmekanism/api/Upgrade;getMax()I"),
		require = 0
	)
	private int productivebeesgenesis$overrideStackDisplayMax(Upgrade upgrade, Operation<Integer> original) {
		if (ExtraUpgrade.STACK != null && upgrade == ExtraUpgrade.STACK
				&& productivebeesgenesis$isCentrifugeFactory()) {
			// null 守卫与 ExtraUpgradeStackMixin 一致，异常加载顺序下回退原值避免 NPE
			if (ModConfig.SERVER != null && ModConfig.SERVER.mekCentrifugeMaxStackUpgrades != null) {
				return ModConfig.SERVER.mekCentrifugeMaxStackUpgrades.get();
			}
			return original.call(upgrade);
		}
		return original.call(upgrade);
	}

	/**
	 * 判断 tile 是否是离心机工厂
	 * <br/>
	 * 通过类名前缀检查，避免直接引用离心机类（可能导致 ME/EME 未加载时类加载失败）。
	 * 覆盖 ME 工厂与 EME 工厂两种变体（已迁移至 compat 包）。
	 *
	 * @return true 如果 tile 是离心机工厂类
	 */
	@Unique
	private boolean productivebeesgenesis$isCentrifugeFactory() {
		if (tile == null) return false;
		String name = tile.getClass().getName();
		return name.startsWith("com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge")
				|| name.startsWith("com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekCentrifugeFactory")
				|| name.startsWith("com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory");
	}
}
