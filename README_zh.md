# 资源蜜蜂：创世

![Version](https://img.shields.io/badge/version-1.8.1-blue?style=flat-square)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.214+-orange?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Java](https://img.shields.io/badge/Java-21+-red?style=flat-square)

> **资源蜜蜂**与**通用机械**附属模组 — 添加了*万象创世蜜蜂*（彩虹渐变，蜜脾可随机转化为其他资源蜜蜂的蜜脾），以及完整的**通用机械风格离心机**家族，深度集成**应用能源 2**。

**语言**: [English](README.md) · [中文](README_zh.md)

> ⚠️ 本模组仍处于开发阶段，可能存在 bug、崩溃和兼容性问题。欢迎反馈。

---

## 目录

- [关于](#关于)
- [功能特性](#功能特性)
  - [万象创世蜜蜂](#万象创世蜜蜂)
  - [MEK 离心机](#mek-离心机)
  - [AE2 集成](#ae2-集成)
  - [蜜蜂过滤界面](#蜜蜂过滤界面)
- [前置依赖](#前置依赖)
- [兼容模组](#兼容模组)
- [配置系统](#配置系统)
- [使用方法](#使用方法)
- [构建](#构建)
- [架构](#架构)
- [问题反馈](#问题反馈)
- [许可证](#许可证)
- [致谢](#致谢)

## 关于

**资源蜜蜂：创世**是 NeoForge 平台上的附属模组，连接**资源蜜蜂**与**通用机械**。它提供了：

- **万象创世蜜蜂** — 特殊蜜蜂，其蜜脾可随机产出整合包内其他资源蜜蜂的蜜脾（支持可配置过滤）。
- **MEK 离心机** — 通用机械风格离心机，可处理资源蜜蜂的蜜脾和蜜脾块，支持**17 个工厂等级**（覆盖四个 Mekanism 系列模组）。
- 深度**AE2 集成** — 离心机作为 AE2 网格节点，可直接将产物推送到 ME 网络，也可从 ME 网络抽取 FE 能量供能。
- 完整的**游戏内蜜蜂过滤界面** — 支持搜索、排序、拖拽、剪贴板导入导出。

万象创世蜜脾使用了与无尽贪婪（Re:Avaritia）模组**寰宇支配之剑**（Sword of the Cosmos）相同的星空遮罩材质 — 详见[致谢](#致谢)。

完整变更记录请查看 [CHANGELOG](CHANGELOG.md)。

## 功能特性

### 万象创世蜜蜂

| 能力 | 说明 |
| --- | --- |
| 彩虹渐变 | 8秒色相循环，使用低饱和度的柔和色调，契合"创世"主题 |
| 彩虹粒子 | 可选粒子特效（`particleEffectEnabled`、`particleCount`） |
| 光晕效果 | 可选发光光晕（`glowEnabled`、`glowColor`） |
| 随机蜜脾 | 产出整合包内任意资源蜜蜂的蜜脾 |
| 过滤名单 | 黑名单/白名单模式，配游戏内编辑器 |
| 全属性可配 | 全部 PB 数据包属性（颜色、授粉、繁殖、环境等） |
| 获取方式 | 钓鱼、繁殖、巢穴生成、蜜蜂转化 — 全部可配置 |
| 禁用开关 | `myriadCreationsEnabled = false` 时仅保留 MEK 离心机功能 |

### MEK 离心机

- **MEK 离心机**：通用机械风格离心机，处理资源蜜蜂的蜜脾和蜜脾块，也支持充能冶炼炉（Energized Smelter）配方。
- **17 个工厂等级**，覆盖四个 Mekanism 系列模组：
  - **Mekanism**：Basic / Advanced / Elite / Ultimate
  - **Mekanism Extras**：Absolute / Supreme / Cosmic / Infinite
  - **Evolved Mekanism**：Overclocked / Quantum / Dense / Multiversal / Creative
  - **Evolved Mekanism Extras**：Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal
- **输出安全**：相同堆叠自动合并；输出槽满时暂停，避免空耗能量。
- **弹出速度优化**：所有变体使用可配置的优化弹出延迟（活动/空闲/阻塞/繁忙四状态）。
- **流体自动弹出**：默认 16384 mB/tick，覆盖 Mekanism 原版的 1024 mB/tick。

### AE2 集成

离心机通过标准 `IN_WORLD_GRID_NODE_HOST` capability 作为 **AE2 网格节点**：

- 直接与 AE2 智能线缆和相邻离心机连接。
- 自动被附属扩展模组线缆发现（ExtendedAE、AdvancedAE、ae2cs、ae2lt、Glodium、AppliedFlux）。
- **ME 直接输出**（`aeOutputEnabled`）：将输出槽物品推送到 ME 网络，绕过外部物流。
- **ME 能量输入**（`aeEnergyInputEnabled`）：从 ME 网络抽取 FE 能量为离心机供能。支持 5 层能量优先级：
  1. 本地 FE 缓存
  2. 外部直接供能（Mekanism configComponent + EnergyInventorySlot）
  3. ME 网络存储的 FE（AppliedFlux）
  4. 其他能量（由 Mekanism 父类处理）
  5. AE2 原生网络能量（转换为 FE）
- **Jade 悬停面板**：显示 AE2 网络连接状态（离线 / 加载中 / 缺少频道 / 在线）。
- 节点生命周期与 `aeOutputEnabled` 解耦 — 关闭产物推送不会让设备离线，ME 能量输入通道不受影响。

### 蜜蜂过滤界面

- 游戏内黑名单/白名单编辑器，支持搜索、排序、分组折叠。
- 拖拽排序、剪贴板导入导出（JSON 数组）。
- 已添加/未添加蜜蜂的视觉区分。
- 数字索引、特殊蜜蜂的动态产物信息。
- 排序模式和折叠状态持久化。

## 前置依赖

| 模组 | 版本 | 用途 |
| --- | --- | --- |
| [Minecraft](https://www.minecraft.net/) | 1.21.1 | 游戏版本 |
| [NeoForge](https://neoforged.net/) | 21.1.214+ | 模组加载器 |
| [Productive Bees](https://www.curseforge.com/minecraft/mc-mods/productive-bees) | 1.21.1-13.13.5+ | 蜜蜂系统与蜜脾机制 |
| [Mekanism](https://www.curseforge.com/minecraft/mc-mods/mekanism) | 1.21.1-10.7.14.79+ | MEK 离心机功能必需 |

## 兼容模组

以下模组为**可选依赖**。安装后对应功能会自动激活。

| 模组 | 集成内容 |
| --- | --- |
| [Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/mekanism-extras) | ME 等级工厂（Absolute / Supreme / Cosmic / Infinite） |
| [Evolved Mekanism](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism) | EM 等级工厂（Overclocked / Quantum / Dense / Multiversal / Creative） |
| [Evolved Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism-extras) | EME 等级工厂（Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal） |
| [Mekanism Unleashed](https://www.curseforge.com/minecraft/mc-mods/mekanism-unleashed) | 扩展升级上限 |
| [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) | 线缆连接 + ME 网络产物输出 + ME 网络能量输入 |
| [AppliedFlux](https://www.curseforge.com/minecraft/mc-mods/appliedflux) | ME 网络存储的 FE 作为离心机能量来源 |
| [ExtendedAE](https://www.curseforge.com/minecraft/mc-mods/ex-pattern-provider) | 自动发现的线缆连接 |
| [AdvancedAE](https://www.curseforge.com/minecraft/mc-mods/advanced-ae) | 自动发现的线缆连接 |
| [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) | AE2 网络状态悬停显示 |
| [Iris Shaders](https://www.curseforge.com/minecraft/mc-mods/irisshaders) | 宇宙渲染光影兼容 |
| [Just Enough Items](https://www.curseforge.com/minecraft/mc-mods/jei) | 配方查看 + 禁用万象创世蜜蜂时自动隐藏相关配方 |

## 配置系统

模组提供三语配置系统（英文 / 中文 / 自动检测），可通过游戏内"模组 → 配置"界面访问。配置分为三个文件：

| 文件 | 作用域 | 主要内容 |
| --- | --- | --- |
| `client.toml` | 客户端 | 蜜蜂过滤界面、端口颜色可视化、彩虹特效 |
| `common.toml` | 通用 | 万象创世蜜蜂属性（外观、授粉、PB 属性、繁殖、环境、获取、产物、高级蜂箱） |
| `server.toml` | 服务端 | 蜜蜂类型过滤、MEK 离心机参数（basic / ejection / io_limit / ae2 子分类） |

### MEK 离心机子分类

21 个 MEK 离心机配置项按功能分组：

- **basic** — energyPerTick、processingTime、fluidTankCapacity、fluidEjectRate、combBlockMultiplier
- **ejection** — ejectDelay、ejectDelayActive、ejectSkipUnchanged、ejectSkipTicks、ejectMaxSpeedMode、ejectMinInterval、ejectBusyThreshold、ejectBusyCooldown、ejectMaxPerTick、ejectBlockedThreshold、ejectBlockedCooldown
- **io_limit** — maxExtractPerTick
- **ae2** — aeOutputEnabled、aeEnergyInputEnabled、preferAppliedFluxOverAeEnergy（仅当 AE2 已安装时注册）

### 访问配置界面

1. 打开 Minecraft 主菜单
2. 点击**模组**
3. 找到**资源蜜蜂：创世**
4. 点击**配置**按钮

配置修改在重启游戏或执行 `/reload` 后生效。

## 使用方法

1. 安装 NeoForge 和必需前置模组。
2. 将模组 jar 放入 `mods` 文件夹。
3. 启动游戏，通过任意已配置的获取方式（钓鱼、繁殖、巢穴生成或蜜蜂转化）获得**万象创世蜜蜂**。
4. 在 **MEK 离心机**或其工厂变体中处理万象创世蜜脾。
5. （可选）通过智能线缆将离心机连接到 AE2 网络，启用 ME 直接输出和 ME 能量输入。

## 构建

```bash
git clone https://github.com/Ayoshiko/productive-bees-genesis.git
cd productive-bees-genesis
./gradlew build
```

构建产物位于 `build/libs/productivebeesgenesis-<version>.jar`。

> 需要 **Java 21**，且需联网从 Cursemaven / Modrinth Maven 下载 Mekanism、Productive Bees、AE2 等依赖。

## 架构

### 包结构

```
com.ayoshiko.productivebeesgenesis/
├── (根包)              主模组类、蜜脾事件处理器、随机蜜脾选择器
├── capability/         限流物品处理器、库存标记防抖
├── client/             客户端事件处理器、JEI/Jade 插件、宇宙渲染、GUI 界面
│   ├── jei/             JEI 配方类别（PB 离心机配方）
│   ├── jade/            Jade 插件 — AE2 状态显示
│   ├── render/cosmic/   宇宙着色器系统、烘焙模型、Iris 兼容
│   └── screen/          配置界面和 Mek 离心机 GUI + 状态管理
├── command/            （保留供未来指令扩展）
├── config/             ClientConfig / CommonConfig / ServerConfig，支持中英文双语
├── datagen/            方块标签、配方、战利品表、语言文件
├── init/               DeferredRegister 注册（方块、物品、方块实体等）
├── item/               自定义物品（无尽之剑复刻）
├── mek/                Mekanism 离心机方块、方块实体、配方处理
│   └── ae2/            AE2 集成（产物推送、网格节点管理、能量注入器）
├── menu/               容器菜单定义
├── mixin/              Mixin 类，含 MixinConfigPlugin 条件加载器
│   ├── accessor/       访问器 mixin
│   ├── beehive/        蜂箱库存防抖与缓存 mixin
│   ├── client/         客户端 mixin（蜜蜂颜色、宇宙物品渲染）
│   ├── iris/           Iris 着色器兼容，含 IrisConfigPlugin
│   ├── mek/            Mekanism 离心机/工厂/弹出器 mixin
│   └── recipe/         配方序列化兜底 mixin
├── network/            网络载荷（过滤配置同步）
└── util/               BeeInfoHelper、RecipeCacheManager、CentrifugeRecipeIndex 等
```

### 关键抽象

- **`AbstractCombEventHandler`** / **`MyriadCreationsEventHandler`**：随机蜜脾分配，通过 `RandomHoneycombSelector`（Fisher-Yates 洗牌、Stars-and-Bars 分配、均匀分配）实现。
- **`PbRecipeProcessor`**：PB 配方处理协调器，委托给 `PbRecipeFinder`（双层缓存）、`PbRecipeCompleter`（批量插入）、`MyriadCreationsHandler`。
- **`FactoryPbContextDelegate`**：组合类，消除三个工厂方块实体中约 293 行重复的 PB 配方上下文逻辑。
- **`AbstractBakedModelCosmic`**：宇宙渲染管线（着色器 uniform、mask 精灵、Iris 延迟），为 `BakedModelCosmic` / `BakedModelHell` / `BakedModelHalo` 的基类。
- **`Ae2GridNodeManager`** / **`Ae2OutputPusher`** / **`Ae2EnergyInjector`**：AE2 节点生命周期、产物推送、ME 网络能量注入（5 层优先级）。
- **`MixinConfigPlugin`**：条件 Mixin 加载器 — ME/EME 未安装时跳过相关 Mixin，防止崩溃。

### 线程安全

- 静态字段使用 `volatile` 保证跨线程可见性
- 并发集合：`ConcurrentHashMap`、`CopyOnWriteArrayList`
- 原子计数器：`AtomicInteger`、`AtomicLong`
- Holder 模式实现线程安全延迟初始化
- 非并发集合的复合操作使用 `synchronized` 块保护
- 服务器停止事件清理静态缓存并注销 JMX MBean，防止内存泄漏

### Mixin 命名规范

所有 Mixin 方法和字段使用 `productivebeesgenesis$` 前缀（如 `productivebeesgenesis$onInit`）。

## 问题反馈

遇到问题或有建议请提交到 [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues)。

## 许可证

本项目采用 **MIT 许可证**，详见 [LICENSE](LICENSE) 文件。

第三方资产与代码引用详见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 致谢

- **Productive Bees** 开发团队 — 蜜蜂与蜜脾系统
- **Mekanism** 开发团队 — 机器与工厂框架
- **NeoForge** 开发团队 — 模组平台
- **Re:Avaritia**（Nova-Committee） — 星空材质的宇宙着色器参考
- **Mek-Energistics**（beipuo） — AppliedFlux + AE2 网络能量输入集成模式参考
- **Applied Energistics 2** — `IN_WORLD_GRID_NODE_HOST` capability API
- **AppliedFlux** — ME 网络中的 FE 存储 API
