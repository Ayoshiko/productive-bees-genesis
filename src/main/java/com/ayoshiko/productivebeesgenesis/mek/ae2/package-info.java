/**
 * AE2 直接输出集成包
 * <br/>
 * 让 MEK 离心机作为 AE2 网格节点，主动将输出槽物品推送到 AE2 网络，
 * 绕过 SFM 中介，减少 AEItemKey.of() 与 ItemStack.hashItemAndComponents 的高频调用。
 * <p>
 * <b>optional 依赖隔离</b>：本包内的类引用 AE2 API，但仅在本包内使用。
 * 离心机类通过 {@link IAe2OutputHost} 接口（不引用 AE2 类）与本包通信，
 * 网格节点字段使用 {@code Object} 类型，确保 AE2 未安装时离心机类仍可加载。
 * <p>
 * <b>核心类</b>：
 * <ul>
 *   <li>{@link Ae2IntegrationLoader} — AE2 是否加载的检测与集成开关</li>
 *   <li>{@link IAe2OutputHost} — 离心机向 AE2 推送输出的宿主接口（不引用 AE2 类）</li>
 *   <li>{@link Ae2GridNodeManager} — 网格节点生命周期管理（创建/销毁/NBT 持久化）</li>
 *   <li>{@link Ae2OutputPusher} — 输出推送逻辑，封装 StorageHelper.poweredInsert</li>
 * </ul>
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
