# 资源蜜蜂：创世

资源蜜蜂（Productive Bees）附属模组，添加**万象创世蜜蜂**，深度集成 Mekanism 离心机并优化所有工厂等级的产物弹出速度，附带宇宙星空渲染效果。

万象创世蜜脾/蜜脾块通过 BakedModel 包装器复用无尽·创世蜜脾的星空材质，保留原有功能（离心机处理、随机蜜脾转化）不变。

## 语言

- [English](README.md)
- [中文](README_zh.md)

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 万象创世蜜蜂 | 8秒彩虹渐变（通过Mixin覆盖PB默认1.25秒循环），带粒子特效；随机产出其他资源蜜蜂的蜜脾。 |
| 星空蜜脾材质 | 万象创世蜜脾/蜜脾块复用无尽·创世蜜脾的星空材质，仅替换视觉效果，保留所有机制。 |
| MEK离心机 | 自定义Mekanism机器，使用SMELTING配方类型处理资源蜜脾。 |
| 工厂等级 | 覆盖Mekanism、Mekanism Extras (ME)、Evolved Mekanism Extras (EME) 共17个等级。 |
| 智能配方处理 | SMELTING配方优先；PB CentrifugeRecipe独立处理，输出数量线性缩放。 |
| 输出安全 | 输出槽合并相同堆叠；机器满槽时暂停，避免空耗能量。 |
| 弹出优化 | 所有Mek离心机类型使用可配置弹出延迟（默认1~2 tick，原版10 tick）。 |
| 宇宙渲染 | 自定义星空/星云着色器，兼容Iris光影，带延迟渲染队列。 |
| 蜜蜂过滤界面 | 游戏内蜜蜂黑白名单编辑器，支持搜索、排序、折叠。 |
| 性能优化 | LRU配方缓存、能量/操作值缓存、预分配渲染矩阵、线程安全集合。 |

## 配置项

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `ejectDelay` | 2 | 输出槽为空时的弹出延迟（tick）。 |
| `ejectDelayActive` | 1 | 输出槽仍有物品时的弹出延迟（tick）。会自动限制为不超过 `ejectDelay`。 |
| `beeFilterMode` | Blacklist | 蜜蜂过滤模式：黑名单或白名单。 |
| `beeFilterList` | 空列表 | 被过滤的蜜蜂类型列表。 |

## 前置依赖

| 模组 | 版本 | 说明 |
| --- | --- | --- |
| Minecraft | 1.21.1 | 游戏版本。 |
| NeoForge | 21.1.214+ | 模组加载器。 |
| Productive Bees | 1.21.1-13.13.5+ | 蜜蜂系统与蜜脾机制。 |
| Mekanism | 1.21.1-10.7.14.79+ | Mek离心机功能必需。 |

## 兼容模组

| 模组 | 兼容性 |
| --- | --- |
| Mekanism Extras | ME高等级工厂。 |
| Evolved Mekanism Extras | EME高等级工厂。 |
| Mekanism Unleashed | 扩展升级上限。 |
| Iris | 宇宙渲染光影兼容。 |
| JEI | 配方查看。 |

## 使用方法

1. 安装 NeoForge 和必需前置模组。
2. 将模组 jar 放入 `mods` 文件夹。
3. 启动游戏，通过资源蜜蜂的蜂巢升级系统或整合包配置获取万象创世蜜蜂。
4. 在 Mek 离心机或其工厂版本中处理万象创世蜜脾。

## 构建

```bash
./gradlew.bat build
```

## 问题反馈

遇到问题或有建议请提交到 [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues)。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 致谢

- Productive Bees 模组团队
- Mekanism 开发团队
- NeoForge 开发团队
- Re:Avaritia（宇宙着色器参考）
