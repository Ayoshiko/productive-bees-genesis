package com.ayoshiko.productivebeesgenesis.mek;

/**
 * MEK 离心机统一标记接口。
 * 用于 TileComponentEjectorMixin 通过 instanceof 统一判断所有离心机类型，
 * 避免硬依赖 ME/EME 可选模组类引发 ClassNotFoundException。
 */
public interface IMekCentrifugeTile {
}
