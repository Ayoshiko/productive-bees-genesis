package com.ayoshiko.productivebeesgenesis.mek;

/**
 * 标记需要 Ejector 输出阻塞冷却的离心机工厂方块实体。
 * <p>
 * 仅 ProductiveBeesGenesis 的三个工厂类实现此接口：
 * {@link TileEntityMekCentrifugeFactory}、
 * {@link TileEntityExtraMekCentrifugeFactory}、
 * {@link TileEntityEMExtraMekCentrifugeFactory}。
 * TileComponentEjectorCooldownMixin 通过此接口精准识别目标，避免影响其他 Mekanism 机器。
 */
public interface IHasEjectorCooldown {
}
