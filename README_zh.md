# 资源蜜蜂：创世

[![CurseForge](https://img.shields.io/badge/CurseForge-下载-orange?style=flat-square&logo=curseforge)](https://www.curseforge.com/minecraft/mc-mods/productive-bees-genesis)
![Version](https://img.shields.io/badge/version-1.1.0-blue?style=flat-square)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green?style=flat-square)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.214+-orange?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)
![Java](https://img.shields.io/badge/Java-21+-red?style=flat-square)

> **资源蜜蜂**与**通用机械**附属模组 — 添加了**万象创世蜜蜂**（彩虹渐变，蜜脾可随机转化为其他资源蜜蜂的蜜脾），**通用机械离心机**以及 **通用机械蜂箱** 这些机器保留Mekanism原版机器的处理逻辑、GUI 风格、侧面配置和升级体系，同时深度集成**应用能源 2**，提供产物回网，蜜脾拉取，网络供电的能力。机器还实现了以Mekanism的升级安装方式安装资源蜜蜂的升级组件，提供了高效饲养蜜蜂处理蜜脾的新方式。

**语言**: [English](README.md) · [中文](README_zh.md)

**当前开发版本：1.1.0，尚未发布。** 更新内容见 [CHANGELOG](CHANGELOG.md#unreleased---110)。

**下载**: [CurseForge](https://www.curseforge.com/minecraft/mc-mods/productive-bees-genesis) · [GitHub Releases](https://github.com/Ayoshiko/productive-bees-genesis/releases)

> 如遇到 bug 或兼容性问题，请通过 [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues) 反馈。

**翻译与贡献：** 所有人都可以通过 [Fork](https://github.com/Ayoshiko/productive-bees-genesis/fork) 提交 PR。在自己的 Fork 中新建分支，添加或修改 `src/main/resources/assets/productivebeesgenesis/lang/` 下的翻译文件（例如 `fr_fr.json`），再通过 **Contribute → Open pull request**，将目标仓库设为 `Ayoshiko/productive-bees-genesis`、目标分支设为 `main-neo/1.21.1`。直接向本仓库推送才需要写权限，从 Fork 提交 PR 不需要成为协作者。若这个流程仍被 GitHub 阻止，请在 issue 中提供完整报错与比较页面网址。

也可以[创建空白 issue](https://github.com/Ayoshiko/productive-bees-genesis/issues/new)，附上翻译 JSON；若 GitHub 不接受 JSON 附件，请先打包成 ZIP。

---

## 目录

- [关于](#关于)
- [功能特性](#功能特性)
  - [万象创世蜜蜂](#万象创世蜜蜂)
  - [通用机械离心机](#通用机械离心机)
  - [通用机械蜂箱](#通用机械蜂箱)
  - [蜂箱直连离心机](#蜂箱直连离心机)
  - [PB 升级系统](#pb-升级系统)
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

**资源蜜蜂：创世**是**资源蜜蜂**与**通用机械**的附属模组，目前支持NeoForge 1.21.1 ,它提供了：

- **万象创世蜜蜂** — 特殊蜜蜂，其蜜脾可随机产出整合包内其他资源蜜蜂的蜜脾（支持可配置过滤）。
- **通用机械离心机** — 通用机械风格离心机，可处理资源蜜蜂的蜜脾和蜜脾块，同时支持充能冶炼炉 (Energized Smelter)的配方。
- **通用机械蜂箱** — 自带模拟功能的电力蜂箱，内部可饲养多只蜜蜂同时进行工作。其内置的饲养槽位可以摆放多种蜜蜂需要的采蜜花朵，且不会消耗。
- 深度**AE2 集成** — 离心机和蜂箱均可参与 AE 网络连接，支持 AE 默认频道传递。可直接将产物推送到 ME 网络，在 Mekanism 配置界面中为物品、流体输出添加 AE 输出开关。
  支持使用链接的ME网络中存储的FE能量，同时也保留了使用AE网络的AE能源。能源使用优先级可调；能量提取保留 5% 网络存量，多台机器同时补能也不会抽干 ME 网络；安装 Applied Flux 后还可按机器关闭 AE2 原生能量提取。
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

通用机械风格工业化离心机，继承 Mekanism 原版电力机器基类，完整保留其 GUI 风格、侧面配置、升级体系、安全系统与红石控制，同时针对资源蜜蜂的蜜脾/蜜脾块处理流程进行深度优化。

#### 核心能力

- **多配方类型支持**：处理资源蜜蜂的蜜脾和蜜脾块，同时兼容充能冶炼炉（Energized Smelter）的熔炼配方——熔炉配方兼容为机器 GUI 内的独立开关（默认关闭），受服务端全局总开关控制。
- **工厂并行处理**：基础版单进程处理；工厂版按等级提供多进程并行处理（2~18 进程），大幅提升吞吐量。
- **输出安全机制**：相同产物堆叠自动合并；输出槽满时暂停处理，避免空耗能量。
- **弹出速度优化**：所有变体使用可配置的四状态弹出策略（活动/空闲/阻塞/繁忙），支持跳过未变化 tick、最大速度模式、高负载冷却等精细化控制。
- **流体自动弹出**：默认 16384 mB/tick，覆盖 Mekanism 原版的 1024 mB/tick。
- **多流体罐模式**：可配置按流体类型动态分配独立槽位，避免不同蜂蜜混合。
- **AE2 深度集成**：作为 AE2 网格节点接入 ME 网络，支持物品/流体直接输出、蜜脾与 SMELTING 输入拉取、ME 网络能量输入（5 层优先级）。
- **Jade 悬停面板**：显示 AE2 网络状态、内部物品/流体/能量等信息。

#### 离心机等级（17 个工厂等级 + 1 个基础变体）

| 等级分类 | 等级名称 | 并行进程 | 输入槽 | 输出槽 |
| --- | --- | --- | --- | --- |
| 基础版 | 通用机械离心机 | 1 | 1 | 3 |
| 通用机械（4 等级） | 基础 / 高级 / 精英 / 终极 | 3 / 5 / 7 / 9 | 3 / 5 / 7 / 9 | 9 / 15 / 21 / 27 |
| 通用机械：扩展（4 等级） | 绝对 / 至尊 / 寰宇支配 / 悖论无限 | 11 / 13 / 15 / 17 | 11 / 13 / 15 / 17 | 33 / 39 / 45 / 51 |
| 进化通用机械（5 等级） | 超频 / 量子 / 致密 / 多元宇宙 / 创造 | 11 / 13 / 15 / 17 / 19 | 11 / 13 / 15 / 17 / 19 | 33 / 39 / 45 / 51 / 57 |
| 进化通用机械：扩展（4 等级） | 绝对超频 / 至尊量子 / 宇宙致密 / 无限多元 | 12 / 14 / 16 / 18 | 12 / 14 / 16 / 18 | 36 / 42 / 48 / 54 |

#### 适配 MEK 拓展模组

覆盖四个 Mekanism 系列附属模组，工厂等级一一对应：
- **通用机械**：基础 / 高级 / 精英 / 终极
- **通用机械：扩展**：绝对 / 至尊 / 寰宇支配 / 悖论无限
- **进化通用机械**：超频 / 量子 / 致密 / 多元宇宙 / 创造
- **进化通用机械：扩展**：绝对超频 / 至尊量子 / 宇宙致密 / 无限多元

#### 升级支持

离心机支持三类升级体系，可共存安装：
- **Mekanism 原版升级**：速度（SPEED）、能量（ENERGY）、静音（MUFFLING）
- **通用机械：扩展 升级**：堆叠（STACK，2^N 倍并行）、创造（CREATIVE，无限能量）— 仅当 MEKExtras 已安装
- **通用机械：增强 升级**：强化速度、强化能量、IO 容量、自动插入器、快速物品插入、快速物品弹出 — 仅当 MekanismEmpowered 已安装
- **PB 升级系统**：详见 [PB 升级系统](#pb-升级系统) 章节

蜂箱支持相同的升级体系，其中一点不同：**所有等级的蜂箱均可安装 MEKExtras 创造（CREATIVE）升级**（无限能量、每刻产出——STACK 升级仍排除）。

### 通用机械蜂箱

 通用机械风格电力蜂箱。不同等级的工厂升级大幅提升了可饲养蜜蜂的数量。拥有独有的饲养板tab，可无消耗的存放多种蜜蜂的采蜜材料。蜂箱同样保留了 Mekanism 的能量存储、侧面配置、升级卡、安全、红石与 GUI 风格。能够安装资源蜜蜂的部分升级组件和效果。同样深度集成AE2，提供产物返回网络，从网络取电的能力。

#### 蜂箱等级

| 等级分类 | 等级名称 | 蜜蜂槽 | 输出槽 | 流体罐容量 |
| --- | --- | --- | --- | --- |
| 基础版 | 通用机械蜂箱 | 3 | 18（每页9格，共2页） | 256,000 mB |
| 通用机械（4 等级） | 基础 / 高级 / 精英 / 终极 | 5–20 | 30–60（每级2页） | 256K–1024K mB |
| 通用机械：扩展（4 等级） | 绝对 / 至尊 / 寰宇支配 / 悖论无限 | 26–42 | 60–84（每级2页） | 1280K–2048K mB |
| 进化通用机械（5 等级） | 超频 / 量子 / 致密 / 多元宇宙 / 创造 | 26–45 | 60–90（每级2页） | 1280K–2304K mB |
| 进化通用机械：扩展（4 等级） | 绝对超频 / 至尊量子 / 宇宙致密 / 无限多元 | 45–60 | 78–102（每级2页） | 2304K–3072K mB |

#### 蜂箱输出分页与吞吐

所有等级蜂箱统一使用两页输出槽。单只蜜蜂最多贡献 6 种不同的物品/组件组合，因此理论不同产物上限为 `6 × 蜜蜂槽数量`：基础蜂箱为 18 种，60 蜜蜂等级最多 360 种。每页仍是居中对齐的 3 行矩阵，第二页只增加物理容量，不扩大 GUI；宇宙致密和无限多元继续保持原来的 3×13、3×14 页面宽度。

分页只影响客户端容器视图。AE2、通用机械弹出器、相邻离心机和外部物品接口始终按稳定索引访问完整物理槽列表。产物分配先按物品/组件键合并，再访问同类槽索引和可复用空槽队列；外部抽取慢时，剩余产物按同一键聚合到可持久化的 FIFO 缓冲区（512 组），并带退避重试，正常混养突发不会因槽位暂时拥塞而丢失产物。

#### 核心特性

- **槽位体系**：蜜蜂槽 / 输出槽 / 蜂笼槽（双向转移）/ 能量槽 / 蜂蜜流体罐 / PB 升级槽。
- **喂食器系统**：自研MEK风格独立窗口管理花朵物品。基础版使用固定 3×3 = 9 槽布局；工厂版按蜜蜂槽数量动态布局。
- **PB 升级系统**：自研升级卡 — 生产力（α/β/γ/Ω）、时间（I/II）、基因采样、蜜脾块、副产物销毁，外加内置的模拟升级 — 详见 [PB 升级系统](#pb-升级系统) 章节。
- **AE2 集成**：物品和流体可推送至 ME 网络；机器能量可从 AppliedFlux 存储的 FE 或 AE2 原生能源补充，并支持优先级切换。
- **直接弹出**：蜂箱相邻方块为离心机时，绕过通用机械弹出器节流，将蜜脾直接转移到离心机输入槽 — 详见[蜂箱直连离心机](#蜂箱直连离心机)。
- **离心机优先**：按机器独立的开关，开启后蜜脾不进入 AE 网络、专供相邻离心机 — 蜜脾产出经快速通道直写离心机输入槽，不经蜂箱输出槽中转；缓冲区中的蜜脾永不被 FIFO 淘汰（溢出先回 AE 网络，FIFO 仅作最后兜底）。
- **Jade 悬停面板**：显示蜜蜂数量、生产进度、AE2 网络状态。
- **GUI 标签页**：排序 / 喂食器 / PB 升级 / 多流体罐 — 四个可自定义标签页，窗口位置持久化。
- **性能**：GUI 能量条节流同步（每 5 游戏刻一次）、tick 加速下的批量能量扣除、蜜蜂槽序列化缓存，大型工厂的 CPU 与网络开销保持轻量。

### 蜂箱直连离心机

蜂箱与离心机构成工业化**"产出 → 加工"**生产链：

1. 蜜蜂按 PB 配方生产蜜脾，蜂蜜注入蜂箱的流体罐。
2. 当蜂箱相邻方块为离心机时，蜂箱**不走通用机械原版的缓慢弹出**，通过自实现快速通道，将蜜脾快速转移到离心机的输入槽；开启离心机优先后，新产出的蜜脾更是不经蜂箱输出槽直接写入离心机输入槽。
3. 离心机处理蜜脾，产出蜜蜂产物。

这种短路设计消除了通用机械弹出器每 tick 限速和繁忙/阻塞冷却引入的吞吐瓶颈，使机器背靠背放置时能够实现满速率工业生产。离心机输入阻塞时按墙钟时间退避重试（250ms→2s），新产出保持零延迟，tick 加速模组下同样正确生效。

### PB 升级系统

本模组自研的 **PB 升级系统**独立于 Mekanism 原版升级体系，采用 Mekanism 升级卡的安装方式，但效果计算完全由本模组实现。该系统适用于**通用机械离心机**（生产力、时间、稳定性、副产物销毁）和**通用机械蜂箱**（生产力、时间、基因采样、蜜脾块、副产物销毁，外加内置的模拟升级），可共存安装。

#### 平衡性预设

服务端的**全局平衡性配置**决定升级系统的整体行为。在游戏内配置界面点击该配置项，会打开显示每个模式及其描述的选择列表：

| 模式 | 等级混装 | 离心机产量升级 | PB 升级上限 | 堆叠升级上限 |
| --- | --- | --- | --- | --- |
| **基础**（默认） | 生产力 α/β/γ/Ω 互斥；时间 I/II 互斥 | 仅增加并行（不放大单次产出） | 4 | 8 |
| **悖论无限** | 各等级可自由混装 | 同时增加产量与并行 | 8+ | 16+ |
| **自定义** | 使用下方各项独立规则 | 可配置 | 可配置 | 可配置 |

- 命名模式会钳制配置的上限：基础取配置值与 4 的较小值（堆叠：8）；悖论无限取配置值与 8 的较大值（堆叠：16）。
- 切换模式不会移除或归一化机器中已安装的升级——等级互斥规则只拦截新的安装。
- 从基础或悖论无限切换到自定义时，继承切换前实际生效的数值；平衡系统启用前的旧配置会迁移为自定义并保留原有行为。
- 基础模式贴近通用机械与资源蜜蜂原版的节奏；悖论无限解锁万象创世蜜蜂的全部力量。

#### 升级类型

| 升级类型 | 标识 | 效果说明 | 默认上限（基础） | 适用设备 |
| --- | --- | --- | --- | --- |
| 生产力 α | PRODUCTIVITY | 产出系数 +1.2×（每张） | 4（悖论无限下 8+） | 离心机 + 蜂箱 |
| 生产力 β | PRODUCTIVITY_2 | 产出系数 +1.5×（每张） | 4（悖论无限下 8+） | 离心机 + 蜂箱 |
| 生产力 γ | PRODUCTIVITY_3 | 产出系数 +2.0×（每张） | 4（悖论无限下 8+） | 离心机 + 蜂箱 |
| 生产力 Ω | PRODUCTIVITY_4 | 产出系数 +2.6×（每张），自带蜜脾块效果 | 4（悖论无限下 8+） | 离心机 + 蜂箱 |
| 时间 I | TIME | 处理时间减少（每张贡献 1 倍权重） | 4（悖论无限下 8+） | 离心机 + 蜂箱 |
| 时间 II | TIME_2 | 处理时间减少（每张贡献 2 倍权重） | 4（悖论无限下 8+） | 离心机 + 蜂箱 |
| 基因采样 | GENE_SAMPLER | 基因采样功能升级 | 4（可配置至 20） | 仅蜂箱 |
| 蜜脾块 | BLOCK | 将蜜脾产出转换为蜜脾块形式 | 1 | 仅蜂箱 |
| 稳定性 | STABILITY | 每张 +15% 离心机非保底产物概率 | 7 | 仅离心机 |
| 副产物销毁 | USELESS_BYPRODUCT | 丢弃蜂蜜与可选的花粉球副产物 | 1 | 离心机 + 蜂箱 |
| 模拟 | SIMULATION | 内置模拟生产（不占槽位） | — | 仅蜂箱 |

> 升级 GUI tooltip 中显示的所有加成数值（产量系数、时间加成、稳定性概率）均在运行时读取资源蜜蜂原版配置文件，始终反映玩家实际的 PB 配置。
>
> 默认的基础模式下，离心机产量升级仅增加并行、不放大单次产出，且同一台机器禁止混装生产力或时间各等级——切换到悖论无限或自定义即可解锁。

#### 效果计算方式

与资源蜜蜂原版的加算方式不同，本模组的 PB 升级采用**加权加算**模式，安装数量越多效果越强：

**生产力升级**（离心机与蜂箱通用）：
- 总产出倍率 = `1 + Σ(系数 × 数量)`，其中系数为 1.2 / 1.5 / 2.0 / 2.6
- 示例：安装 4 张生产力 α + 2 张生产力 γ = `1 + (1.2×4 + 2.0×2) = 1 + 8.8 = 9.8×` 产出（此类等级混装需在允许混装的模式下——悖论无限，或关闭互斥的自定义）
- 蜂箱额外乘以 1.22 补偿系数（平衡蜂箱与离心机的产出节奏），离心机无补偿系数
- 离心机的单次产出放大仅在平衡模式允许时生效（悖论无限，或开启"离心机产量升级增加产量"的自定义）；基础模式下离心机产量升级仅增加并行

**时间升级**（离心机与蜂箱通用）：
- 时间倍率 = `1 / (1 + 0.15 × (时间I数量 + 时间II数量 × 2))`
- 时间 II 每张贡献 2 倍权重，效果更强
- 示例：安装 2 张时间 I + 1 张时间 II = `1 / (1 + 0.15 × (2 + 1×2)) = 1 / 1.6 = 0.625`，即处理时间降至 62.5%（混装时间 I 与时间 II 同样需要允许混装的模式）

#### 安装上限差异化

不同升级类型的默认安装上限不同，所有上限均可在服务端配置中调整，并受[平衡性预设](#平衡性预设)进一步钳制：
- **生产力 / 时间升级**：默认基础模式下 4 张（悖论无限下 8+；自定义下可配置至 64）— 离心机与蜂箱通用
- **模拟升级**：默认 8 张（仅蜂箱）
- **基因采样升级**：默认 4 张，可配置至 20（仅蜂箱）
- **蜜脾块升级**：固定 1 张（仅蜂箱）
- **稳定性升级**：默认 7 张（仅离心机）
- **副产物销毁升级**：固定 1 张（功能型，离心机 + 蜂箱）

#### 与 Mekanism 原版升级的关系

PB 升级系统与 Mekanism 原版升级（SPEED / ENERGY / MUFFLING）完全独立，可同时安装：
- **Mekanism 原版升级**：由 Mekanism 模组自身处理效果（速度提升、能量容量、静音）
- **PB 升级系统**：由本模组处理效果（产出倍率、处理时间、基因采样等）
- 两套升级互不干扰，玩家可自由组合

### AE2 集成

离心机与蜂箱均可作为 **AE2 网格节点**接入 ME 网络：

- 直接与 AE2 智能线缆和相邻机器连接。
- 自动被附属扩展模组线缆发现。
- **ME 直接输出**：将输出槽物品推送到 ME 网络，绕过外部物流。物品和流体均有开关按钮。可在 Mekanism 配置界面中控制功能总开关。
- **流体直接输出**：蜂蜜和离心流体与物品输出共用网络感知批处理、按键退避和有界插入成本预算。
- **ME 能量输入**：从 ME 网络抽取 FE 能量为机器供能 — 注入目标为填满内部能量容器（能量条不再显示为接近空），且单次提取最多取网络存量的 95%，多台机器同时补能也不会抽干共享网络。支持 5 层能量优先级切换：
  1. 本地 FE 缓存
  2. 外部直接供能（Mekanism 配置组件 + 能量槽）
  3. ME 网络存储的 FE（AppliedFlux）
  4. 其他能量（由 Mekanism 父类处理）
  5. AE2 原生网络能量（转换为 FE）
- **AE2 原生能量提取开关**（`aeNativeEnergyInputEnabled`，仅安装 Applied Flux 时出现）：关闭后仅从 AppliedFlux 存储的 FE 提取能量，避免网络 FE 不足时过量抽取 AE 能量导致 ME 网络断电。
- **输入拉取**（仅离心机）：可从连接的 ME 网络主动拉取模糊匹配蜜脾、精确蜜脾/蜜脾块条目或普通 Mekanism SMELTING 输入。
- **拉取过滤**：支持关闭/黑名单/白名单、精确匹配、忽略 NBT、单项拉取量、单项或全局无限拉取，以及整机或单项网络库存保留线。大型精确白名单按游标轮转，后续条目不会饿死。
- **SMELTING 标签门**：普通熔炼候选可使用独立黑白名单表达式过滤，支持物品 id、标签、`&|^!()` 运算符和 `*` 通配符。
- **事务安全**：拉取后无法落槽的剩余物进入有界且持久化的机器 pending 缓冲，不再掉落到世界；物品输出使用持久化“预留—提交—确认”账本，只有源槽安全扣减后才最终确认网络插入。
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

#### 扩展资源蜜蜂

这部分面向整合包作者，也适合想自己加一只蜜蜂的玩家。先照着氢气蜂做通，再修改产物、颜色和花源；不需要写 Java。

**资源类型不限于这里列出的示例。** 氢气、FE、魔源、灵魂、压缩空气、EMC、经验和闪电是内置适配；任意已注册、可完整往返的 AEKey（包括其他模组的自定义资源）都可使用 `kind: ae_key` 走通用产物注册。须安装键提供方并准备对应 ME 存储；没有 AEKey 的新类型需要补充资源与传输适配。机器自动保管待输出资源，可从原生能力或本机 ME 连接输出，图标不能拖进背包，不需要额外资源接口方块。

#### 1. 先选生产方式

| 想让玩家怎么生产 | 参考示例 | 需要哪些文件 |
| --- | --- | --- |
| 蜂箱产蜜脾，再离心出氢气 | `hydrogen` | 蜂种 + PB 蜂箱配方 + 资源离心配方 |
| 蜂箱直接产 FE | `energy` | 蜂种 + 直接资源配方 |
| 蜂箱直接产极高压闪电 | `lightning_ehv` | 蜂种 + 直接资源配方 |
| 蜂箱同时产物品和 FE | `mixed` | 蜂种 + PB 蜂箱配方 + 直接资源配方 |

`energy` 与 `energy_comb` 是两只独立蜜蜂：前者直接产资源，后者先产蜜脾。`createComb: true` 只允许蜂种使用蜜脾外观，**不会自动创建产出蜜脾的配方**。同一蜂种同时添加两条路线会在两个阶段分别产出，只有希望这样设计时才这样写。

资源离心与普通 PB 蜜脾共用并行、虚拟 tick 和能耗计算。机械宿主每种资源的暂存上限为 `100 万 × 实际输出槽倍率`；旧默认额度自动扩容并保留余额。资源数量、生产力和蜜脾块四份计量仍占用额度，空间不足时保留输入，因此高产量配方仍可能受暂存与输出速度限制。

**Genesis 扩展资源产出暂未适配 PB 原版蜂箱和离心机，请使用 Genesis 机械蜂箱／离心机。** PB 自己的普通产物仍按 PB 配方工作。这些蜂种的蜂笼、刷怪蛋、蜜脾／蜜脾块及机械蜂箱蜂槽会默认显示兼容提示，无需按 Shift。

扩展资源功能及其兼容问题请反馈到 [Genesis 问题页](https://github.com/Ayoshiko/productive-bees-genesis/issues)，不要因上述适配限制向 PB 上游报错。JDTE、Useless 的开发接入要求见本节末尾。

#### 2. 放好示例，先让氢气蜂出现

从 [GitHub 示例目录](https://github.com/Ayoshiko/productive-bees-genesis/tree/main-neo/1.21.1/examples/resource-bees) 获取与模组版本匹配的文件。正式模组 JAR 不包含示例蜂或示例语言资源。

- `datapack` 放到 `<存档>/datapacks/resource-bees-example/`，其中直接包含 `pack.mcmeta` 与 `data`。
- `resourcepack` 放到 `<游戏目录>/resourcepacks/resource-bees-example/`，其中直接包含 `pack.mcmeta` 与 `assets`，并在客户端资源包菜单启用。
- 数据包格式为 48，资源包格式为 34，适用于 Minecraft 1.21.1。服务器装数据包，每位客户端加载资源包。
- `kubejs/server_scripts` 管理可选脚本；`api` 管理 Java 示例。现有配方 ID 保持不变，按文件用途寻找蜂种、蜂箱、直接生产和离心示例。

在仓库根目录运行 `.\gradlew packageResourceBeeExamples --no-daemon`，可在 `build/resource-bee-examples` 获得两个根目录正确的 ZIP；分别直接放入存档数据包目录和客户端资源包目录。开发 `runClient` 自动加载两者，发布 JAR 不打包示例。

另有按整合包蜂种格式编写的 `copper_energy`／`copper_energy_comb`：均以铜锭为花源，分别直接产 FE、先产蜜脾再离心产 FE。可选脚本 `kubejs/server_scripts/copper_energy_acquisition.js` 用蜂笼、蜂蜜点心和铜锭合成对应刷怪蛋，蜜脾路线再加蜂蜡。使用 KubeJS 时，数据与语言文件也可分别放到 `kubejs/data` 和 `kubejs/assets`；扩展资源配方需要 Genesis 1.1.0。

**注册蜂种时同步注册名称。** [中文语言文件](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/resourcepack/assets/productivebees/lang/zh_cn.json) 和 [英文语言文件](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/resourcepack/assets/productivebees/lang/en_us.json) 位于 `assets/productivebees/lang`。PB 对 `example:hydrogen` 使用 `entity.productivebees.hydrogen_bee`，而不是 `entity.example.hydrogen_bee`：

```json
{
  "entity.productivebees.hydrogen_bee": "氢气蜜蜂"
}
```

英文文件同键填写 `Hydrogen Bee`。蜜脾、刷怪蛋、JEI 和蜂箱预览复用蜂种名称。改蜂种路径时，同步所有配方 ID 引用和两份语言键；不同命名空间也应避免同路径名称冲突。每个蜂种 JSON 的 `_comment` 都提示其正确名称键。若仍显示原始键，启用语言资源包并按 F3+T；`/reload` 不重载客户端语言。

参考 PB 作者模板的安装方式，也可以把 `data` 和 `assets` 分别放到 `kubejs/data`、`kubejs/assets`，保留命名空间层级，每位客户端都需要 assets。不要再重复启用同一数据包；使用服务器脚本时删除它替代的 JSON 配方。

下面三份文件共同组成氢气蜂。示例 JSON 使用 `_comment` 写说明，仍是合法 JSON；说明字段可以保留，也可以删掉。不要在 JSON 中直接写 `//` 注释。文件夹 `example` 是命名空间，文件名 `hydrogen.json` 决定蜂种 ID 为 `example:hydrogen`。

**第一份：蜂种** — `data/example/productivebees/hydrogen.json`

```json
{
  "_comment": "氢气蜂的外观与花源；花源放在机械蜂箱的采蜜槽中。",
  "primaryColor": "#BFEFFF",
  "secondaryColor": "#FFFFFF",
  "flowerBlock": "minecraft:iron_block",
  "createComb": true
}
```

**第二份：蜂箱产什么蜜脾** — `data/example/recipe/hydrogen_honeycomb.json`

```json
{
  "_comment": "ingredient 和结果的 bee_type 必须与蜂种 ID 一致。",
  "type": "productivebees:advanced_beehive",
  "ingredient": "example:hydrogen",
  "results": [{
    "item": {
      "item": "productivebees:configurable_honeycomb",
      "components": {"productivebees:bee_type": "example:hydrogen"}
    },
    "chance": 1.0
  }]
}
```

**第三份：离心后得到什么** — `data/example/recipe/hydrogen_centrifuging.json`

```json
{
  "_comment": "每个蜜脾产 100 mB 氢气；蜜脾块自动按四份计算。",
  "type": "productivebeesgenesis:resource_centrifuging",
  "bee": "example:hydrogen",
  "outputs": [{"kind": "chemical", "id": "mekanism:hydrogen", "amount": "100", "chance": 1.0}],
  "processing_time": 20
}
```

增加蜂种后重新进入世界；修改已有配方可以用 `/reload`。测试时可用下面的命令获得蛋，再把蜜蜂放入机械蜂箱，采蜜槽放铁块并供电。示例不会自然生成蜜蜂；生存获取方式由整合包另配 PB 繁殖或转化配方。

```mcfunction
/give @s productivebees:spawn_egg_configurable_bee[minecraft:entity_data={id:'productivebees:configurable_bee',type:'example:hydrogen'}]
```

#### 3. 接线，看资源去了哪里

先让蜂箱产出带 `example:hydrogen` 类型的蜜脾，再放进 **Genesis 机械离心机**。机器供电后，一个蜜脾得到 100 mB 氢气；一个对应蜜脾块按四份得到 400 mB。这里是未计入基因和升级的基础数量；有概率的蜜脾块会分别抽四次。

想进 ME 网络：安装 **AE2 + Applied Mekanistics**，把线缆直接接离心机，给网络供电、频道以及能存化学品的存储元件。机器的 AE 输出总开关须开启。打开主 GUI 的“扩展资源”TAB 能看到待输出量；已送入网络的部分在 ME 终端查看。界面图标不可取出，产物不会掉到地上。

扩展资源窗口用于查看化学品、能量等物品／流体以外的产物，默认显示“生产监控”，可切换“待输出库存”。产率按近 5 秒实际成功结算量计算，含升级与加速影响；资源已全部输出、蜜脾因出口不可用返网后仍显示已观察资源和等待状态，停止生产后产率归零。统计不把库存转账计为新生产，不提供背包取放或额外存储。窗口打开时每 500 ms 同步变化，关闭后停止查询并释放会话；Jade 不同步产率。18×18 槽位保留原生 16×16 图标，详情与悬停提供数量、单位和状态。

化学品也能由 Mekanism 化学管道直接从机器拉取。同种资源汇总所有处理槽；可搜索、筛选，并按名称、产率／库存数量或类型排序。网格中的 K/M 等为向下截断的缩写，详情和悬停保留精确值，化学品原生罐展示本机待输出库存。“弹空”尝试把选中库存送入 ME 或相邻容器，拒收余量保留。FE 产物与工作电池分开；已入网库存请在 ME 终端查看。

空气和普通通用 AEKey 的 ME 输出使用长整数请求，不再统一限制为每次 2.1G。实际接收量仍受网络供电、存储容量、接收方接口和共享调度预算约束。原生 FE／魔源／灵魂接口使用 int，原生供气另受安全压力限制。Soulplied Energistics 1.0.3 的外部灵魂存储直接把 long 强转 int，灵魂及对应通用键暂保留每次 2,147,483,647 的兼容保护。

不走 ME 时，打开机器的 Mekanism 原生侧面配置窗口，选择与物品、流体、ENERGY 并列的资源页。每个资源页只在对应模组加载时出现；化学品使用 MEK 原生化学品侧面图标，其他资源页使用对应模组的代表性物品图标并在页签按钮内居中。化学品、魔源、灵魂、经验与闪电可在对应页选择蓝色输出面；空气通过安全压力下的主动供气进入气动管网。自动弹出送相邻兼容容器／机器，关闭后仍允许支持对应能力的外部管道提取。FE 使用原生 ENERGY 页或配置器：INPUT 给工作电池充电，OUTPUT 只输出 FE 产物，INPUT_OUTPUT 同面支持两者，NONE 关闭。工作电池和 FE 产物使用独立容器，输出不会抽走机器工作电量。

旧机器默认保留化学品等资源的六面提取，自动弹出关闭；空气自动供气开启，FE 产物面默认全关。设置随存档、安装器升级、拆卸／挖掘的机器物品及合成升级保存。MEK 配置卡 Shift 右键读取，普通右键粘贴到兼容机器，只复制侧面和自动弹出设置，不复制资源账户、余额或 EMC 收款人；多机器合成沿矩阵顺序取首份设置。

#### 4. 改成直接产 FE 或闪电

以 `energy.json` 定义的 `example:energy` 为例，花源换成铜块，然后只添加 `data/example/recipe/energy_production.json`：

```json
{
  "_comment": "机械蜂箱每个基础周期直接产 1000 FE，不经过蜜脾和离心。",
  "type": "productivebeesgenesis:resource_production",
  "bee": "example:energy",
  "outputs": [{"kind": "energy", "energy_type": "FE", "amount": "1000", "chance": 1.0}]
}
```

FE 进入 ME 需要 AE2 + Applied Flux；产物 FE 与机器工作用电分开保存。极高压闪电参考 `lightning_ehv.json` 和 `lightning_ehv_production.json`，把 `outputs` 改成下面这样，并安装 AE2 Lightning Tech 及其前置：

```json
[{"kind": "lightning", "tier": "extreme_high_voltage", "amount": "1000", "chance": 1.0}]
```

高压用 `high_voltage`，极高压用 `extreme_high_voltage`。两种电压分别存储，不能互换。先产闪电蜜脾的完整例子使用独立蜂种 `lightning_ehv_comb`；它需要自己的蜂箱与资源离心配方。

#### 5. 最常修改的字段

| 字段 | 怎么填 |
| --- | --- |
| `bee` / `ingredient` / `productivebees:bee_type` | 都填完整蜂种 ID，如 `mypack:hydrogen`；改命名空间时三处一起改 |
| `amount` | 正整数**字符串**，如 `"1000"`；不要写 `1000` 或 `1e3`；其他资源最大为 `9223372036854775807`，EMC 支持最多 4096 位十进制整数 |
| `chance` | 0 到 1 的数字；`1.0` 表示必定产出，`0.25` 表示每份 25% |
| `processing_time` | 离心基础时间，单位 tick；20 tick = 1 秒，只用于离心配方 |
| `flowerBlock` / `flowerItem` | 放进采蜜槽的方块或物品 ID，普通静态花源会重复使用 |
| `flowerTag` | 标签 ID，不加 `#`，如 `example:resource_flowers`；参考 `mixed_tag` |
| `include_pb_outputs` | 资源离心默认为 `false`；设为 `true` 时同时执行匹配的 PB 普通离心配方 |

改数量只改 `amount`；改化学品只改 `id`。新建另一只蜂时，复制蜂种及相关配方，修改文件名和所有蜂种 ID，避免覆盖原示例。

| 资源 | `outputs` 中的关键字段 | 资源模组 | ME 还需安装 |
| --- | --- | --- | --- |
| 化学品 | `kind: chemical`、`id: mekanism:hydrogen` | Mekanism | AE2、Applied Mekanistics |
| FE | `kind: energy`、`energy_type: FE` | 无额外前置 | AE2、Applied Flux |
| 魔源 | `kind: source` | Ars Nouveau | AE2、Ars Énergistique |
| 灵魂 | `kind: soul` | Industrial Foregoing Souls | AE2、Soulplied Energistics，及支持灵魂的存储元件 |
| 空气 | `kind: air` | PneumaticCraft | AE2、Applied Pneumatics |
| EMC | `kind: emc` | ProjectE | 自动入账在线收款玩家，无需 EMC-ME 扩展 |
| 经验点 | `kind: experience` | ExperienceLib | AE2、Applied Experienced |
| 闪电 | `kind: lightning`、`tier` | AE2 Lightning Tech | 使用对应电压的存储 |

空气的 1000 原始单位 = 1 L，压力 bar 不是产量。机器的原生能力可由 Ars 中继、灵魂管道、气动管道等提取对应资源；空气可主动送入相邻接收方。气动管路须封闭泄漏端。经验和魔源不会直接写入玩家个人数值。

**EMC 自动入账玩家账户。** 默认收款人为放置机器并拥有其资源账户的玩家，入账后同步 ProjectE 余额和已打开的转化界面，无需 AE2、ME 存储或 EMC-ME 扩展。同时安装 ProjectE 与 FTB Teams 时才显示收款人按钮，机器拥有者或管理员可在资源窗口点击“收款人”，循环选择同队在线玩家或返回机主；机器没有待输出库存时仍可切换。收款人离线、退队或能力不可用时 EMC 留在机器，恢复资格后继续入账；不会自动改投其他去向。未知写入结果隔离，通知重试不会重复加钱。第三方宿主继续使用自身输出规则。

**EMC 使用精确整数。** 生产、倍率、蜜脾块份数、待输出、存档余额与菜单数量支持最多 4096 位十进制整数。例如可把 MK16 的基础量声明为 `"amount": "47958864426752"`；蜂箱仍按自身蜂种周期和升级规则生产。安装 Project Expansion 后，可通过 BigInteger 能力向有足够空间的接收方交接完整大数量。普通 ProjectE 接收方按有界批次接收，余量留在同一账户；满载或异常不会导致越界或自动重放。不提供 AppliedE 适配。

其他资源保持原 long 上限；每种资源的待输出额度随机器输出等级计算，至少为 100 万，EMC 使用精确数量预算。旧余额在额度扩容时保留。高倍率加速仍需足够工作电量和接收空间。资源网络协议已更新，客户端与服务端须同步更新。

扩展资源没有可用出口或暂存额度不足时，机械离心机把原蜜脾交给持久化返还缓冲，ME 可接收时返网，并暂停该类蜜脾的新取料。出口恢复后自动续料；已付周期保留原配方、种子和完整输入身份，未知扣料／接收结果继续保管，不自动重放。

#### 容器花源：按里面的资源匹配

Genesis 机械蜂箱及其工厂支持把装有资源的物品作为可重复使用的花源。普通物品花源只看物品 ID；容器花源会核对内容，所以空罐、装错资源或数量不足都不能工作。

JEI 采蜜页面使用 AE2 存储元件展示容器花源的实际资源、最低含量和采蜜槽用法。对应键桥与兼容元件可用时，展示副本内含所需资源；否则仅显示提示。气体桶、Mekanism 化学品罐和提供对应能力的容器按内容识别，单件单储槽达标且不消耗。

在蜂种 JSON 中添加 **flowerResource**，填写 kind 和字符串 amount；amount 表示最低数量，省略时为 "1"。不要同时保留 flowerBlock、flowerItem、flowerTag、flowerFluid，也不支持 inverseFlower 或采后回调；冲突或错误定义会拒绝工作。

```json
{
  "flowerResource": {
    "kind": "chemical",
    "id": "mekanism:hydrogen",
    "amount": "1000"
  }
}
```

- **化学品容器**：kind 为 chemical，id 例如 mekanism:hydrogen，amount 例如 "1000"。使用提供 Mekanism 化学品物品能力的桶、罐或其他容器。参考 [氢气容器蜂](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_hydrogen.json)。
- **液体容器**：kind 为 fluid，id 例如 minecraft:water，amount 例如 "1000"。支持 NeoForge 流体能力物品与传统桶。参考 [水容器蜂](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_water.json)。
- **能量容器**：kind 为 energy，energy_type 为 FE。支持标准 FE 能力物品，以及按当前换算读取的 Mekanism 能量物品。参考 [能量容器蜂](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_energy.json)。
- **其他 AEKey 资源**：kind 为 ae_key，填写真实 type、id、完整 data。放入已存有该资源的真实 ME 存储元件，不需要把蜂箱接入网络才能检查这个元件。参考 [ME 元件花源蜂](https://github.com/Ayoshiko/productive-bees-genesis/blob/main-neo/1.21.1/examples/resource-bees/datapack/data/example/productivebees/container_ae_key.json)。

液体和化学品的 id 也可写带 # 的资源标签。已有 flowerFluid 蜂种继续使用原字段，现可识别普通桶以外的流体能力容器；其最低要求是存在正数量的对应液体。

**采蜜不抽取资源，不消耗容器。** 数量按单件容器检查，不乘物品堆叠数量，也不合并多个采蜜槽。原生容器须有一个内部储槽达到门槛，最多检查 64 个内部槽；ME 元件按一个完整资源键的可用量判断。蜜蜂基因和升级不放大花源门槛。

容器被清空、换内容或数量不足后，最迟下一个真实服务器 tick 停止采蜜；禁用槽立即退出匹配。资源包只改变外观，不会改变所装资源身份。

包裹资源图标、显示模板和连接远端仓库的卡片不等同于真实本地库存。其他模组的原生资源物品只有提供上述读取能力才可直接使用；已注册 AEKey 的资源可以使用对应真实存储元件。此项花源扩展当前只接入 Genesis 机械宿主，PB 原版／JDTE 不能按新字段生产。

新增蜂种时同步中英文名称键。KubeJS 配方写法不变，flowerResource 保留在数据包的蜂种 JSON 中。

#### 6. 使用 KubeJS，或者让一次加工有多种产物

蜂种 JSON 保留在数据包里。将对应示例 JS 复制到 `kubejs/server_scripts/`，删除它替代的配方 JSON，执行 `/reload`。例如 `hydrogen.js` 替代氢气的两份配方；`energy.js` 演示两只蜂的三份配方。脚本有逐步注释，数量也要写成字符串。数据包与脚本选一种方式管理同一配方，避免同 ID 相互覆盖。

`mixed` 演示同一只蜂产铁锭、蜜脾和 FE：保留同蜂种的 PB 蜂箱配方与直接资源配方。`mixed_essence` 演示精华转换，`mixed_tag` 演示标签花源。生产力、蜜脾块、基因采样和已有升级继续按机器原规则工作；纯资源蜂不会额外生成默认蜂蜜。

`combined` 演示离心时同时产普通物品与 FE。设置 `include_pb_outputs: true`，并保留匹配的 PB 离心配方；只扣一次输入和费用。没有匹配的普通配方会暂停。精华转换后再做粗矿熔炼；超出三个输出槽的余量留在机器内，排空后继续。

#### 7. 没跑起来时，按这个顺序检查

1. **JEI 没有配方／找不到蜂种：** 检查目录是否是 `recipe`（单数）、命名空间和 JSON 语法。新增蜂种后重新进入世界。
2. **蜜蜂等待花源：** 把定义中的花源放入采蜜槽，检查槽位是否禁用。氢气用铁块；FE、闪电例子用铜块。
3. **资源没有进网络：** 打开“扩展资源”面板，检查 AE 输出开关、频道、网络供电、桥接模组和对应存储元件；只有普通物品盘不能存氢气或闪电。
4. **本机资源已满：** 修好输出后会继续；满载时保留输入或已完成的产物，不需要重新注册配方。
5. **显示待核验：** 先保留存档，核对接收端、输入和费用，再由管理员处理。不要删除账户文件或反复补发；未确认的外部接收不会自动重试。

#### 进阶：通用资源、Java 和其他机器

已注册到 AE2 的其他类型可以用 `kind: ae_key`，填写真实 `type`、`id`、完整 `data` 和字符串 `amount`。`ae_key` 示例使用 AE2 自带的 `ae2:i` 物品键演示钻石；修改前先核对目标模组键的格式。完整身份限 1024 字节，不能用缺失内容占位物代替；这条路线只向 ME 输出。包裹物品仅用于显示或模板，不会给库存入账。

Java 示例在 `examples/resource-bees/api/ResourceBeeApiExample.java`，使用 `RegisterResourceRecipesEvent` 注册配方，第三方机器通过 `RegisterResourceHostsEvent` 与 `ResourceHostApi` 接入。数据包同 ID 覆盖 Java 默认配方。周期预约、扣料与提交必须在同一服务器 tick 完成；`transfer` 回调返回实际接收量，异常会暂停，不能把未知结果当作零接收。编译示例：

```powershell
.\gradlew compileResourceApiExampleJava -I examples/resource-bees/api-example.gradle --no-daemon
```

**Useless 1.21.1-2.4.6 已有原生配方转换和通用资源键 API，Genesis 通过这些接口注册资源离心配方，无需额外宿主补丁。** 在高级合金炉使用 PB 离心机作为模具，输入带完整蜂种组件的蜜脾／蜜脾块；还需 AE2、对应资源键桥和接收存储。只转换确定性、独立的资源离心配方，随机及 `include_pb_outputs` 组合不接入。并行、催化剂、能耗、加工、输出和恢复遵守 Useless 自身规则，不向 Genesis 资源账户重复记账。

JDTE 0.6.4.3 官方版仍缺蜂种扩展配方入口。本地 API v3 补丁仅对显式匹配的扩展蜂触发：Genesis 提供配方产物，生物工厂负责费用和已有周期暂存，通过绑定网络的原生 AE 输出升级回网；还需对应资源键桥与接收 ME 存储。拒收、部分接收保留余量，执行结果未知时暂停自动重试。FE、氢气、完整物品／流体键及两种闪电电压已通过原生输出流程验证。正式 PR 留待 Genesis 资源扩展版本发布后提交。旧过渡桥、API v1／v2 与 Useless 保管记录仅作历史证据；旧 Useless 核验命令仅在原型接口存在时保留。

管理员可用 `/pbgresources` 查看资源账户，用 `/pbgoutput <坐标> status` 查看普通输出保管。核验两端后才执行对应 `resolve`；`retry-data` 用于恢复缺失依赖后的原文重读，`confirm-production-settled` 不会补发产物。正常重启和升级保留已有资产与未决请求。旧 .session 文件不再作为停产判据。已保存的未知生产只隔离原车道，未知输出只隔离对应资源，其他生产继续。缺依赖的资源蜜脾不会进入万象候选或离心输入；已进入的蜜脾转入持久化返还缓冲，释放生产槽。真实损坏数据和保存故障仍保管受影响资产，不代表跨存储原子提交。安装 Project Expansion 后使用其短格式显示 EMC，后台数量保持精确。

输入拉取及返还的未决请求也由 `status` 展示。核验两端后可用 `/pbgoutput <坐标> resolve-input <UUID> <实际转移量>`：`extract`／`withdraw-slot` 填写实际从 ME 或源槽扣出的数量，`slot`／`return` 填写接收方实际收到的数量。确认后已知余量只回 ME。未知结果暂停普通蜜脾、扩展资源和熔炼加工，不自动重试。

暂时移除 AE2 时，机器仍保留输入过滤配置的原始数据，再装回后恢复白名单、完整物品指纹、限额及库存策略。无法读取的版本或损坏字段会暂停新取料并拒绝界面编辑，`status` 显示原因；修复原数据后可用 `retry-data` 重读。配置恢复不清除、确认或复制已有输入暂存和未决请求。等级升级同样保留配置，交接记录走独立快照；蜂箱保留其实际 AE 输出开关。

## 前置依赖

| 模组 | 版本 | 用途 |
| --- | --- | --- |
| [Minecraft](https://www.minecraft.net/) | 1.21.1 | 游戏版本 |
| [NeoForge](https://neoforged.net/) | 21.1.214+ | 模组加载器 |
| [Productive Bees](https://www.curseforge.com/minecraft/mc-mods/productive-bees) | 1.21.1-13.13.5+（已验证 13.14.0） | 蜜蜂系统与蜜脾机制 |
| [Mekanism](https://www.curseforge.com/minecraft/mc-mods/mekanism) | 1.21.1-10.7.14.79+ | 通用机械离心机功能必需 |

> Productive Bees Genesis 1.0.10 同时支持资源蜜蜂 13.13.5 和 13.14.0；1.0.9 及更早版本均不兼容资源蜜蜂 13.14.0。

## 兼容模组

以下模组为**可选依赖**。安装后对应功能会自动激活。

| 模组 | 集成内容 |
| --- | --- |
| [Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/mekanism-extras) | 通用机械：扩展 等级工厂（绝对 / 至尊 / 寰宇支配 / 悖论无限） |
| [Evolved Mekanism](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism) | 进化通用机械 等级工厂（超频 / 量子 / 致密 / 多元宇宙 / 创造） |
| [Evolved Mekanism Extras](https://www.curseforge.com/minecraft/mc-mods/evolved-mekanism-extras) | 进化通用机械：扩展 等级工厂（绝对超频 / 至尊量子 / 宇宙致密 / 无限多元） |
| [Mekanism Unleashed](https://www.curseforge.com/minecraft/mc-mods/mekanism-unleashed) | 扩展升级上限 |
| [Mekanism Empowered](https://www.curseforge.com/minecraft/mc-mods/mekanism-empowered) | 强化速度 / 强化能量 / IO 容量 / 自动插入器 / 快速物品插入 / 快速物品弹出升级（离心机 6 种，蜂箱 5 种） |
| [Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2) | 网络连接、物品/流体输出、离心机输入拉取与 ME 网络能量输入 |
| [AppliedFlux](https://www.curseforge.com/minecraft/mc-mods/appliedflux) | ME 网络存储的 FE 作为机器能量来源 |
| Ars Nouveau 5.12.1+ | 魔源生产与原生中继输出 |
| Ars Énergistique 2.1.1-beta+ | 配合 AE2、Ars Nouveau 输出到 ME 魔源存储 |
| [Jade](https://www.curseforge.com/minecraft/mc-mods/jade) | AE2 网络状态悬停显示 + 蜂箱蜜蜂数量 / 生产进度 |
| [Iris Shaders](https://www.curseforge.com/minecraft/mc-mods/irisshaders) | 宇宙渲染光影兼容 |
| [Just Enough Items](https://www.curseforge.com/minecraft/mc-mods/jei) | 配方查看  |
| [KubeJS](https://www.curseforge.com/minecraft/mc-mods/kubejs) | 通过 `MyriadBeeEvents.REGISTER` 在运行时注册蜜蜂配方 |
| [Super Factory Manager](https://www.curseforge.com/minecraft/mc-mods/super-factory-manager) | 通过 NeoForge capability 自动化物品、流体和能量 |
| Just Dire Things Extras | 合并 256x/1024x 机器加速，避免每个虚拟 tick 重复完整管线 |
| Building Gadgets 2 | 客户端方块实体渲染加载时序兼容 |
| Mek Energistics | 为本模组 AE 直连机器保护安装器与目标解析 |

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

服务端配置是模组的核心，管理机器行为，分为三大部分：

#### 平衡性规则

- **全局平衡性配置**：基础 / 悖论无限 / 自定义 — 详见[平衡性预设](#平衡性预设)。在游戏内配置界面点击该配置项，会打开显示每个模式及其描述的选择列表。
- **产量升级等级互斥**（仅自定义直接生效）：禁止产量升级 α/β/γ/Ω 在同一台机器中混装。
- **速度升级等级互斥**（仅自定义直接生效）：禁止时间 I 与时间 II 在同一台机器中混装。
- **离心机产量升级增加产量**（仅自定义直接生效）：开启后离心机产量升级同时增加单次产出；关闭后仅增加并行。无论此项如何，资源蜜蜂原版并行能力始终保留。

#### 通用机械离心机配置

离心机配置按功能分组，共 21 个配置项：

**基础参数**：
- 每_tick 能量消耗：每个处理槽每 tick 的能量消耗（FE）。
- 处理时间：每次操作的基础处理时间（tick）。
- 流体罐容量：流体罐基础容量（mB），工厂版按并行数倍增。
- 流体弹出速率：离心机工厂每 tick 流体弹出速率（mB），范围 1-10240，默认 256。
- 蜜脾块产出倍率：离心蜜脾块时的产出倍数。
- 多流体槽模式：启用后按流体类型动态分配独立槽位。
- 熔炉配方兼容总开关：允许离心机处理电力熔炼炉（SMELTING）配方，默认开启；关闭后每台机器的 GUI 开关无法使用。每台离心机是否兼容熔炉配方由其 GUI 内的独立开关控制，默认关闭。

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
- 启用 AE2 原生能量输入（仅安装 Applied Flux 时出现）：关闭后仅从 AppliedFlux 存储的 FE 提取能量，保护 ME 网络不被抽干。
- 启用 AE2 输入拉取：离心机主动从 ME 网络拉取输入物品。
- 每次拉取最大物品数：1-16384，过大可能增加 CPU 开销。
- 拉取触发间隔：游戏刻，值越大 CPU 开销越低但响应越慢。
- AE2 输入最小页数：AE2 输入过滤窗口最小页数（1-16）。
- AE 能量优先级：优先 AppliedFlux 还是 AE2 原生能量。

**输出槽堆叠倍率**：17 个工厂等级各自的输出槽堆叠倍率，默认从 1× 到 4096×。

**输入槽堆叠倍率**：蜜脾处理成倍产出，输入槽默认倍率为输出槽的 1/4。

**PB 升级上限**：产量升级、时间升级、基因采样升级、蜜脾块升级、稳定性升级的最大安装数量——产量/时间上限受平衡性预设进一步钳制（基础 4，悖论无限 8+）。

**通用机械：扩展 升级上限**：堆叠升级最大数量，2^N 倍并行，仅作用于本模组离心机工厂——受平衡性预设钳制（基础 8，悖论无限 16+）。

**流体罐倍率**：按离心机等级配置流体罐容量倍率。

#### 通用机械蜂箱配置

蜂箱配置镜像离心机结构，并增加蜂箱专属调参：

**基础参数**：
- 蜂箱每_tick 能量消耗：每个处理槽每 tick 的能量消耗（FE），默认 50。
- 蜂箱处理时间：基础处理时间（tick），默认 1200。
- 蜂箱流体罐容量：基础版蜂箱流体罐容量（mB），默认 256,000，工厂版按等级硬编码。

**弹出策略**：与离心机的弹出策略对应，参数名前缀为"蜂箱"。

**输出槽堆叠倍率**：17 个工厂等级各自的输出槽堆叠倍率：
- 基础蜂箱（5 蜜蜂槽，30 输出槽，每页 15）默认 1×
- 高级蜂箱（10 蜜蜂槽，30 输出槽，每页 15）默认 2×
- 精英蜂箱（15 蜜蜂槽，30 输出槽，每页 15）默认 4×
- 终极蜂箱（20 蜜蜂槽，60 输出槽，每页 30）默认 8×
- 通用机械：扩展 绝对 / 至尊 / 寰宇支配 / 悖论无限蜂箱：26 / 30 / 36 / 42 蜜蜂槽，78 / 60 / 72 / 84 输出槽，默认 16× / 32× / 64× / 128×
- 进化通用机械 超频 / 量子 / 致密 / 多元宇宙 / 创造蜂箱：26 / 30 / 36 / 42 / 45 蜜蜂槽，78 / 60 / 72 / 84 / 90 输出槽，默认 16× / 32× / 64× / 128× / 256×
- 进化通用机械：扩展 绝对超频 / 至尊量子 / 宇宙致密 / 无限多元蜂箱：45 / 51 / 55 / 60 蜜蜂槽，90 / 102 / 78 / 84 输出槽，默认 256× / 512× / 1024× / 4096×

**AE2 集成**（仅当 AE2 已安装时注册）：AE2 输出开关 + AppliedFlux 优先级切换 + AE2 原生能量提取开关，与离心机的 AE2 集成对应。

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
7. 在服务端配置中调整**平衡性配置**（基础 / 悖论无限 / 自定义），控制升级等级混装、离心机产量升级行为与升级上限。
8. （可选）通过 **KubeJS** 服务端脚本监听 `MyriadBeeEvents.REGISTER`，在运行时动态注册自定义蜜蜂配方。

进度系统引导前期路线：获得通用机械离心机、首次加工物品、解锁副产物销毁升级配方与终极离心机。

## 构建

```bash
git clone https://github.com/Ayoshiko/productive-bees-genesis.git
cd productive-bees-genesis
./gradlew build
```

本地开发构建产物为 `build/libs/productivebeesgenesis-1.1.0.jar`；1.1.0 尚未发布。

运行 `./gradlew runClient --no-daemon` 会通过仅开发环境使用的 `devExamples` sourceSet 自动加载资源蜜蜂示例，可测试 `example:energy`、`example:hydrogen`、`example:mixed` 等蜂种。可选资源仍需安装对应模组。示例不会打入发布 JAR，`verifyReleaseArtifact` 会检查这一隔离边界。

> 需要 **Java 21**，且需联网从 Cursemaven / Modrinth Maven 下载 Mekanism、Productive Bees、AE2 等依赖。
>
> **开发环境依赖（`libs/` 本地 jar）**：仓库不包含 `libs/` 下的二进制 jar，全新环境编译前需手动放置：
> `productivebees-1.21.1-13.14.0.jar`、`productivelib-1.21.1-0.2.0.jar`、`Mekanism-1.21.1-10.7.19.85.jar`、
> `mekanism_extras-1.21.1-1.4.0.jar`、`Evolved Mekanism-1.21.1-1.2.1-fix3.jar`、`EvolvedMekanismExtras-1.21.1-1.2.1.jar`、
> `jdte-0.5.9-alpha1.jar`、`sponge-mixin.jar`、`jei-1.21.1-neoforge-19.36.0.360.jar`、
> `appliedenergistics2-1.21.1-19.2.17.jar`、`AppliedFlux-1.21-2.1.5-neoforge.jar`、`Jade-1.21.1-NeoForge-15.10.5.jar`、
> `kubejs-neoforge-2101.7.2-build.368.jar`、`rhino-2101.2.7-build.85.jar`。

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
├── command/            仅 OP 可用的运行时开发诊断指令
├── compat/             可选模组集成
│   ├── kubejs/          KubeJS 插件 — MyriadBeeEvents.REGISTER + 配方序列化器
│   ├── mekanism_extras/ 通用机械：扩展 方块、工厂、菜单与委托
│   ├── emextras/        进化通用机械：扩展 方块 / 方块实体注册
│   └── mekenergistics/  Mek Energistics 安装器/目标保护
├── config/             客户端/通用/服务端配置，支持中英文双语
├── datagen/            方块标签、配方、战利品表、语言文件
├── init/               DeferredRegister 注册（方块、物品、方块实体等）
├── item/               自定义物品
├── mek/                通用机械离心机方块、方块实体、配方处理
│   └── ae2/            AE2 网格、输入过滤/拉取、pending 所有权、输出账本、
│                       物品/流体推送、外部存储与能量注入
├── menu/               容器菜单定义
├── mixin/              Mixin 类，含 MixinConfigPlugin 条件加载器
│   ├── accessor/       访问器 mixin
│   ├── ae2/            各机器家族的可选 AE2 宿主适配
│   ├── beehive/        蜂箱库存防抖与缓存 mixin
│   ├── buildinggadgets/ Building Gadgets 方块实体渲染兼容
│   ├── client/         客户端 mixin（蜜蜂颜色、宇宙物品渲染）
│   ├── iris/           Iris 着色器兼容，含 IrisConfigPlugin
│   ├── jdte/           合并加速适配
│   ├── mek/            通用机械离心机/工厂/弹出器 mixin
│   ├── mekenergistics/ Mek Energistics 兼容保护
│   └── recipe/         配方序列化兜底 mixin
├── network/            经校验/限频的 GUI 操作与过滤器/状态同步
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
- **`Ae2GridNodeManager`** / **`Ae2InputPuller`** / **`Ae2InputFilter`**：AE2 节点生命周期，以及公平、有界的蜜脾/SMELTING 发现、过滤、库存保留和 pending 物品所有权。
- **`Ae2OutputPusher`** / **`Ae2OutputLedger`** / **`Ae2FluidPusher`**：事务化物品输出与多流体批量输出，带按键退避和插入成本预算。
- **`Ae2EnergyInjector`**：ME 网络能量注入，支持 5 层优先级与网络保留。
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

本模组没有遥测、统计、崩溃上报或外部数据上传。世界存档会保存资源账户拥有者和 EMC 收款人的 UUID，用于资产归属、权限和入账；资源窗口通过 Minecraft 原生通道同步必要的收款身份与游戏状态。频率限制器使用内存中的玩家身份键。这些数据用于本地游戏玩法，不上传到外部服务。

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
