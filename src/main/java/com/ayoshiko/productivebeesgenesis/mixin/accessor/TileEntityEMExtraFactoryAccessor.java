package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import io.github.masyumero.emextras.common.tile.factory.TileEntityEMExtraFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * TileEntityEMExtraFactory 访问器：暴露EME基类的私有字段供外部包访问
 * <br/>
 * EME的TileEntityEMExtraFactory中activeStates、lastUsage和sorting是私有的，
 * PB离心机工厂需要在onUpdateServer中重算整体激活状态并追加PB能量消耗，
 * 在writeSustainedData/collectImplicitComponents中读取真实sorting字段值
 * （绕过父类isSorting()在AE2拉取期间锁死为false的问题），
 * 通过Accessor Mixin暴露getter/setter。
 *
 * <p>
 * <b>版本敏感性</b>：本 @Accessor 依赖 EvolvedMekanismExtras 1.2.1 的字段名稳定性
 * （TileEntityEMExtraFactory 继承自 Mekanism 的 TileEntityConfigurableMachine，
 * sorting/activeStates/lastUsage 字段均定义于 EME 自身）。
 * 通过 {@code @Accessor} 访问 {@code TileEntityEMExtraFactory#activeStates}（private final boolean[]）、
 * {@code TileEntityEMExtraFactory#lastUsage}（private long）、
 * {@code TileEntityEMExtraFactory#sorting}（private boolean）字段。
 * 如果 EvolvedMekanismExtras 重命名上述任一字段，本 Mixin 将无法应用，
 * 需同步更新本类对应的 @Accessor target。
 *
 * <p>
 * <b>关键修复</b>：EME 的 {@code TileEntityEMExtraFactory} 不继承 Mekanism 的
 * {@code TileEntityFactory}（继承 {@code TileEntityConfigurableMachine}），
 * 因此不能用 {@link TileEntityFactoryAccessor} cast EME 工厂实例，
 * 否则触发 ClassCastException。本 Accessor 是 EME 工厂 sorting 字段的唯一合法访问入口。
 *
 * @since 1.0.0
 */
@Mixin(value = TileEntityEMExtraFactory.class, remap = false)
public interface TileEntityEMExtraFactoryAccessor {

	/** 暴露activeStates数组 — 用于onUpdateServer中重算整体激活状态 */
	@Accessor("activeStates")
	boolean[] productivebeesgenesis$getActiveStates();

	/** 暴露lastUsage setter — 用于更新包含PB处理的能量消耗 */
	@Accessor("lastUsage")
	void productivebeesgenesis$setLastUsage(long value);

	/**
	 * 暴露sorting字段getter — 用于writeSustainedData/collectImplicitComponents
	 * 读取真实sorting值，绕过父类isSorting()在AE2拉取期间锁死为false的问题。
	 */
	@Accessor("sorting")
	boolean productivebeesgenesis$getSorting();

	/** 暴露sorting字段setter — 用于toggleSorting直接设置字段值，避免原版死锁 */
	@Accessor("sorting")
	void productivebeesgenesis$setSorting(boolean value);
}
