package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.jerry.mekextras.api.ExtraUpgrade;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentUpgrade;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
	 * ME 堆叠升级上限 Mixin — 提升离心机工厂的 STACK 升级安装上限
	 * <br/>
	 * <b>背景</b>：ME 的 ExtraUpgrade.STACK 是 mekanism.api.Upgrade 枚举常量（非独立枚举），
	 * 由 ME 的 MixinUpgrade 在 Upgrade.&lt;clinit&gt; TAIL 注入，maxStack=8（2^8=256 倍并行）。
	 * 满级蜂巢产出速度超过离心机处理能力，需提升到 16（2^16=65536 倍并行）。
	 * <p>
	 * <b>方案选择</b>：Mixin TileComponentUpgrade（Mekanism 升级组件），针对性拦截 getMax() 调用。
	 * 不直接修改 Upgrade.STACK.maxStack（private final 字段，Java 21 中反射修改 final 字段困难
	 * 且会影响全局所有 ME 机器），而是在升级安装检查时，当 upgrade 是 STACK 且 tile 是我们的
	 * 离心机工厂时，返回配置的 mekCentrifugeMaxStackUpgrades（默认 16）。
	 * <p>
	 * <b>拦截点</b>：
	 * <ul>
	 *   <li>{@code tickServer()} — 升级安装进度检查 {@code upgrades < type.getMax()}</li>
	 *   <li>{@code addUpgrades(Upgrade, int, int)} — 升级安装计算
	 *       {@code installed < upgrade.getMax()} 和 {@code Math.min(upgrade.getMax() - installed, maxAvailable)}</li>
	 * </ul>
	 * 共 3 处 getMax() 调用，单一 wrapper（@WrapOperation）应用到所有匹配点。
	 * <p>
	 * <b>类加载安全</b>：本类引用 {@link ExtraUpgrade}（ME API 类），仅在 ME 已加载时由
	 * {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin} 应用。
	 * 未安装 ME 时本 Mixin 不会被加载，避免 NoClassDefFoundError。
	 * ME 的 MixinUpgrade 在 Upgrade.&lt;clinit&gt; TAIL 设置 ExtraUpgrade.STACK，
	 * 本 Mixin 的 wrap 在运行时被调用时 STACK 已完成注入。
	 * <p>
	 * <b>线程安全</b>：ModConfig.SERVER 在模组加载后只读，TileComponentUpgrade 在服务端线程访问，无并发问题。
	 * <p>
	 * <b>优先级</b>：priority=500（低优先级），让其他模组的高优先级 Mixin 先应用。
	 */
@Mixin(value = TileComponentUpgrade.class, remap = false, priority = 500)
public class ExtraUpgradeStackMixin {

	/** TileComponentUpgrade 持有的机器方块实体，用于判断是否为我们的离心机工厂 */
	@Shadow
	@Final
	private TileEntityMekanism tile;

	/**
	 * 拦截升级安装上限检查，针对离心机工厂提升 STACK 升级的 maxStack
	 * <br/>
	 * 当 upgrade 是 MEKExtras 的 STACK 升级且 tile 是我们的离心机工厂时，
	 * 返回配置的 mekCentrifugeMaxStackUpgrades（默认 16，范围 8-32）。
	 * 其他情况通过 {@code original.call(upgrade)} 调用原 getMax()，不影响其他机器（ME 原版机器、蜂箱等）。
	 * <p>
	 * wrapper 同时应用于 tickServer 和 addUpgrades 中的所有 getMax() 调用，
	 * 确保安装检查和安装计算都使用提升后的上限。
	 * <p>
	 * 使用 {@link WrapOperation} 而非 {@code @Redirect}，避免独占式拦截导致与其他模组 Mixin
	 * 冲突；{@code require = 0} 容忍目标方法签名变化，提升向前兼容性。
	 *
	 * @param upgrade 被检查的升级类型（getMax() 的调用者）
	 * @param original 原始 getMax() 调用包装，通过 {@link Operation#call(Object)} 委托原方法
	 * @return STACK 升级在离心机工厂中的安装上限，或其他升级的原 maxStack
	 */
	@WrapOperation(
		method = {"tickServer", "addUpgrades(Lmekanism/api/Upgrade;II)I"},
		at = @At(value = "INVOKE", target = "Lmekanism/api/Upgrade;getMax()I"),
		require = 0
	)
	private int productivebeesgenesis$overrideStackMax(Upgrade upgrade, Operation<Integer> original) {
		if (ExtraUpgrade.STACK != null && upgrade == ExtraUpgrade.STACK
				&& productivebeesgenesis$isCentrifugeFactory()) {
			// null 守卫与项目惯例一致（Ae2InputPuller 等），异常加载顺序下回退原值避免 NPE
			if (ModConfig.SERVER != null && ModConfig.SERVER.mekCentrifugeMaxStackUpgrades != null) {
				return BalanceConfig.centrifugeStackLimit(
						ModConfig.SERVER.mekCentrifugeMaxStackUpgrades.get());
			}
			return original.call(upgrade);
		}
		return original.call(upgrade);
	}

	/**
	 * 判断 tile 是否是我们的离心机工厂
	 * <br/>
	 * 通过类名前缀检查，避免直接引用离心机类（可能导致 ME/EME 未加载时类加载失败）。
	 * 覆盖：
	 * <ul>
	 *   <li>TileEntityMekCentrifuge — 基础离心机（不支持 STACK，但安全过滤）</li>
	 *   <li>TileEntityMekCentrifugeFactory — 原版工厂（继承 AbstractMekCentrifugeFactory）</li>
	 *   <li>TileEntityExtraMekCentrifugeFactory — ME 工厂（compat.mekanism_extras）</li>
	 *   <li>TileEntityEMExtraMekCentrifugeFactory — EME 工厂（compat.emextras）</li>
	 * </ul>
	 * 类名检查在 upgrade == STACK 时才触发，频率极低，性能开销可忽略。
	 *
	 * @return true 如果 tile 是我们的离心机类
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
