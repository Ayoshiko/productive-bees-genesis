package com.ayoshiko.productivebeesgenesis.inventory;

/**
 * 窗口位置独立持久化标记接口
 * <br/>
 * 由 {@link com.ayoshiko.productivebeesgenesis.mixin.SelectedWindowDataMixin}
 * 注入到 {@link mekanism.common.inventory.container.SelectedWindowData}。
 * <p>
 * 仅客户端可用，服务端 instanceof 检查永远返回 false。
 * 该接口通过客户端 Mixin 注入到 common 路径类，服务端不实现。
 * <p>
 * 通过此接口可为 {@code SelectedWindowData} 实例设置自定义 saveName，
 * 使其位置和固定状态持久化到 PB 自己的客户端配置中，
 * 避免与 MEK 原版窗口（如升级窗口 saveName="upgrade"）共享同一份持久化数据。
 * <p>
 * <b>解决的问题</b>：PB 升级窗口使用 {@code WindowType.UPGRADE}（与 MEK 原版升级窗口相同），
 * 导致两者 {@code equals()} 返回 true，共享 MEK 配置中 "upgrade" 的位置/固定状态，
 * 固定一个窗口会联动影响另一个。设置 customSaveName 后，持久化完全独立。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>OCP：通过 Mixin + 接口扩展 SelectedWindowData 行为，不修改其源码</li>
 *   <li>DIP：持久化目标由 saveName 参数化，调用方按窗口类型注入</li>
 * </ul>
 */
public interface CustomWindowData {

	/**
	 * 设置自定义持久化 saveName
	 * <br/>
	 * 设置后，{@code SelectedWindowData.getLastPosition} 和
	 * {@code SelectedWindowData.updateLastPosition} 将使用 PB 配置系统
	 * 而非 MEK 配置系统进行持久化。
	 * 未设置（null）时行为不变，使用 MEK 原有逻辑。
	 *
	 * @param saveName 持久化键名（如 "window_pb_upgrade"、"window_ae_input"、"window_feeder"），null 清除
	 */
	void productivebeesgenesis$setCustomSaveName(String saveName);

	/**
	 * 获取自定义持久化 saveName
	 *
	 * @return saveName，未设置时返回 null
	 */
	String productivebeesgenesis$getCustomSaveName();
}
