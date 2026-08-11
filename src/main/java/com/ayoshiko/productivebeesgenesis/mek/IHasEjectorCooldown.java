package com.ayoshiko.productivebeesgenesis.mek;

/**
	 * 标记需要 Ejector 输出阻塞冷却的方块实体。
	 * <p>
	 * ProductiveBeesGenesis 的以下方块实体实现此接口：
	 * <ul>
	 *   <li>离心机工厂：{@link TileEntityMekCentrifugeFactory}、
	 *       {@link TileEntityExtraMekCentrifugeFactory}、
	 *       {@link TileEntityEMExtraMekCentrifugeFactory}</li>
	 *   <li>通用机械蜂箱：{@link com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary}
	 *       及其工厂版子类（通过继承自动覆盖）</li>
	 * </ul>
	 * TileComponentEjectorCooldownMixin 通过此接口精准识别目标，避免影响其他 Mekanism 机器。
	 * <p>
	 * 设计原则（OCP）：蜂箱通过实现此标记接口接入阻塞冷却逻辑，不修改离心机现有行为。
	 */
public interface IHasEjectorCooldown {
}
