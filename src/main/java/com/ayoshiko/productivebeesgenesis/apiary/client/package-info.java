/**
 * MEK 蜂箱 GUI 客户端渲染包
 * <br/>
 * 负责：
 * <ol>
 *   <li>MEK 蜂箱主界面 {@link GuiMekApiary} 与工厂版 {@link GuiMekApiaryFactory}</li>
 *   <li>Tab 管理（喂食槽 Tab、PB 升级 Tab、排序 Tab、PB 升级列表）</li>
 *   <li>蜜蜂实体缓存与渲染（{@link BeeEntityCache}、{@link BeeEntityRenderer}）</li>
 *   <li>蜜蜂名称渲染与 Tooltip（{@link BeeNameRenderer}、{@link BeeTooltipRenderer}）</li>
 *   <li>蜂笼槽位覆盖与 GUI 排版辅助</li>
 * </ol>
 * <br/>
 * 线程语义：仅限客户端渲染线程（主线程）访问。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.apiary.client;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
