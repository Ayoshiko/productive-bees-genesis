# Third-Party Licenses

本模组（Productive Bees Genesis）使用、修改或引用了以下第三方资产与代码。原作者保留所有未明确授予的权利。

## Productive Bees

- **项目**: Productive Bees
- **作者**: Copyright (c) 2024-2026 cy.jdkdigital and contributors
- **许可证**: MIT License
- **使用方式**: 本模组作为 Productive Bees 的附属模组，引用了其 API
- **源码**: https://github.com/JDKDigital/productive-bees

## Re:Avaritia

- **项目**: Re:Avaritia（Avaritia 的非官方重制版）
- **作者**: Nova-Committee 团队（程序员：cnlimiter、Asek3、MikhailTapio）
- **许可证**:
  - 代码：[MIT License](https://opensource.org/license/mit)
  - 资产：[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)
- **使用方式**: 参考了 Re:Avaritia 的宇宙渲染参数（COSMIC/COSMIC_ARMOR 渲染类型的参数配置），并在 `MixinShaderInstance` 中参考其着色器处理逻辑。基于 Re:Avaritia MIT 许可证源码派生，已在着色器文件头部保留版权声明。
- **源码**: https://github.com/Nova-Committee/Re-Avaritia/tree/neo/1.21.1
- **合规说明**: Re:Avaritia 资产采用 CC BY-NC-SA 4.0（非商业、相同方式共享），本模组仅参考其渲染参数而未复制资产，不触发 CC BY-NC-SA 4.0 的传染条款。

## Minecraft

- **项目**: Minecraft
- **作者**: Mojang Studios
- **许可证**: Mojang EULA
- **使用方式**: 本模组作为 Minecraft 的修改内容，遵循 Mojang 最终用户许可协议

## NeoForge

- **项目**: NeoForge
- **许可证**: LGPL-2.1
- **使用方式**: 作为模组加载器

## Mekanism

- **项目**: Mekanism
- **许可证**: MIT
- **使用方式**: 深度集成，扩展离心机功能

## Applied Energistics 2

- **项目**: Applied Energistics 2
- **许可证**: LGPL-3.0
- **使用方式**: 集成 ME 网络接口

## Applied Flux

- **项目**: Applied Flux
- **许可证**: MIT License
- **使用方式**: 集成 ME 网络中存储的 FE 能量提取（通过 `FluxKey.of(EnergyType.FE)` 从 `IStorageService` 提取）
- **依赖方式**: `compileOnly` 软依赖，运行时通过 `AppliedFluxIntegrationLoader.isAppliedFluxLoaded()` 守卫，未安装时不触发类加载

## Mek-Energistics

- **项目**: Mek-Energistics
- **作者**: beipuo
- **许可证**: MIT License
- **使用方式**: 参考其三层能量优先级模式（本地 FE → AE 网络能量 → AppliedFlux/AE 原生）设计本项目的 5 层能量优先级策略
- **源码**: https://github.com/beipuo/Mek-Energistics

## Iris

- **项目**: Iris Shaders
- **许可证**: LGPL-2.1
- **使用方式**: 宇宙渲染系统的着色器兼容

## Jade

- **项目**: Jade
- **许可证**: MIT
- **使用方式**: 方块信息显示集成

## Just Enough Items (JEI)

- **项目**: JEI
- **许可证**: MIT
- **使用方式**: 配方查看集成

## Super Factory Manager (SFM)

- **项目**: Super Factory Manager
- **许可证**: MIT
- **使用方式**: 自动化集成

## Rhino

- **项目**: Rhino（KubeJS 内嵌的 JavaScript 引擎）
- **作者**: Mozilla Foundation 及 KubeJS 贡献者
- **许可证**: [Mozilla Public License 2.0 (MPL 2.0)](https://www.mozilla.org/en-US/MPL/2.0/)
- **使用方式**: 通过 KubeJS 间接依赖，为脚本化蜜蜂配方注册提供 JavaScript 运行时
- **源码**: https://github.com/architectury/rhino

## Mekanism Unleashed

- **项目**: Mekanism Unleashed
- **许可证**: [MIT License](https://opensource.org/license/mit)
- **使用方式**: 扩展升级上限，支持每 tick 多次操作（STACK 升级突破原版上限）
- **源码**: https://github.com/Fire-Extinct/Mekanism-Unleashed

## Mekanism Empowered

- **项目**: Mekanism Empowered
- **许可证**: [MIT License](https://opensource.org/license/mit)
- **使用方式**: 提供强化速度/能量、IO 容量、自动插入器、快速物品插入/弹出升级，通过反射访问 MekEmpUpgrade API
- **源码**: https://github.com/Thelyrs/Mekanism-Empowered

## Mekanism Extras

- **项目**: Mekanism Extras
- **许可证**: MIT License
- **使用方式**: 可选依赖，提供 ABSOLUTE/SUPREME/COSMIC/INFINITE 等级的蜂箱与离心机工厂扩展
- **源码**: https://www.curseforge.com/minecraft/mc-mods/mekanism-extras

## EvolvedMekanism

- **项目**: EvolvedMekanism
- **许可证**: MIT License
- **使用方式**: 可选依赖，提供 OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE 高等级工厂，通过反射访问 FactoryTier 枚举
- **源码**: https://github.com/Nova-Committee/EvolvedMekanism

## EvolvedMekanismExtras

- **项目**: EvolvedMekanismExtras (EME)
- **许可证**: MIT License
- **使用方式**: 可选依赖，提供交叉等级的蜂箱与离心机工厂扩展（Absolute Overclocked/Supreme Quantum/Cosmic Dense/Infinite Multiversal），通过反射访问 FactoryTier 枚举。`libs/EvolvedMekanismExtras-1.21.1-1.2.1.jar` 仅用于开发编译，不分发给终端玩家
- **源码**: https://github.com/Nova-Committee/EvolvedMekanismExtras

## ProductiveLib

- **项目**: ProductiveLib（Productive Bees 附属通用库）
- **作者**: Copyright (c) 2024-2026 cy.jdkdigital and contributors
- **许可证**: MIT License
- **使用方式**: Productive Bees 附属开发库，为蜂箱/蜜蜂配方 API 提供基础类型（implementation 依赖）
- **源码**: https://github.com/JDKDigital/productive-bees

## Just Dire Things Extras (JDTE)

- **项目**: Just Dire Things Extras
- **作者**: JustDireThings 社区
- **许可证**: 以 Modrinth 页面声明为准（库内未附带独立许可证文本）
- **使用方式**: 可选联动，编译期引用 `CoalescedAcceleratedMachine` 合并加速接口（`libs/jdte-0.5.9-alpha1.jar`，仅开发编译用，不分发给终端玩家）；运行时经 `MixinConfigPlugin` 条件应用，未安装 JDTE 时完全不加载相关代码
- **来源**: https://modrinth.com/mod/just-dire-things-extras

## KubeJS

- **项目**: KubeJS（NeoForge）
- **作者**: KubeJS 团队
- **许可证**: [LGPL-3.0](https://www.gnu.org/licenses/lgpl-3.0.html)
- **使用方式**: 可选联动，通过 `kubejs.plugins.txt` 声明脚本化蜜蜂配方注册插件（`@KubeJSPlugin`），未安装 KubeJS 时插件类不会被加载
- **源码**: https://github.com/KubeJS-Mods/KubeJS

## Building Gadgets 2

- **项目**: Building Gadgets 2
- **许可证**: MIT
- **使用方式**: 可选联动，`RenderBlockBeLoadFixMixin` 修复建筑小工具渲染方块实体加载时序问题；未安装时经 `MixinConfigPlugin` 跳过该 Mixin
- **来源**: https://modrinth.com/mod/building-gadgets-2
