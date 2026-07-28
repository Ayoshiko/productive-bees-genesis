# 资源蜜蜂：创世

![Version](https://img.shields.io/badge/version-2.0.0-blue?style=flat-square)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.214+-orange?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Java](https://img.shields.io/badge/Java-21+-red?style=flat-square)

> **资源蜜蜂**与**通用机械**附属模组 — 添加了**万象创世蜜蜂**（彩虹渐变，蜜脾可随机转化为其他资源蜜蜂的蜜脾），**通用机械离心机**以及 **通用机械蜂箱** 这些机器保留Mekanism原版机器的处理逻辑、GUI 风格、侧面配置和升级体系，同时深度集成**应用能源 2**，提供产物回网，蜜脾拉取，网络供电的能力。机器还实现了以Mekanism的升级安装方式安装资源蜜蜂的升级组件，提供了高效饲养蜜蜂处理蜜脾的新方式。

**语言**: [English](README.md) · [中文](README_zh.md)

> ⚠️ 本模组仍处于开发阶段，可能存在 bug、崩溃和兼容性问题。欢迎反馈。

---

## 目录

- [关于](#关于)
- [功能特性](#功能特性)
  - [万象创世蜜蜂](#万象创世蜜蜂)
  - [通用机械离心机](#通用机械离心机)
  - [通用机械蜂箱](#通用机械蜂箱)
  - [蜂箱直连离心机](#蜂箱直连离心机)
  - [AE2 集成](#ae2-集成)
  - [蜜蜂过滤界面](#蜜蜂过滤界面)
  - [KubeJS 集成](#kubejs-集成)
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
- **通用机械离心机** — 通用机械风格离心机，可处理资源蜜蜂的蜜脾和蜜脾块，同时支持充能冶炼炉 (Energized Smelter)的配方。
- **通用机械蜂箱** — 自带模拟功能的电力蜂箱，内部可饲养多只蜜蜂同时进行工作。其内置的饲养槽位可以摆放多种蜜蜂需要的采蜜花朵，且不会消耗。
- 深度**AE2 集成** — 离心机和蜂箱均可参与 AE 网络连接，支持 AE 默认频道传递。可直接将产物推送到 ME 网络，在 Mekanism 配置界面中为物品、流体输出添加 AE 输出开关。
  支持使用链接的ME网络中存储的FE能量，同时也保留了使用AE网络的AE能源。能源使用优先级可调。
  离心机还支持从链接的ME网络中拉取蜜脾到机器中进行处理，拉取蜜脾可配置。
- 保留 Mekanism 原版 GUI、侧面配置、自动弹出配置、升级按钮、安全系统、红石控制和配置工具交互。
- 支持 Jade 显示机器 AE 在线状态，内部物品，流体，能量等。
- 完全可配置的万象创世蜜蜂蜜脾转化过滤界面 — 支持黑白名单，搜索、排序、拖拽、剪贴板导入导出等。
- **KubeJS** 适配 — 支持在运行时动态注册蜜蜂配方。


完整变更记录请查看 [CHANGELOG](CHANGELOG.md)。

## 功能特性

### 万象创世蜜蜂

| 能力 | 说明 |
| --- | --- |
| 彩虹渐变 | 8秒色相循环，使用低饱和度的柔和色调 |
| 彩虹粒子 | 可选粒子特效 |
| 光晕效果 | 可选发光光晕 |
| 随机蜜脾 | 产出整合包内任意资源蜜蜂的蜜脾 |
| 过滤名单 | 黑名单/白名单模式，配游戏内编辑器 |
| 全属性可配 | 全部 PB 数据包属性（颜色、授粉、繁殖、环境等） |
| 获取方式 | 钓鱼、繁殖、巢穴生成、蜜蜂转化 — 全部可配置 |
| 禁用开关 | 禁用时仅保留通用机械离心机和蜂箱功能 |

### 通用机械离心机

- **通用机械离心机**：通用机械风格离心机，处理资源蜜蜂的蜜脾和蜜脾块，也支持充能冶炼炉（Energized Smelter）配方。
- **适配MEK拓展模组**，覆盖四个 Mekanism 系列附属模组：
  - **通用机械**：基础 / 高级 / 精英 / 终极
  - **通用机械：扩展**：绝对 / 至尊 / 寰宇支配 / 悖论无限
  - **进化通用机械**：超频 / 量子 / 致密 / 多元宇宙 / 创造
  - **进化通用机械：扩展**：绝对超频 / 至尊量子 / 宇宙致密 / 无限多元
- **输出安全**：相同堆叠自动合并；输出槽满时暂停，避免空耗能量。
- **弹出速度优化**：所有变体使用可配置的优化弹出延迟（活动/空闲/阻塞/繁忙四状态）。
- **流体自动弹出**：默认 16384 mB/tick，覆盖 Mekanism 原版的 1024 mB/tick。

### 通用机械蜂箱

基于 **Mekanism** 通用机械体系构建的电气化蜜蜂生产系统。通过继承 Mekanism 的电力机器基类，蜂箱完整复用 Mekanism 的能量存储、侧面配置、升级卡、安全互锁与 GUI 框架，将资源蜜蜂（PB）的原版蜂箱逻辑迁移到通用机械工业管线之上。

#### 蜂箱等级（17 个工厂等级 + 1 个基础版）

| 等级分类 | 等级名称 | 蜜蜂槽 | 输出槽 | 流体罐容量 |
| --- | --- | --- | --- | --- |
| 基础版 | 通用机械蜂箱 | 3 | 9 | 256,000 mB |
| 通用机械（4 等级） | 基础 / 高级 / 精英 / 终极 | 5–20 | 9–18 | 256K–1024K mB |
| 通用机械：扩展（4 等级） | 绝对 / 至尊 / 寰宇 / 无限 | 26–42 | 21–30 | 1280K–2048K mB |
| 进化通用机械（5 等级） | 超频 / 量子 / 致密 / 多元宇宙 / 创造 | 26–45 | 21–33 | 1280K–2304K mB |
| 进化通用机械：扩展（4 等级） | 绝对超频 / 至尊量子 / 寰宇致密 / 无限多元 | 45–60 | 33–42 | 2304K–3072K mB |

#### 核心特性

- **槽位体系**：蜜蜂槽 / 输出槽 / 蜂笼槽（双向转移）/ 能量槽 / 蜂蜜流体罐 / PB 升级槽。
- **喂食器系统**：独立窗口管理花朵物品。基础版使用固定 3×3 = 9 槽布局；工厂版按蜜蜂槽数量动态布局。
- **PB 升级系统（9 种）**：
  - 生产力 α / β / γ / Ω — 产出系数 1.2 / 1.5 / 2.0 / 2.6。
  - 时间 I / II — 每级处理时间减少 −15% / −30%。
  - 基因采样、蜜脾块、模拟升级。
- **AE2 集成**：物品 / 流体 / 能量输出到 ME 网络，支持 AppliedFlux 优先级切换。
- **直接弹出**：蜂箱相邻方块为离心机时，绕过通用机械弹出器节流，将蜜脾直接转移到离心机输入槽 — 详见[蜂箱直连离心机](#蜂箱直连离心机)。
- **Jade 悬停面板**：显示蜜蜂数量、生产进度、AE2 网络状态。
- **GUI 标签页**：排序 / 喂食器 / PB 升级 / 多流体罐 — 四个可自定义标签页，窗口位置持久化。

### 蜂箱直连离心机

蜂箱与离心机构成工业化**"产出 → 加工"**生产链：

1. 蜜蜂按 PB 配方生产蜜脾，蜂蜜注入蜂箱的流体罐。
2. 当蜂箱相邻方块为离心机时，蜂箱**绕过通用机械弹出器节流**，将蜜脾直接转移到离心机的输入槽。
3. 离心机处理蜜脾，产出蜜蜂产物。

这种短路设计消除了通用机械弹出器每 tick 限速和繁忙/阻塞冷却引入的吞吐瓶颈，使机器背靠背放置时能够实现满速率工业生产。

### AE2 集成

离心机与蜂箱均可作为 **AE2 网格节点**接入 ME 网络：

- 直接与 AE2 智能线缆和相邻机器连接。
- 自动被附属扩展模组线缆发现（ExtendedAE、AdvancedAE、ae2cs、ae2lt、Glodium、AppliedFlux）。
- **ME 直接输出**：将输出槽物品推送到 ME 网络，绕过外部物流。可在 Mekanism 配置界面中开关。
- **ME 能量输入**：从 ME 网络抽取 FE 能量为机器供能。支持 5 层能量优先级：
  1. 本地 FE 缓存
  2. 外部直接供能（Mekanism 配置组件 + 能量槽）
  3. ME 网络存储的 FE（AppliedFlux）
  4. 其他能量（由 Mekanism 父类处理）
  5. AE2 原生网络能量（转换为 FE）
- **蜜脾拉取**（仅离心机）：离心机可主动从连接的 ME 网络拉取蜜脾到机器内进行处理，拉取行为可配置。
- **Jade 悬停面板**：显示 AE2 网络连接状态（离线 / 加载中 / 缺少频道 / 在线）。
- 节点生命周期与产物输出开关解耦 — 关闭产物推送不会让设备离线，ME 能量输入通道不受影响。

### 蜜蜂过滤界面

- 游戏内黑名单/白名单编辑器，支持搜索、排序、分组折叠。
- 拖拽排序、剪贴板导入导出（JSON 数组）。
- 已添加/未添加蜜蜂的视觉区分。
- 数字索引、特殊蜜蜂的动态产物信息。
- 排序模式和折叠状态持久化。

### KubeJS 集成

模组内置 **KubeJS** 脚本钩子，可在运行时动态注册蜜蜂配方，无需在整合包开发阶段编写静态数据包。

- 在服务端脚本（`server_scripts`）中监听 `MyriadBeeEvents.REGISTER` 事件。
- 事件对象上暴露的辅助方法：
  - `addBreeding(...)` — 注册繁殖配方（亲代 1 + 亲代 2 → 子代）。
  - `addFishing(...)` — 注册钓鱼获取配方（群系列表或标签 + 概率）。
  - `addConversion(...)` — 注册蜜蜂转化配方（物品 + 源蜜蜂 → 目标蜜蜂）。
  - `addSpawning(...)` — 注册巢穴生成规则（巢穴类型 + 群系标签）。
  - `addCentrifuge(...)` — 注册离心机配方（可选自定义流体和处理时间）。
  - `addBeeProduce(...)` — 注册高级蜂箱产出配方。
  - `addMekData(...)` — 注册 Mekanism `mek_data` 有序合成配方。
- 通过 KubeJS 注册的配方会在 `beforeRecipeLoading` 阶段注入到原版 `RecipeManager` 的 JSON 映射中，与数据包配方共存；同时被游戏内配方缓存和 JEI 配方查看器识别。

## 前置依赖

| 模组 | 版本 | 用途 |
| --- | --- | --- |
| [Minecraft](https://www.minecraft.net/) | 1.21.1 | 游戏版本 |
| [NeoForge](https://neoforged.net/) | 21.1.214+ | 模组加载器 |
| [Productive Bees](https://www.curseforge.com/minecraft/mc-mods/productive-bees) | 1.21.1-13.13.5+ | 蜜蜂系统与蜜脾机制 |
| [Mekanism](https://www.curseforge.com/minecraft/mc-mods/mekanism) | 1.21.1-10.7.14.79+ | 通用机械离心机功能必需 |

## 兼容模组

以下模组为**可选依赖**。安装后对应功能会自动激活。

| 模组 | 集成内容 |
| --- | --- |
| [Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/mekanism-extras) | 通用机械：扩展 等级工厂（绝对 / 至尊 / 寰宇支配 / 悖论无限） |
| [Evolved Mekanism](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism) | 进化通用机械 等级工厂（超频 / 量子 / 致密 / 多元宇宙 / 创造） |
| [Evolved Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism-extras) | 进化通用机械：扩展 等级工厂（绝对超频 / 至尊量子 / 宇宙致密 / 无限多元） |
| [Mekanism Unleashed](https://www.curseforge.com/minecraft/mc-mods/mekanism-unleashed) | 扩展升级上限 |
| [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) | 线缆连接 + ME 网络产物输出 + ME 网络能量输入 |
| [AppliedFlux](https://www.curseforge.com/minecraft/mc-mods/appliedflux) | ME 网络存储的 FE 作为机器能量来源 |
| [ExtendedAE](https://www.curseforge.com/minecraft/mc-mods/ex-pattern-provider) | 自动发现的线缆连接 |
| [AdvancedAE](https://www.curseforge.com/minecraft/mc-mods/advanced-ae) | 自动发现的线缆连接 |
| [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) | AE2 网络状态悬停显示 + 蜂箱蜜蜂数量 / 生产进度 |
| [Iris Shaders](https://www.curseforge.com/minecraft/mc-mods/irisshaders) | 宇宙渲染光影兼容 |
| [Just Enough Items](https://www.curseforge.com/minecraft/mc-mods/jei) | 配方查看 + 禁用万象创世蜜蜂时自动隐藏相关配方 |
| [KubeJS](https://www.curseforge.com/minecraft/mc-mods/kubejs) | 通过 `MyriadBeeEvents.REGISTER` 在运行时注册蜜蜂配方 |

## 配置系统

模组提供三语配置系统（英文 / 中文 / 自动检测），可通过游戏内"模组 → 配置"界面访问。配置分为三个文件：

| 文件 | 作用域 | 主要内容 |
| --- | --- | --- |
| 客户端配置 | 客户端 | 蜜蜂过滤界面、端口颜色可视化、彩虹特效、自定义窗口位置 |
| 通用配置 | 通用 | 万象创世蜜蜂属性（外观、授粉、繁殖、环境、获取、产物、高级蜂箱） |
| 服务端配置 | 服务端 | 蜜蜂类型过滤、通用机械离心机参数、通用机械蜂箱参数 |

### 客户端配置

客户端配置主要管理界面显示与视觉效果：

- **蜜蜂过滤界面**：过滤界面的显示选项、分组折叠状态、排序模式。
- **端口颜色可视化**：机器侧面配置端口的颜色显示。
- **彩虹特效**：万象创世蜜蜂的彩虹渐变、粒子特效、光晕效果开关。
- **自定义窗口位置**：PB 升级、AE 输入配置、喂食槽等窗口的位置持久化，可钉住独立于主界面移动。

### 通用配置

通用配置管理万象创世蜜蜂的核心属性，分为以下几类：

- **外观**：彩虹渐变周期、粒子特效（开关、数量）、光晕效果（开关、颜色）。
- **授粉**：授粉概率、授粉花朵范围。
- **繁殖**：繁殖亲代配置、繁殖是否启用。
- **环境**：蜜蜂可活动群系、时间范围。
- **获取方式**：钓鱼（开关、概率、群系）、繁殖、巢穴生成（蜂巢类型、群系）、蜜蜂转化（源蜜蜂、转化物品、概率）。
- **产物**：万象创世蜜脾产出配置、蜜脾块转化倍率。
- **高级蜂箱**：模拟冷却、NBT 保存间隔等性能调参。

### 服务端配置

服务端配置是模组的核心，管理机器行为，分为两大模块：

#### 通用机械离心机配置

离心机配置按功能分组，共 21 个配置项：

**基础参数**：
- 每_tick 能量消耗：每个处理槽每 tick 的能量消耗（FE）。
- 处理时间：每次操作的基础处理时间（tick）。
- 流体罐容量：流体罐基础容量（mB），工厂版按并行数倍增。
- 流体弹出速率：离心机工厂每 tick 流体弹出速率（mB），范围 1-10240，默认 256。
- 蜜脾块产出倍率：离心蜜脾块时的产出倍数。
- 多流体槽模式：启用后按流体类型动态分配独立槽位。

**弹出策略**：
- 弹出延迟：输出槽自动弹出延迟（tick），推荐 2（0.1 秒）。
- 活动弹出延迟：输出槽仍有物品时的弹出延迟，默认 1（0.05 秒）。
- 输出未变化时跳过弹出：降低高倍加速下 CPU 开销。
- 输出未变化跳过刻数：输出未变化时连续跳过的 tick 数，默认 1。
- 最大弹出速度模式：跳过节流逻辑仅保留阻塞冷却，需目标容器空间充足。
- 弹出最小间隔：输出持续变化时两次调用的最小间隔，默认 0（关闭）。
- 高负载弹出阈值：连续未减少输出总量多少次后进入长冷却，默认 5 次。
- 高负载弹出冷却：高负载但未阻塞时跳过的 tick 数，默认 40（2 秒）。
- 单_tick 最大弹出次数：单 tick 最大弹出调用次数，默认 64。
- 阻塞弹出阈值：连续未弹出物品多少次后进入冷却。
- 阻塞弹出冷却：阻塞冷却跳过的 tick 数，0 为关闭。

**IO 限流**：
- 单_tick 最大拉取量：每 tick 外部通过管道/AE2 拉取的最大物品数，0 为无限制。

**AE2 集成**（仅当 AE2 已安装时注册）：
- 启用 AE2 直接输出：推送输出槽物品到 AE2 网络，默认开启。
- 启用 AE2 流体输出：推送蜂蜜流体到 AE2 网络，默认开启。
- 启用 AE 能量输入：从 ME 网络提取 FE 注入本地能量容器，默认开启。
- 启用 AE2 输入拉取：离心机主动从 ME 网络拉取输入物品。
- 每次拉取最大物品数：1-16384，过大可能增加 CPU 开销。
- 拉取触发间隔：游戏刻，值越大 CPU 开销越低但响应越慢。
- AE2 输入最小页数：AE2 输入过滤窗口最小页数（1-16）。
- AE 能量优先级：优先 AppliedFlux 还是 AE2 原生能量。

**输出槽堆叠倍率**：17 个工厂等级各自的输出槽堆叠倍率，默认从 1× 到 4096×。

**输入槽堆叠倍率**：蜜脾处理成倍产出，输入槽默认倍率为输出槽的 1/4。

**PB 升级上限**：产量升级、时间升级、基因采样升级、蜜脾块升级的最大安装数量。

**通用机械：扩展 升级上限**：堆叠升级最大数量，2^N 倍并行，仅作用于本模组离心机工厂。

**流体罐倍率**：按离心机等级配置流体罐容量倍率。

#### 通用机械蜂箱配置

蜂箱配置镜像离心机结构，并增加蜂箱专属调参：

**基础参数**：
- 蜂箱每_tick 能量消耗：每个处理槽每 tick 的能量消耗（FE），默认 50。
- 蜂箱处理时间：基础处理时间（tick），默认 1200。
- 蜂箱流体罐容量：基础版蜂箱流体罐容量（mB），默认 256,000，工厂版按等级硬编码。

**弹出策略**：与离心机的弹出策略对应，参数名前缀为"蜂箱"。

**输出槽堆叠倍率**：17 个工厂等级各自的输出槽堆叠倍率：
- 基础蜂箱（3 进程，9 输出槽）默认 1×
- 高级蜂箱（5 进程，12 输出槽）默认 2×
- 精英蜂箱（7 进程，15 输出槽）默认 4×
- 终极蜂箱（9 进程，18 输出槽）默认 8×
- 通用机械：扩展 绝对蜂箱（11 进程，21 输出槽）默认 16×
- 通用机械：扩展 至尊蜂箱（13 进程，24 输出槽）默认 32×
- 通用机械：扩展 寰宇蜂箱（15 进程，27 输出槽）默认 64×
- 通用机械：扩展 无限蜂箱（17 进程，30 输出槽）默认 128×
- 进化通用机械 超频蜂箱（11 进程，21 输出槽）默认 16×
- 进化通用机械 量子蜂箱（13 进程，24 输出槽）默认 32×
- 进化通用机械 致密蜂箱（15 进程，27 输出槽）默认 64×
- 进化通用机械 多元宇宙蜂箱（17 进程，30 输出槽）默认 128×
- 进化通用机械 创造蜂箱（19 进程，33 输出槽）默认 256×
- 进化通用机械：扩展 绝对超频蜂箱（12 进程，33 输出槽）默认 256×
- 进化通用机械：扩展 至尊量子蜂箱（14 进程，36 输出槽）默认 512×
- 进化通用机械：扩展 寰宇致密蜂箱（16 进程，39 输出槽）默认 1024×
- 进化通用机械：扩展 无限多元蜂箱（18 进程，42 输出槽）默认 4096×

**AE2 集成**（仅当 AE2 已安装时注册）：AE2 输出开关 + AppliedFlux 优先级切换，与离心机的 AE2 集成对应。

**PB 升级上限**：PB 升级卡堆叠上限 — 生产力 / 时间 / 基因采样 / 蜜脾块 / 模拟升级。

#### 万象创世蜜蜂过滤配置

- 过滤模式：禁用 / 黑名单 / 白名单。
- 过滤蜜蜂类型：过滤的蜜蜂类型列表（格式：模组ID:蜜蜂类型）。

### 访问配置界面

1. 打开 Minecraft 主菜单
2. 点击**模组**
3. 找到**资源蜜蜂：创世**
4. 点击**配置**按钮

配置修改在重启游戏或执行 `/reload` 后生效。

## 使用方法

1. 安装 NeoForge 和必需前置模组。
2. 将模组 jar 放入 `mods` 文件夹。
3. 启动游戏，通过任意已配置的获取方式（钓鱼、繁殖、巢穴生成或蜜蜂转化）获得**万象创世蜜蜂**。饲养并处理万象创世蜜脾和蜜脾块。
4. 在 **通用机械离心机**或其工厂变体中处理蜜脾或蜜脾块。
5. 将 **通用机械蜂箱**或其工厂变体中放入蜜蜂，在饲养槽内放入蜜蜂采蜜材料，蜜蜂生产蜜脾。
6. 通过智能线缆等方式将离心机/蜂箱连接到 AE2 网络，启用 ME 直接输出和 FE 能量输入。
7. （可选）通过 **KubeJS** 服务端脚本监听 `MyriadBeeEvents.REGISTER`，在运行时动态注册自定义蜜蜂配方。

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
├── apiary/             通用机械蜂箱系统 — 方块、方块实体、槽位/喂食器/升级
│                       处理器、GUI 标签页、蜜蜂实体渲染、PB 配方上下文
│   └── client/         蜂箱 GUI（排序 / 喂食器 / PB 升级标签页）、蜜蜂渲染器、
│                       蜜蜂名称 / 提示叠加层
├── capability/         限流物品处理器、库存标记防抖
├── client/             客户端事件处理器、JEI/Jade 插件、宇宙渲染、GUI 界面
│   ├── jei/             JEI 配方类别（PB 离心机配方）
│   ├── jade/            Jade 插件 — AE2 状态 + 蜂箱蜜蜂数量/进度显示
│   ├── render/cosmic/   宇宙着色器系统、烘焙模型、Iris 兼容
│   └── screen/          配置界面和通用机械离心机 GUI + 状态管理
├── command/            （保留供未来指令扩展）
├── compat/             可选模组集成
│   ├── kubejs/          KubeJS 插件 — MyriadBeeEvents.REGISTER + 配方序列化器
│   └── emextras/        进化通用机械：扩展 方块 / 方块实体注册
├── config/             客户端/通用/服务端配置，支持中英文双语
├── datagen/            方块标签、配方、战利品表、语言文件
├── init/               DeferredRegister 注册（方块、物品、方块实体等）
├── item/               自定义物品
├── mek/                通用机械离心机方块、方块实体、配方处理
│   └── ae2/            AE2 集成（产物推送、网格节点管理、能量注入器）
├── menu/               容器菜单定义
├── mixin/              Mixin 类，含 MixinConfigPlugin 条件加载器
│   ├── accessor/       访问器 mixin
│   ├── beehive/        蜂箱库存防抖与缓存 mixin
│   ├── client/         客户端 mixin（蜜蜂颜色、宇宙物品渲染）
│   ├── iris/           Iris 着色器兼容，含 IrisConfigPlugin
│   ├── mek/            通用机械离心机/工厂/弹出器 mixin
│   └── recipe/         配方序列化兜底 mixin
├── network/            网络载荷（过滤配置同步）
└── util/               蜜蜂信息助手、配方缓存管理器、离心机配方索引等
```

### 关键抽象

- **`AbstractCombEventHandler`** / **`MyriadCreationsEventHandler`**：随机蜜脾分配，通过 `RandomHoneycombSelector`（Fisher-Yates 洗牌、Stars-and-Bars 分配、均匀分配）实现。
- **`PbRecipeProcessor`**：PB 配方处理协调器，委托给 `PbRecipeFinder`（双层缓存）、`PbRecipeCompleter`（批量插入）、`MyriadCreationsHandler`。
- **`FactoryPbContextDelegate`**：组合类，消除三个工厂方块实体中约 293 行重复的 PB 配方上下文逻辑。
- **`TileEntityMekApiary`** / **`TileEntityMekApiaryFactory`**：通用机械蜂箱方块实体，继承 Mekanism 的电力机器基类；组合槽位管理器、PB 升级处理器、直连弹出处理器、蜂笼处理器、蜜蜂产出处理器、AE2 宿主适配器，将 PB 蜂箱逻辑迁移至通用机械工业管线。
- **`ApiaryDirectEjectHandler`**：短路弹出器，检测到相邻离心机时绕过通用机械弹出器节流。
- **`ApiaryTierMultiplierResolver`** + 各家族代理（`MEDelegate` 等）：在 17 个工厂等级之间解析每等级的蜜蜂槽数量、输出槽数量、流体罐容量和堆叠倍率。
- **`MyriadBeeRegisterEventJS`** / **`MyriadBeeEvents`**：KubeJS 事件组 + 事件对象，暴露 `addBreeding` / `addFishing` / `addConversion` / `addSpawning` / `addCentrifuge` / `addBeeProduce` / `addMekData` 配方构建器。
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

## 隐私与社区

### 隐私声明

本模组**不收集、存储或传输任何玩家数据**。无遥测、统计、崩溃上报或外部网络通信。所有网络包均为 Minecraft 原生通道内的客户端-服务端游戏状态同步。玩家 UUID 仅用作内存中的频率限制器键，不持久化、不外发。

### 适龄提示与健康提醒

本模组适合全年龄段玩家（内容主题：蜜蜂养殖与工业自动化，无暴力/血腥/成人内容）。

### 社区准则

我们致力于提供友好、包容的社区环境。禁止任何形式的骚扰、歧视、人身攻击或仇恨言论。违规行为请通过 [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues) 举报。

## 致谢

- **Productive Bees** 开发团队
- **Mekanism** 开发团队
- **NeoForge** 开发团队
- **Re:Avaritia**（Nova-Committee） — 星空材质的宇宙着色器参考
- **Applied Energistics 2** 开发团队
- **Mek-Energistics**（beipuo） — AE2集成参考
- **UselessMod** 开发团队 —部分优化参考
- **AppliedFlux** — ME 网络中的 FE 存储 API
- **KubeJS** — 运行时脚本钩子与事件组 API
- **Jade** — 方块悬停面板组件插件 API
