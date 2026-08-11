/**
	 * AE2 直接输出集成包
	 * <br/>
	 * 让 MEK 离心机作为 AE2 网格节点，主动将输出槽物品推送到 AE2 网络，
	 * 绕过 SFM 中介，减少 AEItemKey.of() 与 ItemStack.hashItemAndComponents 的高频调用。
	 * <p>
	 * <b>v2.0.0 新增：AE2 网络能量输入</b>：离心机可从 AE2 网络提取 FE 能量
	 * （AppliedFlux 存储 + AE2 原生能量）注入到自身 {@code MachineEnergyContainer}，
	 * 由 {@link Ae2EnergyInjector} 协调提取顺序、{@link Ae2EnergyBridge} 执行底层提取。
	 * 默认关闭，由 {@code aeEnergyInputEnabled} 配置控制，向后兼容 v1.5.3 行为。
	 * <p>
	 * <b>AE2 类引用控制（v1.5.3 起，Task 3 拆分）</b>：本包内的类引用 AE2 API，这是实现 cable 连接的
	 * 必要设计权衡。{@link IAe2OutputHost} 自 v1.5.3 起扩展 {@code IInWorldGridNodeHost}，
	 * 强引用 AE2 类。Task 3 将其拆分为 {@link IAe2OutputHostBase}（无 import appeng，包含所有非 AE2 方法）
	 * 和 {@link IAe2OutputHost}（仅保留 AE2 线缆连接契约）。TileEntity 实现基接口 {@link IAe2OutputHostBase}，
	 * AE2 线缆连接契约由 Task 4 的 Mixin 在 AE2 已安装时动态添加。AE2 未安装时通过
	 * {@link Ae2IntegrationLoader#isAe2Loaded()} 在所有调用点短路保护，防止类加载失败；
	 * capability 注册也仅在 AE2 已安装时执行（参见 {@link Ae2CapabilityRegistrar}）。
	 * 网格节点字段仍使用 {@code Object} 类型存储，避免在状态持有者层强引用 AE2 类。
	 * <p>
	 * <b>核心类</b>：
	 * <ul>
	 *   <li>{@link Ae2IntegrationLoader} — AE2 是否加载的检测与集成开关</li>
	 *   <li>{@link AppliedFluxIntegrationLoader} — AppliedFlux 是否加载的检测（v2.0.0 新增）</li>
	 *   <li>{@link IAe2OutputHostBase} — 离心机/蜂箱向 AE2 推送输出的宿主基础接口（无 AE2 类引用）</li>
	 *   <li>{@link IAe2OutputHost} — AE2 线缆连接契约接口，继承 IAe2OutputHostBase + IInWorldGridNodeHost</li>
	 *   <li>{@link Ae2GridNodeManager} — 网格节点生命周期管理（创建/销毁/NBT 持久化）</li>
	 *   <li>{@link Ae2OutputPusher} — 输出推送逻辑，封装 StorageHelper.poweredInsert</li>
	 *   <li>{@link Ae2CapabilityRegistrar} — 注册 IN_WORLD_GRID_NODE_HOST capability（v1.5.3 新增）</li>
	 *   <li>{@link Ae2EnergyBridge} — AE 网络能量提取桥，封装 AppliedFlux FE + AE2 原生能量提取（v2.0.0 新增）</li>
	 *   <li>{@link Ae2EnergyInjector} — 能量注入协调器，按优先级提取并注入到容器（v2.0.0 新增）</li>
	 * </ul>
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
