/**
 * 外部物流互操作层 — 让第三方物流模组（物流网络 / 天穹物流 / Pipez / SFM / AE2 等）以最高吞吐、
 * 最低开销地与本模组机器交换物品与流体，并替换 Mekanism 弹出器的低效实现。
 * <p>
 * 本包只放<b>与具体机器无关</b>的纯逻辑与小型状态对象，机器侧仅做接线（DIP）：
 * <ul>
 *   <li>{@link com.ayoshiko.productivebeesgenesis.logistics.FastItemEjector}
 *       — 自研物品弹出通道：一次遍历送完全部种类、先模拟再放入、轮转起点、自适应阻塞退避</li>
 *   <li>{@link com.ayoshiko.productivebeesgenesis.logistics.NeighborItemTargets}
 *       — 输出面方向解析与相邻容器能力缓存（逐刻弹出与产物直通共用同一份）</li>
 *   <li>{@link com.ayoshiko.productivebeesgenesis.logistics.ItemPushHelper}
 *       — 两段式插入：先模拟得到可接收量，再精确执行，避免“取出后塞不下”的回填往返</li>
 *   <li>{@link com.ayoshiko.productivebeesgenesis.logistics.IFastEjectHost}
 *       — 产物直通入口（由弹出器 Mixin 实现，配方提交侧只依赖该接口）</li>
 *   <li>{@link com.ayoshiko.productivebeesgenesis.logistics.RotatingContainerView}
 *       — 按游戏刻轮转的容器视图，消除“外部只抽前几个槽/罐”导致的饿死</li>
 *   <li>{@link com.ayoshiko.productivebeesgenesis.logistics.OutputWakeNotifier}
 *       — 输出“空→非空”边沿唤醒相邻物流网络，消除对端退避造成的空转延迟</li>
 *   <li>{@link com.ayoshiko.productivebeesgenesis.logistics.EjectItemMapBuilder}
 *       — 回退到 Mekanism 原版弹出（逻辑运输管道）时用的 O(n) 无分配清单构建</li>
 *   <li>{@link com.ayoshiko.productivebeesgenesis.logistics.ExternalLogisticsSettings}
 *       — 唯一对外开关（产物直通）的缓存读取；其余行为固定为最大速度，不再暴露节流参数</li>
 * </ul>
 * <p>
 * 线程安全：全部对象只被服务端 tick 线程访问；缓存字段仍使用 volatile/Atomic 做防御性发布，
 * 以兼容异步规划型物流模组在主线程之外读取 ItemStack 副本的场景。
 */
package com.ayoshiko.productivebeesgenesis.logistics;
