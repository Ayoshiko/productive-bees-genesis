# Changelog

所有重要变更将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本管理遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> **版本号重新编号说明**（2026-07-04）
>
> 经审查发现 v1.4.0 起的版本号递增不符合 [SemVer](https://semver.org/lang/zh-CN/) 严格规则：
> 主要是 bug 修复的版本错误使用了 MINOR 级别递增。现已按 SemVer 规则重新编号：
>
> | 原版本 | 新版本 | 原因 |
> |--------|--------|------|
> | v1.4.0 | v1.3.4 | 综合审查修复 → PATCH |
> | v1.4.1 | v1.4.0 | 含新功能（ME/EME 颜色） → MINOR |
> | v1.4.2 | v1.4.1 | 代码审查修复 → PATCH |
> | v1.4.3 | v1.5.0 | 含新功能（Jade AE2 状态） → MINOR |
> | v1.5.0 | v1.5.1 | 综合审计修复 → PATCH |
> | v1.6.0 | v1.5.2 | 内部架构重构 → PATCH |
> | v1.7.0 | v1.5.3 | bug 修复 + 安全加固 → PATCH |
>
> v1.5.4 起，所有历史 Release 附带的 JAR 文件名已重新构建，与 Release 版本号严格匹配。
> git tag、GitHub Release 标题、JAR 文件名三处版本号已完全一致。

## [2.0.7-hotfix] - 2026-08-06

v2.0.7-hotfix 修复整合包实测发现的 AE2 线缆兼容崩溃：
服务端机器加载时因 `getGridNode` 接口默认方法冲突崩溃（`IncompatibleClassChangeError`）；
客户端渲染时 ME 工厂蜂箱又因 `getCableConnectionType` 缺失实现崩溃
（`AbstractMethodError`）。现两个方法均由五个接口注入 Mixin 显式实现，
AE2 线缆可正常发现并连接本模组蜂箱/离心机（含 ME / EME 工厂），原有网格连接行为不变。

### 修复

- **AE2 线缆相邻崩溃（CRITICAL）** — `IAe2OutputHost.getGridNode` 原为接口默认方法，与 AE2 `IGridConnectedBlockEntity` 的默认方法冲突；线缆方块 ready 时扫描相邻机器触发类加载校验即崩溃。现改为抽象方法并由五个接口注入 Mixin 显式实现（蜂箱、基础离心机、原版工厂、ME 工厂、EME 工厂），节点查询与方向暴露校验逻辑完全不变
- **ME 工厂蜂箱渲染崩溃（CRITICAL）** — `getCableConnectionType` 同样依赖接口 default，在 ME 工厂蜂箱（`TileEntityExtraMekApiaryFactory`）上触发 `AbstractMethodError: getCableConnectionType`，Sodium 渲染线缆方块时崩溃。现与 `getGridNode` 一致，由五个接口注入 Mixin 显式实现并返回 SMART，消除接口方法表解析问题

### SemVer 合规性

- 本次为单一崩溃修复，延续 2.0.x 维护线定为 **PATCH** 级别（v2.0.7 → v2.0.7-hotfix）

## [2.0.7] - 2026-08-06

v2.0.7 围绕整合包实测反馈继续打磨：让蜂箱与离心机的 PB 升级效果直接跟随资源蜜蜂原版配置，速度/能耗公式改走 Mekanism 运行时入口以承接 Unleashed 与 Empowered 的改动；为工厂补上 PB 原版产量并行倍率与多流体槽预分配；AE2 流体推送重构为"按实际库存推送、按实际接收扣除"的无丢失路径；同时新增 Building Gadgets 2 剪切粘贴与 Mek Energistics 安装器两项兼容修复，并统一了机器掉落物的数据保存方式。所有改动均经过代码审查、单元测试与编译验证。

### 修复

- **AE2 流体推送无丢失重构（CRITICAL）** — 先快照流体罐实际总量并 clamp 请求量，再执行批量推送，最后按 AE2 实际接收量从罐内精确扣除；网络完全拒绝时不再触碰流体罐，彻底消除"先扣减后回填"路径中可能出现的流体丢失与重复推送
- **工厂批量倍率被首个进程独占（MAJOR）** — 工厂各进程共享的 tickMultiplier 改为在入口只消费一次，再为每个进程分别注入，修复高并行工厂只有第一个输入槽按加速倍率处理的问题
- **机器掉落物数据保存统一（MAJOR）** — 蜂箱/离心机掉落物改用 vanilla 标准 `saveToItem` 路径，同时保存自定义 NBT 与 DataComponents，统一镐子挖掘与扳手拆卸的 NBT 结构；`onRemove` 不再提前清空槽位，避免破坏持久化读取到空数据
- **万象创世流体满载重复计算（MAJOR）** — 流体槽满载时在随机选型与物品规划之前快速暂停，避免 256× 加速下每 tick 重做权重分配，同时防止蜂蜜被静默丢弃
- **多流体罐稳定阻塞判定提前（MEDIUM）** — 匹配槽全部满载且达到单流体配额时直接判定为稳定阻塞，避免高并行下每 tick 重做批量输出模拟与二分回退

### 新增

- **PB 原版配置驱动升级效果** — 产量 α/β/γ/Ω 系数与时间升级比例现在运行时读取资源蜜蜂原版配置（`productivityMultiplier[1..4]`、`timeBonus`），玩家修改 PB 配置文件后蜂箱与离心机同步生效，配置未就绪时回退原版默认值
- **MEK 升级公式运行时委托** — 蜂箱/离心机的速度时间倍率与能耗倍率改走 `MekanismUtils` 运行时入口，自动承接 Mekanism Unleashed 与 Mekanism Empowered 的公式 mixin，不再手算绕过
- **PB 原版产量并行倍率** — 工厂与基础离心机按产量升级等级获得 4/8/16/32 并行，与 MEK STACK/JDT 倍率叠加，饱和运算防止极端组合溢出
- **Building Gadgets 2 剪切/粘贴兼容** — 剪切方块粘贴时改用 `loadWithComponents` 完整恢复 DataComponents（MEK 升级、能量、侧面配置等），机器不再出现状态不完整
- **Mek Energistics 安装器守卫** — 阻止 ME 工厂安装器把本模组离心机误判为 ME 电力熔炼炉/熔炼工厂并错误转换

### 性能优化

- **蜂箱 tick 加速真实化** — Tick 加速器重复调用时按倍率推进虚拟 tick，进度与完成节奏真实加速，产出总量与原批处理策略保持一致；能耗与产出计数采用饱和运算防溢出
- **AE2 输入配置缓存** — 拉取速率/间隔配置缓存到状态持有者（每 100 tick 刷新），拉取路径不再逐次读取 NeoForge 配置
- **AE2 流体键按槽缓存** — AEFluidKey 按流体槽缓存并按（流体引用 + 组件哈希）失效，避免高并行工厂每 tick 重复分配
- **AE 批次缓冲去累加** — 同一流体在窗口内保留最大当前库存而非逐 tick 累加，避免把同一批库存重复计算 20 次；总累积量增量维护，不再每 tick 遍历
- **工厂 AE2 拉取输入槽复用** — 基础/ME/EME 工厂直接复用父类稳定输入槽列表，不再每次拉取分配 ArrayList

### 变更

- **模组体积优化** — JAR 排除 datagen 增量哈希缓存（`.cache`），并停止跟踪该缓存目录
- **工作区清理** — 移除研究遗留的空目录与调试输出文件

### SemVer 合规性

- 本次变更以修复、兼容适配与性能优化为主，延续 2.0.x 维护线定为 **PATCH** 级别（v2.0.6 → v2.0.7）

## [2.0.6] - 2026-08-04

v2.0.6 在玩家实测反馈的基础上继续打磨：修复稳定性升级在 ME Extras / EM Extras 高阶离心机工厂中失效、JEI 拖入蜜脾块后渲染缺角甚至不可见、AE 输出状态文字越界等问题；同时补齐了以封存生物琥珀为花朵的蜜蜂（Butcher / Rancher 等）的花朵判定，让 Wanna Bee 真正按琥珀内的生物产出对应掉落物，并把 AE 输入过滤扩展到全部 PB 离心机可处理的蜜脾——现在 Ghostly / Milky / Powdery 蜜脾及蜜脾块、原版蜜脾块、Feywild 蜜脾都能直接拖入标记。蜂箱还新增了「特殊直连通道」开关，可在侧面配置页单独关闭蜂箱到相邻离心机的自动输送。所有改动均经过代码审查、单元测试与编译验证。

### 新增

- **封存生物琥珀花朵支持（Butcher / Rancher 等）** — 适配以琥珀封存实体为花朵的蜜蜂：直接从琥珀物品的 `ENTITY_DATA` 组件读取封存实体 ID，与 PB 数据包声明的实体 ID 或实体标签（支持 `inverseFlower` 与 `!tag` 写法）匹配，全程不实例化实体；沿用花朵有效性缓存，仅在喂食槽内容变化后首次计算
- **Wanna Bee 动态战利品产出** — 机械蜂箱中的 Wanna Bee 现在会读取喂食槽内琥珀封存的生物，按该生物的战利品表产出对应掉落物（如封存凋零的琥珀产出下界之星）；单批最多 128 个代表样本，配合按槽位缓存的战利品上下文，高倍 tick 加速下也不会产生性能尖刺
- **蜂箱特殊直连通道开关** — 在 MEK 侧面配置的物品页新增「D」按钮，可单独关闭蜂箱自动输送到相邻离心机的特殊通道；状态随存档持久化，工厂升级时保留，默认开启以兼容旧存档
- **AE 输入过滤支持更多蜜脾** — Ghostly / Milky / Powdery 蜜脾及其蜜脾块、原版蜜脾块、Feywild 蜜脾均可拖入过滤 GUI 标记并显示正确图标；仅收录 PB 原版离心机/热力离心机可处理的输入，Sugarbag 等由其他模组机器处理的蜜脾不会误入

### 修复

- **稳定性升级在高阶工厂失效** — ME Extras / EM Extras 离心机工厂未传递稳定性加成，导致安装 7 个稳定性升级仍无法让概率产物达到 100%；现统一通过 `PbOutputChance` 读取 PB 原版 `stabilityChanceIncrease` 配置计算加成，与基础离心机行为一致，并增加 NaN/越界输入的防御处理
- **JEI 拖蜜脾块渲染缺角/消失** — 修复过滤 GUI 中 3D 蜜脾块图标缺角甚至完全不可见、JEI 拖拽预览不置顶的问题：保留物品原始深度位置不变，仅在 MEK 高 Z 环境下临时切换深度函数并在渲染后恢复，消除深度精度导致的渲染错误
- **AE 输出状态文字越界** — 移除侧面配置页中溢出的「AE：开/关」状态文字，改为按钮字母颜色直接表达状态（开启绿色、关闭深灰），tooltip 保留完整状态说明
- **GUI 窗口缓存泄漏** — AE 输出与直连通道按钮的静态缓存改为弱引用值，关闭 GUI 后窗口、容器与方块实体可正常回收，长时间游玩不再累积内存

### 变更

- **稳定性概率公式对齐 PB 配置** — `PbRecipeCompleter` 单次与批量路径统一走 `PbOutputChance` 工具类，概率判定行为一致
- **测试覆盖** — 新增 5 组单元测试：稳定性概率计算、AE 批次缓冲时间窗、AE 推送退避窗口、蜜脾过滤白名单、Wanna Bee 批次采样计划
- **发布资料** — 随包附带 1024×1024 模组图标（`productivebeesgenesis.png`）并接入 NeoForge `logoFile` 元数据，新增平台发布清单

### SemVer 合规性

- 本次变更以修复和整合包适配为主，延续 2.0.x 维护线定为 **PATCH** 级别（v2.0.5 → v2.0.6）

## [2.0.5] - 2026-08-02

v2.0.5 是 SemVer PATCH 版本，集中修复玩家实测反馈的多项问题：离心机处理流体蜜脾时偶发卡住、工作台合成升级工厂时蜜蜂与升级数据丢失并引发崩溃、AE2 与全能工具扳手无法拆卸机器、镐子破坏机器时物品复制与数据丢失、蜜脾块离心配方产出多余蜡与原生配方重复显示、Lumber Bee 不按喂食槽产出对应木头、喂食槽花朵名称超出 GUI 边框、普通蜂笼使用后错误返还空蜂笼等。所有修复均经过日志分析、代码审查与编译验证。

### 修复

- **离心机流体推送卡住——退避死循环（CRITICAL）** — 解决离心机处理产物为流体的蜜脾时偶发性停止加工，换一台离心机又正常的问题
  - **问题**：离心机处理流体蜜脾时偶发卡住（机器停止加工），玩家报告为偶发性、换一台离心机又正常工作。日志分析显示 AE2 流体推送退避指数反复在 0→5→6→...→16→0 之间循环，形成无限退避
  - **根因**（三重叠加）：
    1. **缺少网格节点状态检查**：`Ae2FluidPusher` 未检查 Grid Node 是否为 `STATE_ONLINE` 就直接调用 `poweredInsert`（物品推送器 `Ae2OutputPusher` 有此检查）。当节点处于 `OFFLINE`/`NETWORK_BOOTING`/`MISSING_CHANNEL` 时，`poweredInsert` 必然失败并触发 30s 激进退避
    2. **退避重置死循环**：原逻辑中 `anySuccess`（含部分成功）会调用 `recordSuccess()` 将退避指数重置为 0。网格间歇性不稳定时，30s 退避到期后部分成功重置退避，紧接着完全失败又触发 30s 激进退避，形成"30s退避→部分成功→重置→立即失败→30s退避"的无限循环
    3. **误触退避**：`totalRequested` 包含 batch buffer 跨 tick 累积量，但 tank 可能在累积期间已被 Ejector 清空，此时 `totalRequested > 0` 但实际无流体可推送，仍会触发退避
  - **修复**：
    1. 在 TPS 检查后、获取网格前，添加与 `Ae2OutputPusher` 对称的 Grid Node 状态检查：仅 `STATE_ONLINE` 时继续，非 ONLINE 直接返回不触发退避
    2. 新增 `hasLeftover` 标志区分完全成功与部分成功：仅完全成功（`anySuccess && !hasLeftover`）时重置退避；部分成功保持当前退避级别，不重置也不升级
    3. 新增 `totalActualShrunk` 变量替代 `totalRequested` 判断退避触发条件：区分"推送失败"和"tank 已空无需推送"
  - **影响范围**：1 个文件修改（`Ae2FluidPusher.java`）

- **合成升级数据丢失与崩溃（CRITICAL）** — 解决在工作台合成升级蜂箱/离心机工厂时，内部蜜蜂、PB升级、MEK升级丢失，输出槽有物品时无法合成，以及合成结果放入背包后游戏崩溃的问题
  - **问题**：玩家报告合成升级时"除了 MEK 升级都保留了"，随后鼠标移到合成升级后的精英蜂箱工厂上游戏崩溃（`Missing id for entity`）。进一步测试发现输出槽有物品时工作台不显示合成结果
  - **根因**（三重叠加）：
    1. **assemble 覆盖从未执行**：工厂升级配方使用 MEK 的 `MEK_DATA` 序列化器，反序列化时创建的是 `MekanismShapedRecipe` 实例而非本模组的 `ApiaryShapedRecipe`，重写的 `assemble` 从未被调用，蜜蜂/PB升级等存储在 `BLOCK_ENTITY_DATA` 中的自定义 NBT 字段全部丢失
    2. **输出槽守卫返回 EMPTY**：`MekanismShapedRecipe.assemble` 在输入机器输出槽有物品时通过 `ItemRecipeData` 转移失败返回 `EMPTY`，导致输出槽有物品时无法合成
    3. **BLOCK_ENTITY_DATA 缺少 id 字段**：合成转移 `BLOCK_ENTITY_DATA` 时 `remove("id")`，但该组件使用 `CustomData.CODEC_WITH_ID` 校验顶层 `id`（BlockEntityType 注册键），缺失时在物品编码/解码/保存玩家 NBT 时抛 `Missing id for entity` 崩溃
  - **修复**：
    1. 新增自定义配方序列化器 `productivebeesgenesis:apiary_shaped`，反序列化时创建 `ApiaryShapedRecipe` 实例使 `assemble` 覆盖生效；datagen 输出 36 个工厂升级配方 JSON 全部切换至新序列化器
    2. 重写 `assemble`：先调用 `super.assemble` 处理 MEK 标准升级数据，再从 `CraftingInput` 转移/合并 `BLOCK_ENTITY_DATA`（蜜蜂槽取并集、PB升级累加上限截断、其他字段取首个非空）；`super` 返回 EMPTY 时走降级路径，复制输入全部组件（含 `mekanism:upgrades`）再覆盖 `BLOCK_ENTITY_DATA`
    3. `remove("id")` 改为 `putString("id", targetTileId)`，通过 `IHasTileEntity.getTileType()` 解析目标方块 BlockEntityType 注册键，满足 CODEC_WITH_ID 校验
    4. 配合修复：容器注册器能量槽新增 `INTERNAL` 模式允许合成路径插入能量物品；工厂离心机 `parseUpgradeData` 传递新方块槽位给 helper 深拷贝恢复；ME/EME 工厂蜂箱蜜蜂槽容量查询接口供合并时确定目标容量
  - **影响范围**：4 个新增文件 + 21 个文件修改 + 36 个配方 JSON 重新生成

- **AE2/全能工具扳手无法拆卸机器（MAJOR）** — 解决 AE2 赛特斯石英扳手、石英扳手和全能工具扳手模式下 shift+右键无法拆解蜂箱/离心机，只能旋转方向的问题
  - **问题**：所有通用扳手 shift+右键只能旋转机器方向，无法像 MEK 原版配置卡那样拆卸
  - **根因**：omnitools 的 `OmniToolItem.useOn` 总是先调用 `WrenchHandlerRegistry.handle`，其中 `MekanismTransmitterWrenchHandler.canHandle` 对任何暴露 `CONFIGURABLE` 能力的 MEK 方块（包括本模组机器）返回 true，其 `handle` 在服务端调用 `IConfigurable.onSneakRightClick` 打开配置 UI 并返回 consumesAction，先于 `Block.useItemOn` 执行，导致方块内的 shift+扳手拆卸分支从未触发
  - **修复**：新增 `ApiaryWrenchDismantleHandler` 监听 `PlayerInteractEvent.RightClickBlock`（HIGHEST 优先级，仅服务端），在 shift+扳手+本模组机器时直接调用 `WorldUtils.dismantleBlock` 并取消事件；客户端不取消事件保证 `ServerboundUseItemOnPacket` 正常发送。同时新增 `WrenchCapabilityHelper` 统一扳手工具判定（优先 MEK 工具，其次 `c:tools/wrench` 标签 fallback）
  - **影响范围**：2 个新增文件 + 2 个文件修改

- **方块破坏物品复制与数据丢失（MAJOR）** — 解决用镐子破坏或创造模式左键拆除蜂箱/离心机时，部分输出槽物品作为实体弹出而另一部分保留在方块内（物品复制），以及工厂安装器升级时输出槽有物品导致物品弹出的问题
  - **问题**：镐子破坏蜂箱/离心机时部分输出槽物品掉落为实体、部分保留在方块内；工厂安装器升级带物品的机器时物品弹出；破坏后方块内蜜蜂/喂食槽/PB升级数据丢失
  - **根因**：`saveAllItemsForDrop` 仅在 `getDrops` 中调用，但创造模式左键破坏不走 `getDrops`，槽位未清空，`setRemoved` 触发 Ejector 组件 `popResource` 将物品弹出世界（物品复制）。同时镐子破坏路径下产物输出槽/蜂笼 I/O 槽/能量槽未通过 `saveCustomData` 持久化，破坏后丢失
  - **修复**：
    1. 覆写 `onRemove` 在 `tile.blockRemoved()` 前调用 `saveAllItemsForDrop` 清空槽位，覆盖创造/生存/扳手拆卸全部路径
    2. `getUpgradeData` 构建升级数据后立即清空旧方块槽位，防止 `setRemoved` 重复弹出
    3. 移除 `ApiaryOutputBuffer.dumpToWorld` 和 `setRemoved` 中的 popResource 兜底（消除唯一 popResource 源）
    4. `saveCustomData` 追加冗余序列化产物输出槽/蜂笼 I/O 槽/能量槽到自定义 NBT 键，作为 `ITEM_CONTAINER` 组件的备份
  - **影响范围**：8 个文件修改

- **蜜脾块离心配方系统重构（MAJOR）** — 解决蜜脾块离心产出多余蜡、JEI 显示重复配方且产物错误、特殊蜜脾块无离心配方的问题
  - **问题**：恶魂蜜脾块/烈焰蜜蜂蜜脾块等在 JEI 中显示重复配方且产物与 PB 原版不一致（如 4 倍产物而非 100mB 蜂蜜）；幽匿/牛奶等特殊蜜脾块无离心配方显示
  - **根因**：
    1. 派生蜜脾块配方时未过滤 `c:waxes` 标签的 `ChancedOutput`，导致离心额外产出蜡
    2. PB 原版有独立的蜜脾块配方（如 `comb_blazing.json` 产出 blaze_rod），与单蜜脾配方产物不同，原 JEI 派生逻辑为所有 bee_type 派生蜜脾块配方，导致双显示且实际离心走派生路径产出错误
    3. 4 种特殊蜜脾块（ghostly/milky/powdery/vanilla）无 bee_type，原索引和 JEI 不处理
  - **修复**：
    1. `CentrifugeRecipeIndex` 派生蜜脾块配方时静态过滤 `c:waxes` 标签的 `ChancedOutput`，仅保留矿物和蜂蜜
    2. 两遍扫描消除 HashMap 迭代顺序依赖：第一遍收集有原生蜜脾块配方的 bee_type 集合，第二遍派生时跳过这些 bee_type
    3. 新增 `SpecialCombBlockRecipeHandler` 和特殊蜜脾块索引，支持 ghostly/milky/powdery/vanilla 蜜脾块的 O(1) 配方查找
    4. `PbRecipeFinder` 新增特殊蜜脾块配方查找分支；JEI 新增特殊蜜脾块离心配方注册
  - **影响范围**：1 个新增文件 + 3 个文件修改

- **Lumber Bee 不按喂食槽产出对应木头（MEDIUM）** — 解决 Lumber Bee 无论喂食槽放什么木头都产出相同产物的问题
  - **问题**：喂食槽放入不同木头（橡木/白桦/樱花等），Lumber Bee 产出不随喂食槽变化
  - **根因**：`BeeProduceProcessor` 缓存了固定产物列表，未根据喂食槽内容动态推断 multi-flower 蜜蜂（lumber_bee/quarry_bee/dye_bee）的产物
  - **修复**：新增 `FeederSlotManager` 查询 API 获取喂食槽物品类型；`MultiFlowerBeeAdapter` 根据喂食槽物品推断对应产物；`BeeProduceProcessor` 新增 `getCachedProduce` 重载对 multi-flower 蜜蜂走喂食槽推断路径跳过缓存
  - **影响范围**：4 个文件修改

- **喂食槽花朵名称超出 GUI 边框（MEDIUM）** — 解决基础/高级/精英蜂箱工厂喂食槽放满 9 种不同物品时，花朵名称文字超出 UI 边框的问题
  - **问题**：喂食槽 TAB 中放入 9 种不同物品后，信息面板的花朵名称列表高度超出窗口边界
  - **根因**：`GuiFeederWindow` 窗口高度固定等于网格高度，未根据花朵数量动态计算信息面板所需高度
  - **修复**：窗口高度改为 `标题 + max(网格高度, 信息面板内容高度) + 底部提示`，信息面板高度按花朵数量动态计算；`GuiFeederTab` 使用实际窗口宽度计算居中定位
  - **影响范围**：2 个文件修改

- **普通蜂笼使用后错误返还空蜂笼（MINOR）** — 解决普通蜂笼使用后与 PB 原版行为不一致的问题
  - **问题**：本模组机械蜂箱中使用普通蜂笼装入/释放蜜蜂后，错误地返还空蜂笼，与 PB 原版一次性消耗行为冲突
  - **根因**：`ApiaryCageHandler` 对所有蜂笼统一返还空蜂笼到 `cageOutSlot`，未区分普通蜂笼（一次性）和加固蜂笼（可反复使用）
  - **修复**：通过 `is(ModItems.STURDY_BEE_CAGE.get())` 区分，仅加固蜂笼走返还路径，普通蜂笼仅消耗不返还
  - **影响范围**：1 个文件修改（`ApiaryCageHandler.java`）

- **PB 升级翻译对齐整合包** — 将产量α/β/γ/ω、基因采样器等翻译与整合包汉化标准对齐
  - **影响范围**：1 个文件修改（`zh_cn.json`）

### SemVer 合规性

- **版本号定级**：本次发布全部为 bug 修复（9 项：流体推送退避死循环、合成升级数据丢失与崩溃、扳手无法拆卸、方块破坏物品复制、蜜脾块离心配方系统重构、Lumber Bee 产物、喂食槽 GUI 超边框、蜂笼返还行为、翻译对齐），无新功能，无 BREAKING 变更。按 [SemVer](https://semver.org/lang/zh-CN/) 严格规则定为 **PATCH** 级别（v2.0.4 → v2.0.5）

## [2.0.4] - 2026-07-31

v2.0.4 是 SemVer PATCH 版本，修复服务端实测发现的 5 个独立 bug：蜂箱速度升级卸载后工作时间不可逆递减、概率产物被错误地变为必产物、时间流体蜜脾错误产出蜂蜜、蜜蜂属性 tooltip 翻译与整合包不一致、蜂箱产物在离心机阻塞时被困缓冲区。所有修复均基于设计文档 v2.2.0，并经过代码审查与编译验证。

### 修复

- **蜂箱速度升级"不可逆递减"（CRITICAL）** — 解决安装 MEK 速度升级后蜜蜂工作时间指数衰减，卸载升级后仍无法恢复的问题
  - **问题**：安装 1 个速度升级后工作时间从 1200 tick 暴跌到 27 tick，再装一个降到 7 tick，卸载后仍保持 7 tick 无法恢复
  - **根因**：`BeeSlotTickProcessor.tick()` 将已乘以倍率的 `adjustedMinTicks` 写回 `slot.minOccupationTicks`，下一 tick 又把它当作基础值再次乘以倍率，形成指数衰减；该污染值还通过 NBT 持久化，导致卸载升级后 `timeMultiplier=1.0` 只能保持污染值不变
  - **修复**：
    1. `BeeSlot` 新增 `baseMinOccupationTicks` 字段存储基础值，`minOccupationTicks` 仅存显示用调整值
    2. `BeeSlotTickProcessor` 从 `baseMinOccupationTicks` 读取基础值计算调整时间，永不回写基础值
    3. `ApiarySlotSerializer` 新增 `base_min_occupation_ticks` NBT 字段，老存档迁移时上界从配置 `apiaryProcessingTime` 读取（默认 1200），避免被 bug 污染的较低值被误迁移
    4. `ApiaryCageHandler` 装入新蜜蜂时重置 `baseMinOccupationTicks=0`，触发 fallback 到配置默认值（与 PB 原版行为一致）
  - **影响范围**：4 个文件修改（`BeeSlot.java`、`BeeSlotTickProcessor.java`、`ApiarySlotSerializer.java`、`ApiaryCageHandler.java`）

- **概率产物变必产物（CRITICAL）** — 解决幽匿蜜蜂 5% 概率产花粉球变为 100% 必产 10 个的问题
  - **问题**：机械蜂箱中的幽匿蜜蜂每次产出必出 10 个花粉球，而非 PB 原版的 5% 概率
  - **根因**：`BeeInfoHelper.getBeeProduce` 使用 `chancedOutput.max()` 完全忽略 `chance` 字段，概率产物变为 100% 必产出且取最大值；离心机路径 `PbRecipeCompleter` 正确实现了概率检查，机械蜂箱路径缺失
  - **修复**：
    1. 新增 `BeeProduceBatchSampler` 类，专门负责机械蜂箱批量概率采样，与离心机路径保持算法一致
    2. 单次产出走 Bernoulli 概率检查，批量场景用 Binomial + CLT 近似替代 N 次独立判定
    3. `BeeProduceProcessor` 缓存类型从 `List<ItemStack>` 改为 `Map<ItemStack, ChancedOutput>`，缓存配方原始数据而非随机结果
    4. 保底机制确保低概率产物在批量场景下仍有机会产出
  - **影响范围**：3 个文件修改 + 1 个新增文件（`BeeProduceBatchSampler.java`、`BeeProduceProcessor.java`、`BeeInfoHelper.java`）

- **时间流体蜜脾错误产出蜂蜜（MAJOR）** — 解决时间流体蜜脾在机械蜂箱中产出蜂蜜，与 PB 原版配方冲突的问题
  - **问题**：JDT 时间流体蜜脾在机械蜂箱中产出蜂蜜，但 PB 原版该蜜脾应产出时间流体（由离心机处理）
  - **根因**：`BeeProduceProcessor` 硬编码 250mB 蜂蜜无条件注入所有蜜蜂，与 PB 原版蜂箱通过 `beeReleasePostAction` 硬编码注入蜂蜜的行为一致，但忽略了非蜂蜜流体蜜蜂的存在
  - **修复**：
    1. 新增 `BeeFluidOutputResolver` 类，从蜜蜂的离心机配方推断流体输出类型
    2. 流体为蜂蜜：返回 `FluidStack(honey, 250)`，机械蜂箱注入蜂蜜
    3. 流体为其他（如时间流体）：返回 `EMPTY`，不注入蜂蜜，该流体由离心机处理时产出
    4. 无离心配方：默认返回蜂蜜（向后兼容）
    5. `BeeProduceProcessor` 移除 `HONEY_FLUID_AMOUNT_PER_PRODUCE = 250` 硬编码常量
  - **影响范围**：2 个文件修改 + 1 个新增文件（`BeeFluidOutputResolver.java`、`BeeProduceProcessor.java`）

- **蜜蜂属性 tooltip 翻译与整合包不一致（MEDIUM）** — 修正 14 处蜜蜂属性翻译与宁纳汉化标准的差异
  - **问题**：蜜蜂属性 tooltip（产量、天气适应性、昼夜习性等）翻译与整合包资源蜜蜂汉化不一致，玩家难以对照
  - **修复**：修改 `zh_cn.json` 14 处翻译，与整合包标准对齐
    - `productivity.normal`：普通 → 正常
    - `productivity.medium`：中等 → 中
    - `productivity.very_high`：极高 → 很高
    - `endurance.weak`：虚弱 → 弱
    - `endurance.normal`：普通 → 正常
    - `endurance.medium`：中等 → 中
    - `endurance.strong`：强健 → 强
    - `temper.passive`：被动 → 友好
    - `temper.hostile`：敌对 → 敌意
    - `behavior.diurnal`：日行 → 昼行性
    - `behavior.nocturnal`：夜行 → 夜行性
    - `behavior.metaturnal`：全天 → 昼夜双行性
    - `weather_tolerance.rain`：雨天 → 雨
    - `weather_tolerance.any`：任意 → 任何
  - **影响范围**：1 个文件修改（`zh_cn.json`）

- **蜂箱产物被困缓冲区（MAJOR）** — 解决蜂箱紧挨离心机且离心机输出槽阻塞时，蜂箱产物无法弹出或推送到 AE 网络的问题
  - **问题**：蜂箱紧挨离心机时，离心机输出槽阻塞会导致蜂箱输出槽满载，缓冲区持续积压，产物被困无法弹出，即使开启 MEK 弹出开关也无效
  - **根因**：`ApiaryDirectEjectHandler` 仅处理蜂箱输出槽，不处理 `ApiaryOutputBuffer` 缓冲区；缓冲区退避机制未检测网络状态，导致无效重试和日志刷屏
  - **修复**（组合修复，三管齐下 + AE 协同）：
    1. **缓冲区直接转移**：`ApiaryDirectEjectHandler` 检测到离心机相邻且输入槽有空间时，除了从输出槽转移，也从缓冲区转移物品，绕过"缓冲区→蜂箱输出槽→离心机"的两跳路径
    2. **退避重置**：成功转移后主动调用 `ApiaryOutputBuffer.resetBackoff()` 立即下 tick 重试，避免最长 8 tick 退避延迟
    3. **阻塞检测 + fallback**：连续 20 tick（1秒）无法转移到离心机时，fallback 到 MEK Ejector 弹到其他方向容器
    4. **AE 协同**：fallback 后 AE2 推送（`pushOutputs`）仍会执行，若 AE 开启则优先通过 AE 推送，AE 失败再由 MEK Ejector 兜底
  - **影响范围**：2 个文件修改（`ApiaryDirectEjectHandler.java`、`ApiaryOutputBuffer.java`）

### 性能优化

- **ApiaryOutputBuffer 独立预扫描数组** — 解决离心机输入槽与蜂箱输出槽数不同时反复扩容的问题
  - **问题**：`tryRedistributeToExternalSlots` 与 `tickRedistribute` 共享同一套预扫描数组，但两者槽位数不同（蜂箱 9 槽 vs 离心机 19 槽），导致每 tick 反复触发数组重新分配（3×2=6 个数组），256× 加速下加剧 GC 压力
  - **修复**：为 `tryRedistributeToExternalSlots` 分配独立的 `reusableExternalStacks/Counts/Limits` 数组，两套数组各自按目标槽位数稳定复用，零扩容
  - **影响范围**：1 个文件修改（`ApiaryOutputBuffer.java`）

### SemVer 合规性

- **版本号定级**：本次发布全部为 bug 修复（5 项：速度升级不可逆、概率产物变必产物、蜂蜜流体硬编码、翻译不一致、虚拟槽位吞没产物）+ 性能优化（1 项：独立预扫描数组），无新功能，无 BREAKING 变更。按 [SemVer](https://semver.org/lang/zh-CN/) 严格规则定为 **PATCH** 级别（v2.0.3 → v2.0.4）

## [2.0.3] - 2026-07-31

v2.0.3 是 SemVer PATCH 版本，修复专用服务器客户端无法放入蜜脾、任意蜜脾块无视配方校验、网络包 IDOR 类权限提升漏洞、服务端离心配方索引为空、蜂箱 tooltip 工作进度显示异常等多个服务端兼容性和安全问题，并针对满升级+高加速场景进行了 Spark 热点分析驱动的深度性能优化。

### 修复

- **专用服务器客户端无法放入蜜脾（CRITICAL）** — 解决专用服务器环境下客户端无法将蜜脾/蜜脾块放入离心机输入槽的问题
  - **问题**：`CentrifugeRecipeIndex` 作为 static 字段，仅在 `ServerLifecycleHooks.getCurrentServer() != null` 时重建。专用服务器客户端无本地服务器，`getCurrentServer()` 返回 null，索引永远为 `EMPTY`，客户端 `containsRecipe` 校验失败
  - **根因**：`onTagsReload` 的服务端守卫在专用服务器客户端为 false，跳过 `CentrifugeRecipeIndex.rebuild`
  - **修复**：
    1. `CentrifugeRecipeIndex.rebuild` 改为接受 `RecipeManager` 而非 `ServerLevel`，解除对服务端的硬依赖
    2. `onTagsReload` 中，客户端通过 `FMLEnvironment.dist.isClient()` 守卫 + 反射调用 `ProductiveBeesGenesisClient.rebuildCentrifugeIndex()`，从 `ClientLevel` 获取 `RecipeManager` 重建索引
    3. 客户端 `ModConfig.SERVER` 访问用 try-catch 降级到默认值 4，避免配置未同步时崩溃
  - **影响范围**：2 个文件修改（`CentrifugeRecipeIndex.java`、`ProductiveBeesGenesis.java`）

- **任意蜜脾块无视配方校验放入并产出错误结果（CRITICAL）** — 解决输出槽有产物时，shift 左键任意蜜脾块都能放入输入槽，无视配方匹配，并以输出槽的物品为产物进行产出的问题
  - **问题**：`containsRecipe` 中 `super.containsRecipe(input)` 匹配了 SMELTING 配方（`c:honeycombs` 标签），导致任意 PB 蜜脾/蜜脾块都能通过校验。随后 `getRecipe` 返回 SMELTING 配方，产出与输入蜜脾不匹配的错误结果
  - **根因**：modularbees 为 `c:honeycombs` 标签注册了熔炉配方，SMELTING 配方缓存会匹配所有带该标签的物品，绕过 PB CentrifugeRecipe 校验
  - **修复**：
    1. `IMekCentrifugeTile` 新增 `productivebeesgenesis$isPbCombInput` default 方法，识别 PB 蜜脾（带 `BEE_TYPE` 组件）和蜜脾块（`CONFIGURABLE_COMB_BLOCK` 物品）
    2. `TileEntityMekCentrifuge.containsRecipe` 对 PB 蜜脾/蜜脾块强制只检查 PB 配方，跳过 SMELTING
    3. `IFactoryPbDelegateAccess.productivebeesgenesis$isValidInput` 同步修复，工厂版离心机也强制 PB 蜜脾走 PB 配方路径
    4. 非 PB 蜜脾（如 modularbees）走原逻辑，允许 SMELTING 处理
  - **影响范围**：3 个文件修改（`IMekCentrifugeTile.java`、`TileEntityMekCentrifuge.java`、`IFactoryPbDelegateAccess.java`）

- **网络包 IDOR 类权限提升漏洞（CRITICAL）** — 修复 10 个服务端 Payload Handler 缺失或条件性容器位置一致性校验的问题
  - **问题**：恶意客户端可打开任意容器（如工作台、背包）后，在 8 格交互距离内远程操作他人方块的 AE2 开关、过滤器、蜜蜂槽位、蜂笼操作和 PB 升级卸载
  - **根因**：
    1. AE2 系列 7 个 Handler（`handleCycleAeOutput`、`handleToggleAeInput`、`handleToggleAeInputNbtIgnore`、`handleCycleAeInputFilterMode`、`handleSetAeInputFilterEntry`、`handleToggleAeInputPreciseMode`、`handleOpenAeInputConfig`）完全缺失容器位置一致性校验
    2. Apiary 系列 3 个 Handler（`handleApiarySelectBee`、`handleApiaryCageOperation`、`handlePbUpgradeExtract`）使用条件性校验（`instanceof MekanismTileContainer` 为 false 时跳过校验），存在绕过路径
  - **修复**：
    1. AE2 系列：新增 `validateContainerMatch` 辅助方法，7 个 Handler 统一调用，强制要求 `containerMenu` 为 `MekanismTileContainer<?>` 且坐标一致
    2. Apiary 系列：将条件性校验改为强制校验，`containerMenu` 非 `MekanismTileContainer` 时直接拒绝
    3. `handleApiaryToggleSorting` 校验顺序调整，`level()` null 检查提前到容器校验之前
  - **影响范围**：2 个文件修改（`ApiaryPayloadHandlers.java`、`Ae2PayloadHandlers.java`）

- **服务端离心配方索引为空（CRITICAL）** — 解决服务端启动后 `CentrifugeRecipeIndex` 为空，导致所有配方查找走 FALLBACK 全量遍历路径的问题
  - **问题**：`onTagsReload` 触发时 `ServerLifecycleHooks.getCurrentServer()` 可能返回 null（服务器启动早期阶段），服务端环境跳过索引重建
  - **修复**：在 `BeeRecipeReloader.overrideRecipesInternal()` 成功后调用 `CentrifugeRecipeIndex.rebuild(recipeManager)`，确保配方重载完成后索引必定重建
  - **影响范围**：1 个文件修改（`BeeRecipeReloader.java`）

- **蜂箱 tooltip 工作进度显示 300/0 tick（MEDIUM）** — 解决蜂箱内部 tooltip 中蜜蜂工作进度显示为 `300/0 tick（0%）`、工作 tick 上限为 0 的问题
  - **问题**：`BeeSlotTickProcessor` 计算 `adjustedMinTicks` 后未同步回 `BeeSlot.setMinOccupationTicks()`，tooltip 始终读取初始值 0
  - **修复**：在 `BeeSlotTickProcessor` 推进计时阶段，将计算后的 `adjustedMinTicks` 同步到 `slot.setMinOccupationTicks()`，确保 tooltip 显示正确的工作上限
  - **影响范围**：1 个文件修改（`BeeSlotTickProcessor.java`）

- **客户端 Container 构造时潜在 NPE（MEDIUM）** — 修复 ME/EME 工厂离心机和基础离心机 Container 在客户端构造时 PB 升级槽位可能为 null 导致的崩溃
  - **问题**：`addSlots()` 直接调用 `tile.getPbUpgradeInputSlot().createContainerSlot()`，若 `getPbUpgradeInputSlot()` 返回 null（客户端 Container 构造时 `pbUpgradeDelegate` 可能尚未初始化）则 NPE
  - **修复**：在 `addSlots()` 中添加 null 守卫，槽位为 null 时记录 warn 日志并跳过虚拟槽注册
  - **影响范围**：3 个文件修改（`MekCentrifugeContainer.java`、`ExtraMekCentrifugeFactoryContainer.java`、`EMExtraMekCentrifugeFactoryContainer.java`）

- **PB 升级插件中文翻译调整** — 将"时间"/"时间 II"调整为"速度"/"速度+"，从玩家视角理解"减少生产时间=提升生产速度"
  - **影响范围**：1 个文件修改（`zh_cn.json`）

- **移除临时调试日志** — 移除 v2.0.2 添加的 `[DEBUG-FIND]` 诊断日志
  - **问题**：v2.0.2 为诊断专用服务器问题添加的临时日志已确认根因，无需保留
  - **修复**：移除 `ProductiveBeesGenesis`、`CentrifugeRecipeIndex`、`TileEntityMekCentrifuge`、`PbRecipeFinder` 中的所有 `[DEBUG-FIND]` 日志
  - **影响范围**：4 个文件修改

### 安全

- 统一所有服务端 Payload Handler 的容器一致性校验策略，与 `handleApiaryToggleSorting` 的强制校验模式保持一致
- `handleSetAeInputFilterEntry` 的 `CLEAR` 操作特别危险（可清空他人过滤器），现已强制容器校验

### 性能优化

基于 Spark 性能分析报告（满升级+256×加速场景，MSPT max=54.6ms）进行的深度优化：

- **蜂箱产物缓冲区退避机制** — 解决 v2.0.2 新增的 `ApiaryOutputBuffer.tickRedistribute` 每 tick 无效重试 `insertItem` 的问题
  - **问题**：输出槽全满时每 tick 重复调用 `insertItem`（内部每次查询 `getLimit`），256×加速下加剧开销
  - **修复**：输出槽全满时递增退避计数器（1→2→...→8 tick），退避期内直接返回，开销仅字段比较
  - **预扫描直写**：与 `distributeToOutput` 直写模式一致，先一次遍历获取所有槽位状态，用 `setStack` 替代 `insertItem`，将 `getLimit` 查询从 N×M 次降为 M 次
  - **加速模式降频**：256×加速模式（`skipBeeProcessing=true`）下 `tickRedistribute` 调用频率降为每 4 tick 一次，减少 75% 无效调用
  - **影响范围**：2 个文件修改（`ApiaryOutputBuffer.java`、`ApiaryTickHandler.java`）

- **蜂箱批量产出动态 flush 阈值** — 解决满升级时每 10 tick 一次的批量 flush 导致 MSPT 周期性尖刺的问题
  - **问题**：固定 `BATCH_FLUSH_INTERVAL=10` 在满升级+256×加速时，单次 flush 累积产出量巨大（可达数百次×N蜜蜂），瞬间 `insertItem` 调用量是单 tick 的 10×
  - **修复**：添加 `FLUSH_ACCUMULATION_THRESHOLD=64` 累积量阈值，达到阈值时提前 flush，将大批量拆为小批量，平滑 flush 负载到多个 tick
  - **影响范围**：1 个文件修改（`BeeSlotTickProcessor.java`）

- **ItemStack 比较快速筛选** — 减少 `isSameItemSameComponents` 的 `PatchedDataComponentMap.equals` 全量比较开销
  - **问题**：批量 flush 时每个待插入栈×每个输出槽都调用 `isSameItemSameComponents`，内部涉及 DataComponentMap 全量比较
  - **修复**：先 `getItem() ==` 比较（O(1) 指针比较），仅 Item 相同时才进入组件比较，短路求值确保不同 Item 直接跳过
  - **影响范围**：2 个文件修改（`BeeProduceProcessor.java`、`ApiaryOutputBuffer.java`）

## [2.0.2] - 2026-07-30

v2.0.2 是 SemVer PATCH 版本，修复两位玩家独立报告的"工具挖掘等级失效"CRITICAL bug（标签加载失败级联影响 paxel/hammer/drill/aio 等工具），以及战利品表解析失败、服务端 Mixin 降级日志噪音、蜂箱产物丢失等问题，并新增蜜蜂 productivity 基因应用。

### 修复

- **工具挖掘等级失效（CRITICAL）** — 解决未安装 EM/ME/EME 附属模组时 `minecraft:mineable/pickaxe` 标签加载失败，级联影响所有 paxel/hammer/drill/aio/staff 工具标签，导致下界合金多功能工具等挖掘速度异常
  - **问题**：`ModBlockTagsProvider` 为 EM/ME/EME 条件注册的工厂方块生成必需标签条目，未安装对应模组时方块 ID 未注册，整个 `mineable/pickaxe` 标签加载失败
  - **根因**：必需条目（`required:true`）引用未注册方块 ID 时，原版标签解析器拒绝整个标签，级联影响所有引用 `#minecraft:mineable/pickaxe` 的工具标签（cucumber paxel、mekanismtools paxel、ftbstuff hammer、actuallyadditions drill/aio、draconicevolution staff）
  - **修复**：使用 `addOptional(id)` 为条件方块生成可选条目（`required:false`），未注册时静默跳过，不影响标签整体加载
  - **影响范围**：1 个文件修改（`ModBlockTagsProvider.java`）

- **战利品表解析失败** — 解决未安装 EM/ME/EME 附属模组时战利品表引用未注册物品 ID 导致解析错误
  - **问题**：`ModLootTables` 无条件为所有方块生成 dropSelf 战利品表，EM/ME/EME 方块的 BlockItem 未注册时触发 `Unknown registry key` 错误
  - **修复**：
    1. `ModLootTables` 过滤 EM/ME/EME 方块，仅为基础方块生成无条件战利品表
    2. 新建 `ConditionalBlockLootProvider` 为 EM/ME/EME 方块生成带 `neoforge:mod_loaded` 条件的 dropSelf 战利品表 JSON
    3. `ProductiveBeesGenesis` 注册新 provider 到数据生成器
  - **影响范围**：3 个文件修改 + 1 个新增文件

- **蜜脾块 hoe 标签错误** — 移除 `infinitycreation_comb_block` 的 `mineable/hoe` 标签
  - **问题**：蜜脾块为金属/装饰方块，锄不应作为有效挖掘工具
  - **修复**：统一加入 `mineable/pickaxe`，不再为任何方块添加 hoe 标签

- **服务端 Mixin 降级日志噪音** — 优化 `PbUpgradeInventorySlot` 服务端 Mixin 降级时的日志输出
  - **问题**：服务端首次放置蜂箱时 `static {}` 块 ClassCastException 打印 40+ 行堆栈 WARN
  - **修复**：WARN 不再带异常堆栈（从 40+ 行降为 1 行），堆栈信息降级到 DEBUG 级别
  - **影响范围**：1 个文件修改（`PbUpgradeInventorySlot.java`）

- **蜂箱产物丢失** — 解决输出槽满载时剩余产物被静默丢弃的问题
  - **问题**：`BeeProduceProcessor.distributeToOutput` 输出槽满载时直接丢弃剩余产物，导致高产量蜜蜂产物损失
  - **修复**：
    1. 新建 `ApiaryOutputBuffer` 缓存溢出产物，每 tick 重试注入输出槽（FIFO）
    2. `distributeToOutput` 返回 `List<ItemStack>` 剩余产物，`processBatchProduce` 送入缓冲区
    3. `ApiaryTickHandler.onUpdateServer` 每 tick 调用 `tickRedistribute`（Tick 加速模式下也执行）
    4. `ApiaryNbtSerializer` 序列化/反序列化缓冲区状态（向后兼容）
    5. 方块破坏时 `dumpToWorld` 掉落缓冲产物（区块卸载时跳过，避免重复）
    6. `getDrops` 保存 NBT 后清除缓冲区，避免 `dumpToWorld` 与 `BLOCK_ENTITY_DATA` 重复掉落
    7. `ArrayDeque` 替代 `ArrayList` 实现 FIFO 淘汰 O(1)，复用 `remainingBuffer` 避免每 tick 分配
  - **设计原则**：SRP（缓冲区独立类）、线程安全（synchronized）、内存安全（MAX_BUFFER_GROUPS=64 上限）、性能优化（O(1) 淘汰 + 零分配重试）
  - **影响范围**：6 个文件修改 + 1 个新增文件

### 新增

- **蜜蜂 productivity 基因应用** — 应用 PB 原版第五层公式提升蜂箱产物产量
  - **背景**：机械蜂箱此前不应用蜜蜂 productivity 基因，高纯度蜜蜂与普通蜜蜂产量相同
  - **公式**：`finalMultiplier = upgradeMultiplier × (1 + 0.2 × purity)`
  - **实现**：
    1. `BeeSlot` 新增 `cachedProductivityPurity` 字段（volatile），从 `beeData` NBT 解析 purity 值并缓存
    2. `setBeeData` 时重置缓存
    3. `BeeProduceProcessor.buildAdjustedItems` 应用第五层公式
    4. `processBatchProduce` 计算同组蜜蜂加权平均纯度
  - **NBT 路径**：`neoforge:attachments.productivebees:attributes_handler.attributes.productivebees:productivity.purity`
  - **影响范围**：2 个文件修改（`BeeSlot.java`、`BeeProduceProcessor.java`）

- **蜜蜂属性应用设计文档** — 记录 BEHAVIOR/WEATHER_TOLERANCE 属性的未来实现设计
  - **内容**：PB 原版逻辑分析、机械蜂箱差异、类设计（`BeeWorkConditionChecker`、`WAITING_CONDITION` 状态）、NBT 路径、tick 流程修改、GUI 显示、边界处理、性能考虑、测试用例
  - **本迭代不实现代码**，作为下一迭代参考
  - **影响范围**：1 个新增文件（`docs/bee-attributes-future-design.md`）

## [2.0.1] - 2026-07-30

v2.0.1 是 SemVer PATCH 版本，修复 v2.0.0 发布后发现的 EME/ME 附属模组未安装时的启动崩溃问题、蜂箱工厂 EM 等级翻译错误，并将 EME 布局参数和 GUI 类迁移至 compat 隔离包以强化可选依赖隔离规范。

### 修复

- **EME/ME 附属模组未安装时启动崩溃** — 解决未安装 Evolved Mekanism Extras / MekanismExtras 时 NoClassDefFoundError 导致游戏启动失败的问题
  - **问题**：`ModMenuTypes.java` 在静态字段中直接引用 EME/ME 的 TileEntity 类，模组加载阶段即触发类加载，未安装对应附属时抛出 `NoClassDefFoundError`
  - **根因**：静态字段在类加载时立即解析，不受运行时守卫保护，违反了可选依赖隔离规范
  - **修复**：
    1. 从 `ModMenuTypes.java` 移除所有 EME/ME 静态字段
    2. 新建 `compat/emextras/EMEMenuTypeRegistration.java` 和 `compat/mekanism_extras/MEMenuTypeRegistration.java` 隔离类，承载 EME/ME MenuType 注册逻辑
    3. 在 `EMECompatLoader.java` / `MECompatLoader.java` 中添加 `registerCentrifugeMenuType()` 方法，内部通过 `MekCompatHooks.isXxxLoaded()` 守卫
    4. `MekCentrifugeEMEBlockType.java` / `MekCentrifugeMEBlockType.java` 改为引用 compat 包的 Holder
    5. `ProductiveBeesGenesis.java` 在工厂注册流程末尾调用 compat loader 的注册方法
    6. `ProductiveBeesGenesisClient.java` 在 Screen 注册处添加 `isXxxLoaded()` 守卫
  - **设计原则**：SRP（compat 包专注单一附属的注册）、LoD（基础类不直接引用可选依赖类）、类加载安全（JVM 延迟加载隔离类）
  - **影响范围**：9 个文件修改 + 2 个新增文件

- **蜂箱工厂 EM 等级中文翻译错误** — 修正扩展进化蜂箱工厂等级的中文译名
  - **问题**：`cosmic_extra_mek_apiary_factory` 显示为"寰宇通用机械蜂箱工厂"，`infinite_extra_mek_apiary_factory` 显示为"无限通用机械蜂箱工厂"，与离心机翻译不一致
  - **修复**：
    1. `cosmic_extra_mek_apiary_factory` → "寰宇支配通用机械蜂箱工厂"
    2. `infinite_extra_mek_apiary_factory` → "悖论无限通用机械蜂箱工厂"
    3. 同步更新对应的 container 翻译键
    4. README_zh.md 中的等级表和配置项描述同步更新
  - **影响范围**：2 个文件修改（`zh_cn.json`、`README_zh.md`）

### 重构

- **EME 布局参数隔离** — 将 EME 专属布局方法从基础类抽取至 compat 隔离类
  - **问题**：`FactoryLayoutHelper.java` 的 6 个 EME 方法签名直接引用 `EMExtraFactoryTier`，虽然 JVM 延迟解析方法签名保证运行时安全，但方法签名级别的可选依赖是"隐式依赖"，未来维护者误调用 EME 重载会触发 `NoClassDefFoundError`
  - **修复**：
    1. 新建 `compat/emextras/EMEFactoryLayoutHelper.java` 隔离类，承载 EME 4 等级的 6 个布局参数方法（imageWidthAddition / inventoryLabelX / baseX / baseXMult / fluidTankX / fluidTankY）
    2. `FactoryLayoutHelper.java` 移除 EME import 和 6 个 EME 方法重载，更新 javadoc 说明 EME 隔离
    3. `EMExtraMekCentrifugeFactoryContainer.java` 改用 `EMEFactoryLayoutHelper` 获取布局参数
  - **设计原则**：OCP（通过新增隔离类扩展 EME 支持，不修改基础类）、LoD（基础类不再"知道" EME 的存在）
  - **影响范围**：2 个文件修改 + 1 个新增文件

- **EME 离心机 GUI 类迁移至 compat 包** — 将直接引用 EME 类的 GUI 类从 `client/screen/` 移至 `compat/emextras/client/gui/`
  - **问题**：`GuiEMExtraMekCentrifugeFactory.java` 直接 import EME 的 `EMExtraGuiSortingTab`，虽然通过注册守卫和方法引用延迟加载保证运行时安全，但包位置不符合隔离规范
  - **修复**：
    1. 将 `GuiEMExtraMekCentrifugeFactory.java` 从 `client/screen/` 移至 `compat/emextras/client/gui/`
    2. 更新 `ProductiveBeesGenesisClient.java` 的 import 路径
    3. GUI 类内部改用 `EMEFactoryLayoutHelper` 获取布局参数（配合上述布局参数隔离）
  - **影响范围**：2 个文件修改 + 1 个新增文件 + 1 个文件删除

### SemVer 合规性

- **版本号定级**：本次发布为 bug 修复（2 项：EME/ME 启动崩溃、蜂箱翻译错误）+ 内部重构（2 项：EME 布局参数隔离、EME GUI 类迁移），无 API 变更，无 BREAKING 变更。按 [SemVer](https://semver.org/lang/zh-CN/) 严格规则定为 **PATCH** 级别（v2.0.0 → v2.0.1）

## [2.0.0] - 2026-07-27

v2.0.0 是 SemVer MAJOR 版本，标志 MEK 蜂箱系统正式发布。本次更新引入 MEK 蜂箱系统、MEK 离心机系统重构、KubeJS 集成、ME/EME 蜂箱与离心机工厂扩展、配置系统模块化重构、网络包与安全限流系统、万象创世物品与宇宙渲染系统、客户端基础事件与渲染系统等重大功能。

### 新增

- **MEK 蜂箱系统**（apiary 包，71 个新文件）
  - 基础蜂箱 + 17 个工厂等级（与离心机工厂一一对应）
    - 原版 4 等级：Basic / Advanced / Elite / Ultimate
    - ME 扩展 4 等级：Absolute / Supreme / Cosmic / Infinite
    - EM 进化 5 等级：Overclocked / Quantum / Dense / Multiversal / Creative
    - EME 扩展进化 4 等级：Absolute Overclocked / Supreme Quantum / Cosmic Dense / Infinite Multiversal
  - 槽位体系：蜜蜂槽 / 输出槽 / 蜂笼槽 / 能量槽 / 流体罐 / PB 升级槽
  - 喂食器系统：花朵槽矩阵管理，基础 3×3=9 槽，工厂版按蜂数动态扩展
  - PB 升级系统：9 种升级类型（4 产量 + 2 时间 + 基因 + 蜜脾块 + 模拟）
  - AE2 集成：物品/流体/能量输出，AppliedFlux 优先级切换
  - 直接弹出：蜂箱相邻离心机时绕过 MEK Ejector 节流直连
  - Jade 集成：显示蜜蜂状态、生产进度、AE2 网络状态
  - GUI 标签页：排序 / 喂食器 / PB 升级 / 多流体罐

- **MEK 离心机系统重构**（mek 包，85 个文件）
  - 配方处理层：PbRecipeContext / PbRecipeProcessor / PbRecipeFinder / PbRecipeCompleter
  - 万象创世：MyriadBatchPlanner / MyriadCreationsCache / MyriadCreationsHandler
  - 升级支持：MekEmpUpgradeSupport / MekExtraUpgradeSupport
  - AE2 集成：Ae2EnergyBridge / Ae2FluidPusher / Ae2OutputPusher（22 个文件）
  - 多流体罐：MultiFluidTankHolder / MultiFluidSideConfigHandler / MultiFluidTankNbtCodec
  - DevMode 管理：DevModeManager + DevModeCommand + DevModeStateSyncPacket

- **KubeJS 集成**（compat/kubejs 包）
  - ProductiveBeesGenesisKubeJSPlugin
  - MyriadBeeEvents.REGISTER 事件
  - 脚本可调用 addBreeding / addCentrifuge / addBeeProduce / addMekData

- **ME/EME 蜂箱与离心机工厂扩展**（compat/mekanism_extras + compat/emextras）
  - ME：4 个蜂箱工厂 + 4 个离心机工厂
  - EME：4 个蜂箱工厂 + 4 个离心机工厂

- **配置系统模块化重构**
  - ConfigSectionRegistry + 5 个子类（Apiary / Centrifuge / FluidTankMultiplier / StackMultiplier / WindowPosition）

- **网络包与安全限流系统**（network 包，19 个文件）
  - ApiaryPayloadHandlers / Ae2PayloadHandlers
  - PayloadRateLimiter（语义别名 + 玩家登出清理）
  - NetworkSecurityConstants（集中字符串/列表长度限制）

- **万象创世物品与宇宙渲染系统**
  - ItemInfinityCreationComb / ItemInfinityCreationCombBlock / ItemInfinitySwordReplica
  - CosmicRenderQueue / CosmicRenderTypes / BakedModelHalo / PerspectiveModel
  - Iris 兼容：ShaderInstanceMixin
  - 自定义 GLSL 着色器：cosmic.fsh/vsh/json + hell.fsh/vsh/json

- **客户端基础事件与渲染系统**
  - Jade 插件：JadeApiaryComponentProvider / JadeAe2StatusProvider
  - JEI 集成：PbCentrifugeRecipeCategory
  - Screen 系统：GuiMekCentrifuge / GuiMekCentrifugeFactory / FilterListScreen
  - 蜂箱 GUI：GuiMekApiary / GuiApiarySortingTab / GuiFeederTab / GuiPbUpgradeTab / GuiMultiFluidTanksTab

### 删除

- **遗留 PB 风格离心机资源**：移除 335 个 textures + 10 个 models + 10 个 java 旧文件
- **旧版 config/fml.toml**：已迁移到 neoforge.mods.toml

### 变更

- **BREAKING**：v2.0.0 是 SemVer MAJOR 版本
- **BREAKING**：配置系统模块化重构（ConfigSectionRegistry + 5 个子类）
- **BREAKING**：删除遗留 PB 风格离心机资源

### 文档

- **README 中英文版同步优化**：
  - **通用机械离心机部分详细化**：补充核心能力（多配方支持、工厂并行处理、输出安全、弹出优化、多流体罐、AE2 集成、Jade 面板）、离心机等级表（17 工厂 + 1 基础，含并行进程/输入槽/输出槽）、升级支持说明（MEK 原版 / MEKExtras / MekanismEmpowered / PB 升级四类共存）
  - **PB 升级系统独立成章节**：从蜂箱部分抽出，独立介绍 9 种升级类型（生产力 α/β/γ/Ω、时间 I/II、基因采样、蜜脾块、模拟）、适用设备（离心机 6 种，蜂箱全部 9 种）、效果计算方式（加权加算模式，含公式与示例）、安装上限差异化（8/8/8/4/1）、与 Mekanism 原版升级的关系
  - **兼容模组表格补充 MekanismEmpowered**：声明强化速度/能量、IO 容量、自动插入器、快速物品插入/弹出升级（离心机 6 种，蜂箱 5 种）为可选兼容
  - **目录更新**：添加 PB 升级系统章节链接

### SemVer 合规性

- **版本号定级**：本次发布为 SemVer MAJOR 版本（v1.8.1 → v2.0.0），包含 MEK 蜂箱系统、MEK 离心机系统重构、KubeJS 集成、ME/EME 工厂扩展、配置系统模块化重构、网络包与安全限流系统、万象创世物品与宇宙渲染系统、客户端基础事件与渲染系统等重大功能新增，并包含 BREAKING 变更（配置系统模块化重构、删除遗留 PB 风格离心机资源）。按 [SemVer](https://semver.org/lang/zh-CN/) 严格规则定为 **MAJOR** 级别

## [1.8.1] - 2026-07-05

### 修复

- **AE 能量注入上限移除** — 解决 32 速度升级下 AE 网络供能跟不上消耗的问题
  - **问题**：v1.8.0 引入的 `mekCentrifugeAeEnergyInjectionPerTick` 配置项（默认 1000 FE/tick）作为 AE 网络能量注入上限，导致 32 速度升级（每 tick 消耗约 1600 FE）下供能跟不上
  - **根因**：经反编译调研 Mek-Energistics 1.0.7，其 `MeMekanismMachineBlockEntity.hasEnergyForRecipe()` 方法每次按需差额提取（提取量 = `energyPerTick - energyContainer.getEnergy()`），无 perTick 上限。本模组原实现引入的 perTick 上限属于过度设计，与参考模组不一致
  - **修复**：
    1. 删除 `mekCentrifugeAeEnergyInjectionPerTick` 配置项（含字段、注册、委托、翻译键）
    2. 修改 `Ae2EnergyInjector.injectEnergy()` 方法签名移除 `maxAmount` 参数，注入量改为按容器剩余容量差额提取（`toExtract = maxEnergy - currentEnergy`），由 SIMULATE 模式进一步限制为 ME 网络可提取量
    3. 最终注入量 = `min(容器剩余容量, ME 网络可提取量)`，与 Mek-Energistics 完全对齐
  - **影响范围**：7 个文件修改（`CentrifugeConfigSection.java`、`ServerConfig.java`、`IAe2OutputHost.java`、`Ae2EnergyInjector.java`、`en_us.json`、`zh_cn.json`）

- **能量不足时机器仍发光材质 bug** — 修复离心机能量不足但有输入物品时切换到加工中材质（发光）的问题
  - **问题**：用户反馈离心机能量不足但有输入物品时仍切换到加工中材质（发光），取走输出物后几秒恢复，与预期行为不符
  - **根因**：`PbRecipeProcessor.tryProcessPbRecipeInternal` 第 275-279 行能量不足时执行 `pbProcessing[processIndex] = true; return true;`，导致 `MekCentrifugeTickHandler.onUpdateServer` 调用 `tile.callSetActive(true)` 触发加工中材质；工厂版同理通过 `tryProcessPbRecipe` 返回 true 触发 `onProcessActivated` 递增计数器，`hasActiveProcess()` 返回 true，`setActive(true)`
  - **修复**：将能量不足分支改为 `pbProcessing[processIndex] = false; return false;`，让上层判定机器未在加工，不切换到加工中材质
  - **设计权衡**：保留 `pbOperatingTicks` 进度（不重置），能量恢复后从保留的进度继续处理，避免能量波动导致进度丢失
  - **影响范围**：1 个文件修改（`PbRecipeProcessor.java`）

- **AE2 节点创建与 aeOutputEnabled 配置解耦** — 修复关闭 AE2 直接输出时设备离线导致 ME 网络能量输入也失效的问题
  - **问题**：用户反馈关闭 `aeOutputEnabled` 配置时设备直接离线，此时即使启用 `aeEnergyInputEnabled`，机器也无法从 ME 网络获取能量
  - **根因**：`Ae2GridNodeManager` 中的节点创建/连接/销毁/NBT 守卫使用 `isIntegrationEnabled()`（要求 AE2 已安装且 `aeOutputEnabled=true`），导致关闭输出推送时节点不创建，机器与 ME 网络完全断开
  - **参考模组调研**：反编译 Mek-Energistics 1.0.7 的 `MeMekanismMachineBlockEntity` 构造函数，其 `mainNode` 创建是**无条件**的（不依赖任何配置开关），仅输出推送行为受配置控制
  - **修复**：
    1. 将 `Ae2GridNodeManager` 中 `prepareNode`/`connectNode`/`loadNodeNBT`/`getGridNodeState` 的守卫从 `isIntegrationEnabled()` 改为 `isAe2Loaded()`（仅检测 AE2 是否安装）
    2. 保留 `Ae2OutputPusher.pushOutputs()` 中的 `isIntegrationEnabled()` 检查，仅用于输出推送守卫
    3. 更新 `Ae2IntegrationLoader` Javadoc 明确两个方法的语义边界
  - **设计原则**：
    - **SRP**：`isAe2Loaded()` 仅负责检测 AE2 加载状态（用于节点生命周期），`isIntegrationEnabled()` 仅负责检测输出推送功能启用状态
    - **LoD**：配置访问点通过抽象方法守卫，不直接耦合具体配置项
  - **影响范围**：2 个文件修改（`Ae2GridNodeManager.java`、`Ae2IntegrationLoader.java`）

- **子分类翻译键格式修复** — 修复 v1.8.1 引入的 MEK 离心机子 section 翻译键格式错误
  - **问题**：v1.8.1 引入的 4 个子 section 翻译键格式错误，使用了 `productivebeesgenesis.configuration.section.productivebeesgenesis.server.toml.mek_centrifuge.basic` 形式（含文件名前缀、父 section 路径和 `.title` 后缀），导致 NeoForge 配置界面无法正确显示子分类标题
  - **根因**：NeoForge 子 section 翻译键格式应为 `modId.configuration.<section_key>`，不含文件名前缀、父 section 路径和 `.title` 后缀
  - **修复**：
    1. 删除 8 个错误翻译键
    2. 添加 12 个正确翻译键（格式为 `productivebeesgenesis.configuration.<section_key>`，覆盖 `.button` 和 `.tooltip` 变体）
    3. 4 个子 section：`basic`、`ejection`、`io_limit`、`ae2`
  - **影响范围**：2 个文件修改（`ModLanguageProvider.java`、`en_us.json` + `zh_cn.json`）

### 新增

- **AE2/AppliedFlux 配置项条件化注册** — 附属模组未加载时对应配置项不显示在配置文件和界面
  - **问题**：v1.8.0 注册的 AE2 配置项（`aeOutputEnabled`、`aeEnergyInputEnabled`、`preferAppliedFluxOverAeEnergy`）在 AE2/AppliedFlux 未加载时仍出现在配置文件和 NeoForge 配置界面，给用户造成困惑
  - **修复**：
    1. 在 `CentrifugeConfigSection` 构造时检查 `Ae2IntegrationLoader.isAe2Loaded()`，未加载则跳过整个 `ae2` 子 section 注册（`builder.push("ae2")` 不执行）
    2. 在 `ae2` section 内检查 `AppliedFluxIntegrationLoader.isAppliedFluxLoaded()`，未加载则跳过 `preferAppliedFluxOverAeEnergy` 注册
    3. 未加载时对应字段为 `null`，访问处通过 `Ae2IntegrationLoader.isAe2Loaded()` 守卫避免 NPE
    4. `IAe2OutputHost.productivebeesgenesis$injectAe2Energy()` 添加 `mekCentrifugeAeEnergyInputEnabled` 非 null 守卫（防御性检查）
    5. `Ae2EnergyInjector.readPreferAppliedFlux()` 添加 `mekCentrifugePreferAppliedFluxOverAeEnergy` 非 null 守卫，AppliedFlux 未加载时返回 false 直接使用 AE2 原生能量
  - **设计原则**：
    - **LoD**：配置访问点通过 `Ae2IntegrationLoader.isAe2Loaded()` 守卫，不直接检查 mod 加载状态
    - **OCP**：通过条件化 push/pop 实现配置项可选注册，不修改配置项定义逻辑
  - **影响范围**：3 个文件修改（`CentrifugeConfigSection.java`、`IAe2OutputHost.java`、`Ae2EnergyInjector.java`）

- **MEK 离心机配置子分类** — 提升 NeoForge 配置界面的可读性与导航效率
  - **问题**：v1.8.0 之前 MEK 离心机的 21 个配置项全部位于 `mek_centrifuge` 单一 section，配置界面单页过长，难以快速定位
  - **修复**：将 21 个配置项按功能分为 4 个子 section：
    - `mek_centrifuge.basic` — 基础参数（5 项：energyPerTick、processingTime、fluidTankCapacity、fluidEjectRate、combBlockMultiplier）
    - `mek_centrifuge.ejection` — 弹出策略（11 项：ejectDelay、ejectDelayActive、ejectSkipUnchanged、ejectSkipTicks、ejectMaxSpeedMode、ejectMinInterval、ejectBusyThreshold、ejectBusyCooldown、ejectMaxPerTick、ejectBlockedThreshold、ejectBlockedCooldown）
    - `mek_centrifuge.io_limit` — IO 限流（1 项：maxExtractPerTick）
    - `mek_centrifuge.ae2` — AE2 集成（条件化注册，2-3 项：aeOutputEnabled、aeEnergyInputEnabled、preferAppliedFluxOverAeEnergy）
  - **BREAKING 配置结构变更**：配置文件从 `[mek_centrifuge]` 扁平结构变为 `[mek_centrifuge.basic]` / `[mek_centrifuge.ejection]` 等嵌套结构，旧配置文件中的扁平键会被 NeoForge 视为未注册键发出 warning 但不影响功能（根据用户规则 16"模组暂未发布，无需在乎版本升级兼容"可接受）
  - **影响范围**：3 个文件修改（`CentrifugeConfigSection.java`、`en_us.json`、`zh_cn.json`）

### 改进

- **全配置项描述精简** — 精简所有配置项的 comment 与 tooltip，提升配置界面可读性
  - **问题**：v1.8.0/v1.8.1 新增的 AE2 与附属相关配置描述过于啰嗦，其他历史配置项描述也存在冗长问题，配置界面单页信息密度过高
  - **精简原则**：
    - 每个配置项 comment 不超过 3 行
    - tooltip 精简到 1-2 句核心信息
    - 保留关键默认值/单位/格式说明（如 `0=无限制`、`格式: modID:beeType`）
    - 枚举值用 `/` 分隔（如 `DISABLED/BLOCKLIST/WHITELIST`）
  - **精简范围**：
    1. **MEK 离心机配置**（`CentrifugeConfigSection.java`）：21 个配置项 comment 全部精简到 ≤3 行
    2. **服务端其他配置**（`ServerConfig.java`）：`devMode`、`myriadCreationsEnabled`、`myriadCreationsFilterMode`、`myriadCreationsFilteredBeeTypes`、`produceOutputItem`、`myriadProduceThrottlePerTick`、`advancedBeehiveSimulateCooldown`、`advancedBeehiveSaveInterval` 等 8 项精简
    3. **蜜蜂属性配置**（`BeeAttributeConfigSection.java`）：`createComb` 精简
    4. **客户端配置**（`ClientConfig.java`）：`showPortColors` 精简
    5. **语言文件**（`ModLanguageProvider.java` + `en_us.json` + `zh_cn.json`）：英文与中文 tooltip 同步精简，覆盖 `devMode`、`combBlockMultiplier`、`fluidTankCapacity`、`myriadProduceThrottlePerTick`、`showPortColors`、`saveInterval`、`simulateCooldown`、`filterMode`、`colors`、`mekCentrifugeEjectBlockedCooldown`、`mekCentrifugeFluidEjectRate`、`mekCentrifugeMaxExtractPerTick`、`server.other` 等
  - **影响范围**：6 个文件修改（`CentrifugeConfigSection.java`、`ServerConfig.java`、`BeeAttributeConfigSection.java`、`ClientConfig.java`、`ModLanguageProvider.java`、`en_us.json` + `zh_cn.json`）

### 文档

- 更新 `future-optimization.md`：记录 v1.8.1 完成情况与下个版本（v1.9.0）建议

### SemVer 合规性

- **版本号定级**：本次发布为 bug 修复（4 项：AE 能量注入上限移除、能量不足机器仍发光、AE2 节点与配置解耦、子分类翻译键格式修复）+ 新增功能（2 项：AE2/AppliedFlux 配置条件化注册、MEK 离心机配置子分类）+ 改进（1 项：全配置项描述精简），含 BREAKING 配置结构变更。根据用户规则 16"模组暂未发布，无需在乎版本升级兼容"，BREAKING 配置结构变更不影响版本号定级。按 [SemVer](https://semver.org/lang/zh-CN/) 严格规则，bug 修复为主 + 配置体验改进定为 **PATCH** 级别（v1.8.0 → v1.8.1）

## [1.8.0] - 2026-07-05

### 新增

- **AppliedFlux + AE2 网络能量输入集成** — 离心机现在可以直接使用 ME 网络中存储的 FE 能量
  - **问题**：v1.7.0 已实现离心机作为 AE2 网格节点连接 ME 网络，但离心机只能通过外部线缆供能或内部 FE 缓存运行，无法直接使用 ME 网络中存储的能量
  - **根因**：离心机的 `MachineEnergyContainer` 仅接受 Mekanism `EnergyInventorySlot.fillContainerOrConvert()` 注入的外部 FE，未实现从 AE2 网络提取能量的通道
  - **修复**：
    1. 新建 `AppliedFluxIntegrationLoader` 软依赖检测类（SRP），通过 `ModList.get().isLoaded("appflux")` 检测 AppliedFlux 加载状态，使用 Holder 模式实现线程安全懒加载
    2. 新建 `Ae2EnergyBridge` 类（SRP），封装从 AE 网络提取能量的两个静态方法：
       - `extractAppliedFluxFe`：通过 `IStorageService.getInventory().extract(FluxKey.of(EnergyType.FE), ...)` 提取 AppliedFlux 存储的 FE
       - `extractAeEnergyAsFe`：通过 `grid.getEnergyService().extractAEPower()` 提取 AE2 原生能量，按 2 FE = 1 AE 比例转换
    3. 新建 `Ae2EnergyInjector` 类（SRP + DIP），协调从 AE 网络提取能量并注入到 `MachineEnergyContainer`：
       - 使用 `Action.SIMULATE` 先模拟提取确定可提取量，再实际提取（MODULATE），避免实际提取后容器已满导致 ME 网络能量浪费
       - 依赖 `IAe2OutputHost` 抽象，不直接引用具体 TileEntity
       - 容器已满时（剩余容量 ≤ 0）直接返回 0
    4. `IAe2OutputHost` 接口扩展 `productivebeesgenesis$injectAe2Energy()` default 方法（OCP），三重守卫：① `Ae2IntegrationLoader.isAe2Loaded()` ② `ModConfig.SERVER` 非 null + `mekCentrifugeAeEnergyInputEnabled` 配置 ③ 委托 `Ae2EnergyInjector.injectEnergy()`
    5. `MekCentrifugeTickHandler` 和 3 个工厂类在 `super.onUpdateServer()` 调用**前**注入 AE 网络能量，让父类处理 SMELTING 配方消耗时已有注入的能量可用
  - **能量优先级策略（5 层）**：
    1. 机器内部缓存 FE（`MachineEnergyContainer.getEnergy()`）
    2. 外部直接供能（Mekanism `configComponent` + `EnergyInventorySlot.fillContainerOrConvert()`，由 `super.onUpdateServer()` 处理）
    3. AE 网络存储的 FE（AppliedFlux）— 新增
    4. 其他能量（由 Mekanism 父类处理）
    5. AE2 原生网络能量（转换为 FE）— 新增
  - **注入时机设计**：在 `super.onUpdateServer()` 调用**前**注入，让父类处理 SMELTING 配方消耗时已有注入的能量可用；工厂版在 `energyBeforeSuper` 快照**前**注入，保证能量差计算正确
  - **配置开关**（3 项，默认关闭保证向后兼容 v1.7.0 行为）：
    - `mekCentrifugeAeEnergyInputEnabled`（默认 false）：是否启用 AE 网络能量输入
    - `mekCentrifugePreferAppliedFluxOverAeEnergy`（默认 true）：优先使用 AppliedFlux FE 而非 AE2 原生能量
    - `mekCentrifugeAeEnergyInjectionPerTick`（默认 1000，范围 1-100000）：每 tick 最大注入量
  - **AE2 类引用控制**：
    - 所有 AE2 类引用通过 `Ae2IntegrationLoader.isAe2Loaded()` 守卫
    - 所有 AppliedFlux 类引用通过 `AppliedFluxIntegrationLoader.isAppliedFluxLoaded()` 守卫
    - AppliedFlux jar 配置为 `compileOnly` 依赖，运行时通过守卫短路，未安装时不触发类加载
  - **设计原则**：
    - **SRP**：新建 `AppliedFluxIntegrationLoader`（仅负责加载检测）、`Ae2EnergyBridge`（仅负责能量提取）、`Ae2EnergyInjector`（仅负责注入协调），三个类职责分离
    - **OCP**：通过 `IAe2OutputHost` 接口扩展 default 方法，不修改现有实现类
    - **DIP**：`Ae2EnergyInjector` 依赖 `IAe2OutputHost` 抽象，不直接引用具体 TileEntity
    - **LoD**：Tick 处理器仅调用 `host.injectAe2Energy()`，不直接访问 AE2 网络或 AppliedFlux API
  - **参考模组**：[Mek-Energistics](https://github.com/beipuo/Mek-Energistics) 的三层能量优先级模式（本地 FE → AE 网络能量 → AppliedFlux/AE 原生）

### 文档

- 更新 `README.md` 与 `README_zh.md`：扩展 AE2 Integration 描述，添加 AppliedFlux 能量输入兼容；致谢部分新增 Mek-Energistics
- 更新 `THIRD_PARTY_LICENSES.md`：添加 AppliedFlux 与 Mek-Energistics 许可证信息
- 更新 `future-optimization.md`：记录 v13（1.8.0）完成情况与后续优化方向

### SemVer 合规性

- **版本号定级**：本次发布为新增功能（AppliedFlux + AE2 网络能量输入），向后兼容无破坏性变更（默认配置关闭，行为与 v1.7.0 一致），按 [SemVer](https://semver.org/lang/zh-CN/) 严格规则定为 **MINOR** 级别（v1.7.0 → v1.8.0）

## [1.7.0] - 2026-07-05

### 新增

- **AE2 线缆及附属联动连接适配** — 离心机现在可被 AE2 线缆直接发现并连接
  - **问题**：离心机已能通过 ME 接口连接 ME 网络，但离心机与离心机之间、离心机与 AE2 其他线缆（含 ExtendedAE / AdvancedAE / ae2cs / ae2lt / Glodium / AppliedFlux 等附属扩展模组）之间无法连接到 ME 网络
  - **根因**：AE2 19.x（1.21.1）将旧 `IGridNodeHost` 重构为 `appeng.api.networking.IInWorldGridNodeHost`，通过 NeoForge `BlockCapability`（`AECapabilities.IN_WORLD_GRID_NODE_HOST`）发现相邻方块上的网格节点。本项目仅调用了 `setExposedOnSides` 和 `setInWorldNode(true)`，但未实现该接口、也未注册 capability，导致 AE2 线缆在 `GridHelper.getExposedNode` 中查找时返回 null
  - **修复**：
    1. `IAe2OutputHost` 接口扩展 `extends IInWorldGridNodeHost`，新增 `getGridNode(Direction)` 和 `getCableConnectionType(Direction)` 默认方法
       - `getGridNode` 从 `productivebeesgenesis$getAe2GridNode()` 取 `IManagedGridNode`，校验 `InWorldGridNode.isExposedOnSide(dir)` 后返回节点
       - `getCableConnectionType` 返回 `AECableType.SMART`，让 AE2 渲染智能线缆连接纹理
    2. 新建 `Ae2CapabilityRegistrar` 类（SRP 单一职责），封装 `IN_WORLD_GRID_NODE_HOST` capability 注册，覆盖全部 18 个离心机 BlockEntityType（基础 1 + 原版 4 + EM 5 + ME 4 + EME 4）
    3. `ProductiveBeesGenesis.onRegisterCapabilities` 通过 `Ae2IntegrationLoader.isAe2Loaded()` 守卫调用 `Ae2CapabilityRegistrar.register`
  - **通用适配机制**：通过 `IN_WORLD_GRID_NODE_HOST` capability 通用发现机制，所有 AE2 生态模组（ExtendedAE / AdvancedAE / ae2cs / ae2lt / Glodium / AppliedFlux）的线缆都能自动发现并连接离心机，无需为每个附属模组单独适配代码
  - **设计原则**：
    - **SRP**：新建 `Ae2CapabilityRegistrar` 仅负责 capability 注册，不处理节点生命周期
    - **OCP**：接口扩展（新增 default 方法）不修改现有实现类
    - **DIP**：capability provider lambda 通过 `IAe2OutputHost` 抽象访问
  - **AE2 类引用控制**：所有 AE2 类引用通过 `Ae2IntegrationLoader.isAe2Loaded()` 在调用点短路保护，AE2 未安装时不会触发类加载

### 文档

- 更新 `README.md` 与 `README_zh.md`：扩展 AE2 Integration 描述，明确线缆连接兼容性
- 更新 `future-optimization.md`：记录 v12（1.7.0）完成情况与后续优化方向

### SemVer 合规性

- **版本号定级**：本次发布为新增功能（AE2 线缆连接适配），向后兼容无破坏性变更，按 [SemVer](https://semver.org/lang/zh-CN/) 严格规则定为 **MINOR** 级别（v1.6.1 → v1.7.0）

## [1.6.1] - 2026-07-05

### 修复

#### P1 — 重要问题（5 项）

- **P1-1: PBReflectionCacheCleaner 单 try-catch 导致部分失败状态不一致** — 反射清理操作部分失败时静态缓存处于半清理状态
  - **问题**：`cachedBiomes` 和 `cachedRecipes` 共用同一个 try-catch 块，若 `cachedRecipes` 字段名在 PB 版本变更后被重命名，`cachedBiomes` 已被成功清理但 `cachedRecipes` 仍保留旧 Recipe 引用
  - **修复**：抽取私有方法 `clearField(String fieldName)`，每个字段独立 try-catch，单个失败不影响另一个
- **P1-2: onServerStopped 缺乏异常隔离** — 任一清理方法抛出异常会中断后续清理
  - **问题**：`onServerStopped` 中 4 个清理方法串联调用无 try/catch 隔离，静态缓存残留并泄漏到新存档
  - **修复**：抽取 `safeClear(Runnable, String)` 私有方法，每个清理操作独立 try/catch
- **P1-3: MekAe2LifecycleHandler 同步策略不一致** — `ae2NodePending` 标志的 check-then-set 非原子
  - **问题**：`Ae2GridNodeManager.prepareNode`/`connectNode`/`destroyNode` 都使用 `synchronized(host)` 保护 check-then-act，但 `MekAe2LifecycleHandler` 对 `ae2NodePending` 标志的读写不在该锁内
  - **修复**：将 `prepareForLoad`、`tryConnectNode`、`destroyForRemoval`、`destroyForChunkUnload` 整体放入 `synchronized(host)` 块
- **P1-4: Ae2GridNodeManager.saveNodeNBT/loadNodeNBT 未同步** — 与其它三个方法同步策略不一致
  - **问题**：`saveNodeNBT` 读取 `getAe2GridNode()` 后调用 `node.saveToNBT(tag)` 未加锁，若 `destroyNode` 并发执行可能在 `node.saveToNBT` 执行期间节点被销毁
  - **修复**：为 `saveNodeNBT` 和 `loadNodeNBT` 添加 `synchronized(host)` 包裹
- **P1-5: BakedModelHalo pushPose/popPose 未 try/finally 保护** — 异常路径 PoseStack 栈深度永久 +1
  - **问题**：`renderHalo` 的 `try/finally` 只恢复 RenderSystem 状态，未保证 `poseStack` 栈平衡，若 `putBulkData` 抛异常，`popPose` 不会执行
  - **修复**：每个 `pushPose` 都用独立 try/finally 包裹 `popPose`

#### P2 — 线程安全 / 正确性（10 项）

- **P2-1: MyriadBeeTypeCache 模板数组未防御性拷贝** — API 契约脆弱
  - **修复**：在 Javadoc 中明确标注"返回数组不可修改，调用方必须 copy 后再修改"的契约约束
- **P2-2: BeeTypeCacheSnapshot.EMPTY 持有可变 CopyOnWriteArrayList** — 共享可变实例污染风险
  - **问题**：EMPTY 快照中的 `CopyOnWriteArrayList` 是可变列表，若消费者在 EMPTY 状态下调用 `add(...)` 会污染所有后续 EMPTY 状态的读取
  - **修复**：将 EMPTY 的 beeTypes 改为 `List.of()`（真正不可变），record 字段类型改为 `List<ResourceLocation>`
- **P2-3: connectNode 缺少"已连接"幂等检查** — 竞态发生时 `create()` 可能被重复调用
  - **修复**：在 `synchronized` 块内、`node.create()` 调用前增加 `if (node.getNode() != null) return;` 防御性检查
- **P2-4: Ae2OutputStateHolder.clear() 非原子** — 并发读取方可能观察到中间状态
  - **修复**：通过 P1-3 的 `synchronized(host)` 间接解决，`clear()` 自身无需加锁
- **P2-5: BeeIngredientFactory 未就绪不安排重试** — 配方永远不会被修改且无恢复路径
  - **问题**：`overrideRecipesInternal` 检查 `BeeIngredientFactory.getOrCreateList().containsKey(MYRIADCREATIONS_TYPE_STRING)`，若未就绪仅记录 warn 并 return
  - **修复**：调用 `RecipeReloadRetryManager.rescheduleRetry` 安排延迟重试（不重置 retryCount，避免无限重试）
- **P2-6: RecipeReloadRetryManager 理论竞态** — 新的 context 会被误清除
  - **问题**：`onServerTickSlowPath` 读取 `pendingRetryContext` 后才调用 `clearPendingRetryContext()`，若期间 `scheduleRetry` 被并发调用，新的 context 会被误清除
  - **修复**：将 `pendingRetryContext` 改为 `AtomicReference<PendingRetryContext>`，使用 `compareAndSet(ctx, EMPTY)` CAS 模式确保只清除自己读取的 context 实例
- **P2-7: MyriadBeeTypeCache.onServerTick 计数器非原子** — `incrementAndGet` 与 `set` 非原子
  - **修复**：使用 `getAndUpdate` 实现原子的"递增并按需重置"
- **P2-8: MyriadSelectionCache.invalidate 不清理 selected 列表** — 服务器停止后仍持有旧引用
  - **修复**：在 `invalidate()` 的 synchronized 块中增加 `e.selected = List.of();`
- **P2-9: BakedModelCosmic/Hell setupShaderUniforms 缺少 uniform null 检查** — 依赖 MC 内部实现细节
  - **问题**：仅 `cosmicUVs` 有 null 检查，其余 5 个 uniform 直接 `.set()`，依赖 `safeGetUniform` 不返回 null 的内部实现
  - **修复**：所有 6 个 uniform 都添加 null 检查
- **P2-10: AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin RETURN 与 TAIL 注入冗余** — 同一返回点做了两次同样清理
  - **问题**：`@At("TAIL")` 等同于 finally 块，覆盖正常返回和异常抛出路径；`@At("RETURN")` 仅匹配正常返回，两者注入方法体完全相同
  - **修复**：删除 `@At("RETURN")` 注入方法，保留 `@At("TAIL")` 注入方法

#### P3 — 规范 / 完善（4 项修复）

- **P3-1: AbstractCombEventHandler ThreadLocal 未清理** — 线程池场景下可能泄漏
  - **修复**：添加 `clearThreadLocals()` 公共静态方法，在 `ProductiveBeesGenesis.onServerStopped` 中调用
- **P3-2: RecipeReloadRetryManager 超上限仅 warn** — 严重配置错误应记录 error
  - **修复**：将 `LOGGER.warn` 改为 `LOGGER.error`
- **P3-3: BeeHelperMixin 缩进错误** — 影响可读性
  - **修复**：统一缩进为 4 级
- **P3-4: onServerStopped 使用完全限定类名** — 与其他事件类型风格不一致
  - **修复**：添加 `import net.neoforged.neoforge.event.server.ServerStoppedEvent;` 并修改方法签名

### 完善

- **P3-5: Ae2OutputStateHolder.clear 未清空 reusableBuffers 内部集合** — 评估后保持现状
  - **决策**：`Ae2OutputStateHolder` 不引用 `ReusableBuffers`（包级可见 + 类型隔离），实现需通过反射或清理回调破坏隔离设计，当前无外部引用持有 `ReusableBuffers`，不构成实际泄漏
- **P3-6: MyriadCreationsEventHandler @EventBusSubscriber 未限制 Dist** — 评估后保持现状
  - **决策**：该类被 Mixin 静态引用（`isMyriadCreationsHoneycomb` 等），不能直接限制为 `Dist.DEDICATED_SERVER`，拆分到独立内部类会增加复杂度，收益不抵成本
- **P3-7: MixinExtraFactory / MixinFactoryForME / MixinEMExtraFactory 死代码** — 评估后保持现状
  - **决策**：作为扩展点保留，符合开闭原则，若未来切换到 Factory 基类，这些 Mixin 可立即生效

### 变更

- **连锁类型变更**：由于 P2-2 将 `BeeTypeCacheSnapshot.beeTypes` 类型从 `CopyOnWriteArrayList<ResourceLocation>` 改为 `List<ResourceLocation>`，同步修改了以下文件的参数类型：
  - `RandomHoneycombSelector.java` — 6 个方法参数
  - `MyriadSelectionCache.java` — `selectDistinctBeeTypesCached` 参数
  - `AbstractCombEventHandler.java` — `buildBeeTypeCache` 返回类型 + `appendRandomCombsInternal` 参数
  - `MyriadBeeTypeCache.java` — `updateBeeTypeCache` 中 `newCache` 变量类型
- **统一模式**：
  - 引入"CAS 清除自己读取的 context 实例"作为 reloader 调用 scheduleRetry/rescheduleRetry 后不被误清除的标准模式
  - 引入"rescheduleRetry 不重置 retryCount"作为子重试场景避免无限重试的标准模式
  - 引入"try/finally 保护 PoseStack 栈平衡"作为渲染层 pushPose 的标准模式

### SemVer 合规性

- **版本号定级**：本次发布全部为 bug 修复 + 内部架构加固 + 规范完善，没有用户可见的新功能，没有不兼容变更，按 [SemVer](https://semver.org/lang/zh-CN/) 严格规则定为 **PATCH** 级别（v1.6.0 → v1.6.1）

## [1.6.0] - 2026-07-05

### 新增

- **P0: Ae2OutputPusher 对象复用** — 256× 加速场景下高频对象分配优化
  - **问题**：每次 `pushOutputs` 调用分配 5+ 个临时对象（MekEnergyToAeAdapter、BaseActionSource、ArrayList、HashMap×2），256× 加速 + 14 台离心机场景下每 tick 70+ 对象分配
  - **修复**：引入 `ReusableBuffers` 内部类，由 `Ae2OutputStateHolder` 持有，跨 tick 复用
    - `BaseActionSource` 提升为 `private static final` 全局单例（无状态）
    - `MekEnergyToAeAdapter` 懒初始化，宿主生命周期内仅创建一次
    - `ArrayList<SlotEntry>` + `HashMap` 跨 tick 复用，`clear()` 而非新建
    - 方块销毁时由 `Ae2OutputStateHolder.clear()` 自动释放
  - **性能收益**：稳态下每 tick 临时对象分配从 70+ 降至 0

### 变更

- **P1: ProductiveBeesGenesis 主类构造函数拆分** — 88 行构造函数拆分为 6 个私有方法
  - `initMekCentrifugeExtensions()` — EM/ME/EME 三层工厂初始化
  - `registerDeferredRegisters(IEventBus)` — 注册 DeferredRegister
  - `registerConfigs(ModContainer)` — 注册配置文件
  - `registerConfigListeners(IEventBus)` — 配置加载/重载监听器
  - `registerModEventBusListeners(IEventBus)` — mod 事件总线监听器
  - `registerNeoForgeEventBusListeners()` — NeoForge 事件总线监听器
  - 构造函数从 88 行降至 22 行，职责分离更清晰

### 修复

- **P3: Ae2GridNodeManager.connectNode 缺失 synchronized(host) 锁保护** — 锁一致性隐患
  - **问题**：`prepareNode` 和 `destroyNode` 均使用 `synchronized(host)` 保护 check-then-act 块，但 `connectNode` 未使用，锁保护不一致
  - **修复**：为 `connectNode` 添加 `synchronized(host)` 保护，与 `prepareNode`/`destroyNode` 保持一致的锁粒度
- **P3: MyriadBatchPlanner.plan() tick=-1L 导致快照缓存失效** — 跨路径缓存无法复用
  - **问题**：`plan(List, Item, Map)` 重载方法内部调用 `takeSnapshot(slots, baseItem, -1L)`，与批量路径的真实 tick 值不匹配，导致同一 tick 内同一 slots 实例的快照无法跨路径复用
  - **修复**：为 `plan(List, Item, Map)` 添加 `long tick` 参数，调用方 `MyriadCreationsHandler.completeMyriadCreations` 传入 `context.level().getGameTime()`

### 完善

- **P3: TileEntityMekCentrifuge.slotManager 非 volatile** — 评估后保持现状
  - **决策**：与 v1.5.5 P3-3 决策一致，在字段 Javadoc 中标注线程安全约束："方块实体在服务端单线程执行，Ejector Mixin 通过同线程读取，无需 volatile"
- **P3: MyriadCreationsHandler.cachedTicksForBase 双 volatile 非原子对** — 评估后保持现状
  - **决策**：单线程访问无竞态风险，修正 Javadoc 描述为"单字段读写原子，字段对非原子复合更新；方块实体服务端单线程执行，无跨线程竞态风险"

## [1.5.5] - 2026-07-04

### 修复

- **P2: TileComponentEjectorCooldownMixin refreshConfigCache check-then-act 竞态**：多线程同时通过冷却检查时可能重复刷新配置缓存
  - **问题**：`volatile long lastConfigRefreshTick` 的 check-then-act 非原子，多线程同时通过时间窗口检查时可能同时进入刷新逻辑
  - **修复**：改为 `AtomicLong` + CAS 模式，先读 lastRefresh 检查时间窗口，再 `compareAndSet(lastRefresh, currentTick)` 推进时间戳，CAS 失败则直接返回
- **P2: CosmicShaders 数组元素非 volatile**：UV 坐标和精灵数组元素修改的可见性无保证
  - **问题**：`COSMIC_UVS` 和 `COSMIC_SPRITES` 数组元素修改在渲染线程可能读到部分更新的状态
  - **修复**：引入 `CosmicUvSnapshot` 不可变 record 封装 `float[] uvs` 和 `TextureAtlasSprite[] sprites`，通过单一 `AtomicReference<CosmicUvSnapshot>` 原子替换；新增 `getCosmicUvs()` 和 `getCosmicSprites()` 兼容性访问方法
- **P2: CosmicRenderQueue 静态复用 Matrix4f/PoseStack 未同步**：Iris 并行渲染时可能并发访问
  - **问题**：静态复用的 `Matrix4f`、`PoseStack`、`List` 在 Iris 并行渲染线程间可能被并发访问，导致矩阵数据损坏
  - **修复**：将 4 个静态复用字段改为 `ThreadLocal` 隔离（`REUSABLE_OLD_PROJECTION`、`REUSABLE_OLD_MODEL_VIEW`、`RENDER_SNAPSHOT`、`REUSABLE_POSE_STACK`），每个线程持有独立的实例
- **P2: MekCentrifugeFactoryHelper 计数器状态守卫非原子**：`pbActiveStates[process]` check-then-act 可能多次递增
  - **问题**：`boolean[] pbActiveStates` 的 check-then-act 非原子，多线程同时调用 `onProcessActivated` 时可能多次递增 `activeProcessCount`
  - **修复**：参数 `boolean[] pbActiveStates` 改为 `AtomicIntegerArray`，使用 `compareAndSet(process, 0, 1)` 和 `compareAndSet(process, 1, 0)` 的 CAS 模式，CAS 成功才递增/递减计数器；`FactoryPbContextDelegate` 同步修改字段类型
- **P2: FilterListBeeInfoCache 跨多 map 复合操作未整体加锁**：clear 过程中可能观察到不一致状态
  - **问题**：跨 `iconCache`、`displayNameCache`、`productInfoCache` 三个 map 的复合操作未整体加锁，使用 `Collections.synchronizedMap` 的 per-map 锁无法保证复合操作原子性
  - **修复**：删除 `Collections.synchronizedMap` 包装，新增单一 `LOCK` 对象，所有 `getBeeIcon`/`getBeeDisplayName`/`getBeeProductInfo`/`clear` 方法体包裹在 `synchronized (LOCK)` 中
- **P2: AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin ThreadLocal 异常路径泄漏**：原方法抛异常时 RETURN 注入不触发
  - **问题**：`@Inject` with `@At("RETURN")` 在原方法抛异常时不触发，ThreadLocal 残留导致后续 tick 误用上次 block entity 引用
  - **修复**：新增 `@At(value = "THROW")` 注入点，在异常路径清理 `productivebeesgenesis$CURRENT_BLOCK_ENTITY.remove()`
- **P3: BeeHelperMixin THROTTLE_COUNTERS 内存泄漏风险**：极端场景下 map 容量可能持续膨胀
  - **问题**：节流 tick 推进失败或大量蜜蜂 ID 重建时，`THROTTLE_COUNTERS` map 容量可能持续膨胀导致内存压力
  - **修复**：增加 `PRODUCTIVEBEESGENESIS$THROTTLE_HARD_LIMIT = 10_000` 硬上限，超过阈值时强制 `clear()` 释放桶数组，最坏影响仅是本 tick 内节流失效一次
- **P3: ProductiveBeesGenesis recipeVersion 非原子自增**：volatile long 自增非原子
  - **问题**：`public static volatile long recipeVersion = 0L` 的 `recipeVersion++` 非原子，虽然 TagsUpdatedEvent 在主线程触发，但部分模组可能在异步上下文触发重载事件
  - **修复**：改为 `AtomicLong`，`onTagsReload` 中 `recipeVersion.incrementAndGet()`；`ProductiveBeesGenesisJEI` 和 `PbRecipeProcessor.checkRecipeVersion` 配套修改
- **P3: onServerStopped 空实现**：服务器停止时未清理静态缓存
  - **问题**：`onServerStopped` 方法体为空，未清理 `CentrifugeRecipeIndex`、`BeeInfoHelper`、`MyriadCreationsEventHandler` 等静态缓存
  - **修复**：调用 `CentrifugeRecipeIndex.clear()`、`BeeInfoHelper.invalidateCache()`、`MyriadCreationsEventHandler.clearAllCaches()`；`CentrifugeRecipeIndex` 新增 `clear()` 方法；`MyriadCreationsEventHandler` 新增 `clearAllCaches()` 公开方法
- **P3: SFM 依赖 versionRange 过于宽泛**：`[0,)` 会匹配到老版 Forge 上的不兼容版本
  - **修复**：改为 `[0.3.0,)`，0.3.0+ 为 NeoForge 1.21.1 分支首个稳定版本

### 完善

- **P3: mixins.json 缺 refmap 字段**：与 `productivebeesgenesis.iris.mixins.json` 不一致
  - **修复**：添加 `refmap`、`minVersion`、`target` 字段，保持两个 mixins.json 配置一致
- **P3: @EventBusSubscriber 显式声明 bus**：评估后保持原状
  - **决策**：NeoForge 1.21.1 中 `bus` 参数已被标记为 `@Deprecated`，自动判定机制取代显式声明，添加 `bus = Bus.MOD` 会触发编译警告，故保持原状并在 Javadoc 中说明决策
- **P3: 客户端实例字段非 volatile**：评估后保持现状
  - **决策**：`BeeSelectionCache`、`AeItemKeyCache`、`MekCentrifugeSlotManager` 已在 Javadoc 中清楚标注线程安全约束（"客户端 GUI 单线程访问"、"服务端 tick 线程独占访问"），无需添加 volatile
- **P3: PacketDistributor.sendToAllPlayers 范围过广**：评估后保持现状
  - **决策**：`MekCentrifugeBlock.setPlacedBy` 中调用 `PacketSyncSecurity` 同步安全拥有者信息，参考 Mekanism 自身实现，安全信息需要所有可能看到的玩家都知道（玩家从远处走来时也需正确判断权限），放置方块是低频事件，保持现状
- **P3: javax.annotation 过时包**：评估后保持现状
  - **决策**：`javax.annotation.ParametersAreNonnullByDefault` 是 JSR-305 标准，被 Minecraft 自身大量使用，`org.jetbrains.annotations` 不提供包级非空注解，迁移会导致与 Minecraft API 不一致
- **P3: ModLanguageProvider 未使用**：评估后保持现状
  - **决策**：`package-info.java` 已明确说明"保留类供未来参考"，作为 datagen 包中的参考实现，不增加运行时开销

## [1.5.4] - 2026-07-04

### 修复

- **P1: CentrifugeRecipeIndex 多 volatile 字段非原子替换**：蜜脾索引和蜜脾块索引在 rebuild 时非原子写入
  - **问题**：两个 volatile 字段依次写入存在可见性窗口，其他线程可能读到 `index` 已更新但 `combBlockIndex` 仍为旧值的状态
  - **修复**：引入 `RecipeIndexSnapshot` 不可变 record 封装两个 Map，通过单一 volatile 引用原子替换，使用 `Map.copyOf()` 创建不可变 Map
- **P1: MyriadCreationsEventHandler 多 volatile 字段非原子更新**：蜜蜂类型缓存与模板数组在 rebuild 时非原子写入
  - **问题**：`cachedBeeTypes`、`cachedHoneycombTemplates`、`cachedCombBlockTemplates` 三个 volatile 字段依次写入，跨字段一致性无保证
  - **修复**：引入 `BeeTypeCacheSnapshot` 不可变 record 封装三个字段，通过单一 volatile 引用原子替换；聚合生成方法使用单次 `snapshot()` 读取
- **P1: BeeRecipeReloader 多 volatile 字段非原子写入**：延迟重试上下文非原子清空
  - **问题**：`pendingRetry`、`pendingRecipeManager`、`pendingRegistryAccess` 三个 volatile 字段非原子写入，`clearPendingRetry` 清空字段间存在不一致窗口
  - **修复**：引入 `PendingRetryContext` 不可变 record 封装 recipeManager 和 registryAccess，通过单一 volatile 引用原子替换
- **P1: MyriadSelectionCache 循环更新多索引位竞态**：缓存重建循环无锁保护
  - **问题**：多线程同时触发缓存重建时，循环内对不同索引位的写入非原子，可能部分索引位版本回退
  - **修复**：对整个重建循环使用 `synchronized(MyriadSelectionCache.class)` 保护，配合双重检查模式避免无竞争时的同步开销
- **P0: BeeInfoHelper.getBeeProduce O(N²) 性能问题**：GUI 打开时全量遍历配方
  - **问题**：N 个蜜蜂 × N 个配方的 O(N²) 复杂度，蜜蜂类型数百时 GUI 卡顿
  - **修复**：引入 `AdvancedBeehiveRecipeIndex` 不可变 record 封装 `beeType -> recipe` 静态索引，首次查询时遍历所有配方并从 `BeeIngredient.getBeeType()` 提取 beeType 建立索引，后续查询 O(1) 命中，GUI 打开从 O(N²) 降为 O(N)
- **P0: PbRecipeProcessor.ticksForBaseCache 每 tick 清空重建**：HashMap 桶数组频繁分配
  - **问题**：256× 加速场景下每 tick clear + 重新 put 产生高频 GC 压力
  - **修复**：改为 20 tick（1 秒）时间窗口缓存模式，时间窗口过期时才 clear + 重新填充，升级变更后最多 20 tick 内自动反映新值
- **P2: Ae2GridNodeManager check-then-act 竞态**：节点创建和销毁的竞态条件
  - **问题**：`prepareNode` 和 `destroyNode` 均存在 check-then-act 竞态，多线程同时调用时可能创建重复节点导致 AE2 网格泄漏
  - **修复**：对 check-then-act 块加宿主级锁 `synchronized(host)`，两个方法使用同一把锁保证互斥
- **P2: JadeAe2StatusProvider NBT key 命名不一致**：使用缩写前缀
  - **问题**：`NBT_STATE = "pbg_grid_node_state"` 使用缩写 `pbg_` 前缀，违反项目统一的 `productivebeesgenesis_` 命名约定
  - **修复**：改为 `"productivebeesgenesis_grid_node_state"`，与其他 NBT key 保持一致
- **P2: BeeHelperMixin 辅助方法缺失 modid$ 前缀和 @Unique**：违反 Mixin 命名规范
  - **问题**：3 个辅助方法（`makeKey`、`clearThrottleIfTickChanged`、`mergeItemStacks`）未标注 `@Unique` 且未使用 `productivebeesgenesis$` 前缀
  - **修复**：重命名方法添加前缀，添加 `@Unique` 注解，所有调用点同步更新
- **P2: TileComponentEjectorMixin configMismatchWarned 非原子复合操作**：volatile boolean 的 check-then-act 非原子
  - **问题**：多线程同时通过 if 检查后可能多个线程都执行日志记录，导致日志重复输出
  - **修复**：改为 `AtomicBoolean` + `compareAndSet(false, true)` 模式，保证"只警告一次"语义

### 删除

- **遗留 PB 风格离心机资源**：删除 `assets/productivebees/` 下全部 14 个遗留资源文件
  - 6 个模型 JSON：`idle.json`、`running.json`、`heated_idle.json`、`heated_running.json`、`powered_idle.json`、`powered_running.json`
  - 8 个纹理文件：`bottom.png`、`grindstone_side.png`（含 `.mcmeta`）、`heated_top.png`、`inner.png`、`powered_side.png`、`side.png`、`top.png`
  - **原因**：早期开发版本测试制作的 PB 原版风格离心机资源，后改为 Mek 风格机器，这些遗留资源已完全孤立（代码和 blockstate 均引用 `productivebeesgenesis:block/mekanism_centrifuge`），不再需要
  - 项目未来只实现 Mek 风格机器

### 变更

- **THIRD_PARTY_LICENSES.md 更新**：移除 Productive Bees 条目中"重写了离心机方块的部分模型和纹理"的描述
  - 原描述引用的 `assets/productivebees/` 目录已删除，本模组不再修改 PB 资产，仅引用其 API
- **统一不可变快照模式**：引入"不可变 record + 单一 volatile 引用原子替换"作为多 volatile 字段的标准修复模式
  - 适用于 `CentrifugeRecipeIndex`、`MyriadCreationsEventHandler`、`BeeRecipeReloader`、`BeeInfoHelper` 等多处
  - 保证读线程看到一致状态，重建期间旧快照仍可服务读请求

### SemVer 合规性

- **版本号重新编号**：按 [SemVer](https://semver.org/lang/zh-CN/) 严格规则重新编号 v1.4.0 起的所有版本
  - 详见 CHANGELOG 顶部"版本号重新编号说明"
  - git tag 和 GitHub Release 已同步重新编号
- **JAR 文件名修复**：重新构建 7 个历史版本（v1.3.4 ~ v1.5.3）的 JAR 文件
  - 修复 Release 标题版本号与 JAR 文件名版本号不匹配的问题（例如 v1.3.4 附带的 jar 名为 `productivebeesgenesis-1.4.0.jar`）
  - 在对应历史 commit 上重新构建，保证 JAR 内部 `mod_version` 与文件名一致

## [1.5.3] - 2026-07-04

### 修复

- **P1: AE2 节点销毁调用顺序错误**：4 个 TileEntity 文件 8 处 `setRemoved`/`onChunkUnloaded` 方法
  - **问题**：`holder.clear()` 在 `destroyNode()` 之前执行，导致 holder 中 `ae2GridNode` 和 `aeItemKeyCache` 被置 null，随后 `destroyNode` 通过 `getAe2GridNode()` 获取时返回 null，在 instanceof 检查处短路返回
  - **影响**：`node.destroy()` 未执行导致 AE2 网格泄漏（幽灵节点、频道计数错误），`cache.clear()` 未执行导致 AeItemKeyCache 内存泄漏
  - **修复**：调整调用顺序为 `destroyNode(this)` → `holder.clear()`，确保节点销毁和缓存清理真正执行
  - 涉及文件：`TileEntityMekCentrifuge`、`TileEntityMekCentrifugeFactory`、`TileEntityExtraMekCentrifugeFactory`、`TileEntityEMExtraMekCentrifugeFactory`

### 安全加固

- **网络包频率限制**：为 `FilterConfigSyncPayload` 添加每玩家 3 秒冷却
  - 采用 `ConcurrentHashMap<UUID, AtomicLong>` + CAS 模式，借鉴 `RateLimitedItemHandler` 的并发安全思路
  - CAS 推进时间戳防止并发包穿透冷却窗口
  - 惰性清理策略：每处理 64 个包清理一次超过 5 分钟未活动的条目，不依赖玩家退出事件 API
  - 拒绝时通过 `sendSystemMessage` 通知玩家剩余秒数
  - 补充中英文语言文件 `productivebeesgenesis.config.sync.rate_limited`

### 文档

- 新增 `findings-v6.md`：v6 调查阶段发现的 2 项关键问题及修复方案
- 更新 `future-optimization.md`：标记用户排除项（API 暴露、配置扩展、多语言、JEI 增强），标记频率限制已完成

## [1.5.2] - 2026-07-04

### 新增

- **第三方素材授权完善**：补充 Re:Avaritia 的真实授权信息
  - 作者: Nova-Committee 团队（cnlimiter、Asek3、MikhailTapio）
  - 许可证: 代码 MIT，资产 CC BY-NC-SA 4.0
  - 源码: https://github.com/Nova-Committee/Re-Avaritia/tree/neo/1.21.1
  - 合规说明: 本模组仅参考渲染参数而未复制资产，不触发 CC BY-NC-SA 4.0 传染条款

### 重构

- **架构优化: Ae2OutputStateHolder 组合类**：消除三个工厂类约 60 行 AE2 字段/方法重复
  - 新增 `Ae2OutputStateHolder` 封装 3 个 AE2 字段（`ae2GridNode`、`aeItemKeyCache`、`ae2NodePending`）
  - `IAe2OutputHost` 接口的 4 个纯字段访问方法改为 default 委托实现
  - 三个工厂类 + `TileEntityMekCentrifuge` 各删除 3 字段 + 4 方法实现，新增 1 holder 字段
- **架构优化: AbstractVerticalScrollBar 基类**：消除滚动条逻辑约 115 行重复
  - 新增 `AbstractVerticalScrollBar` 抽象基类（229 行），采用模板方法模式
  - `BeeSelectionScrollBar` 从 175 行降至 108 行（减少 67 行）
  - `FilterListDragHandler` 从 262 行降至 214 行（减少 48 行）
  - 差异点通过抽象方法处理（`getMinThumbHeight` 16 vs 20、`getScrollBarMargin` 命名差异）
- **架构优化: JeiRecipeHider 工具类**：消除 JEI 反射隐藏逻辑约 118 行重复
  - 新增 `JeiRecipeHider` 工具类（162 行），抽取 3 个反射隐藏方法
  - `ProductiveBeesGenesisJEI` 从 442 行降至 324 行（减少 118 行）
  - 4 个重复 try-catch 块改为循环 + 参数化调用

### 性能优化

- **FilterListBeeInfoCache LRU + clear**：防止长期运行内存累积
  - 三个 Map 改为 LRU LinkedHashMap（容量上限 256，自动淘汰最久未访问条目）
  - 新增 `clear()` 方法，在 `FilterListScreen.onClose()` 中调用主动释放内存
- **JEI 反射缓存**：`isRecipeForBeeType` 的 `Class.getMethods()` 结果缓存到 `ConcurrentHashMap`
  - 避免每个配方重复反射获取方法数组
- **集合容量优化**：多处 `new HashMap<>()` / `new ArrayList<>()` 指定初始容量
  - `Ae2OutputPusher`：`new HashMap<>(entries.size())`
  - `AbstractCombEventHandler`：`new ArrayList<>(beeData.size())`
  - `TransformUtils`：改用 `EnumMap<>(ItemDisplayContext.class)`（枚举键专用）
  - `RandomHoneycombSelector`：`new HashMap<>(types.size() * 2)`（乘 2 避免 0.75 扩容）

### 修复

- **BeeConfigReloadMixin 日志级别不当**：`info` + 完整异常栈过重
  - 改为 `warn` 级别且仅传 `e.getMessage()`，保留日志可见性同时避免异常栈刷屏
- **TileComponentEjectorMixin 配置警告刷屏**：配置错误时每次 `outputItems` 都打印 warn
  - 添加 `static volatile boolean` 标志位，仅在首次触发时记录 warn，后续静默调整

### 删除

- **MekCompatHooks 死代码**：删除 3 个无调用方的方法
  - `getEMETierProcesses`、`getEMETierImageWidth`、`getEMETierInventoryLabelX`

## [1.5.1] - 2026-07-04

### 新增

- **第三方素材授权声明**：新增 `THIRD_PARTY_LICENSES.md`，声明所有引用的第三方资产与代码授权
  - 涵盖 Productive Bees、Re:Avaritia、Minecraft、NeoForge、Mekanism、AE2、Iris、Jade、JEI、SFM 共 10 个项目
  - 标注 PB 离心机模型/纹理覆盖的授权来源
  - 标注 Re:Avaritia mask 纹理的使用授权
- **network 包 package-info**：补充 `network/package-info.java`，完善包级 Javadoc 文档

### 修复

- **P0: AbstractCombEventHandler 线程安全 Bug**：`CentrifugeRecipeTestInputHolder` 静态共享实例竞态条件
  - `TEST_COMB` 和 `HANDLER` 是静态共享实例，多线程同时调用 `hasCentrifugeRecipe` 时同时修改 `bee_type` 组件和槽位
  - 改为 `ThreadLocal` 模式，每线程持有独立的 Handler 与 ItemStack 实例
- **P1: BeeHelperMixin 静态字段缺少 Mixin 前缀**：3 个静态字段添加 `productivebeesgenesis$` 前缀和 `@Unique` 注解
  - `FULL_32_BIT_MASK` → `PRODUCTIVEBEESGENESIS$FULL_32_BIT_MASK`
  - `THROTTLE_COUNTERS` → `productivebeesgenesis$THROTTLE_COUNTERS`
  - `LAST_THROTTLE_TICK` → `productivebeesgenesis$LAST_THROTTLE_TICK`
- **P1: BeeHelperMixin THROTTLE_COUNTERS.clear() 并发不安全**：改为 `entrySet().removeIf(...)` 仅清理过期 tick 条目
  - 原 `clear()` 与并发 `computeIfAbsent` 冲突会导致刚插入的条目被清空
  - 新逻辑保留当前 tick 数据，仅清理高 32 位 ≠ 当前 tick 的条目
- **P1: TileComponentEjectorCooldownMixin 字段前缀 + 原子操作**
  - `CONFIG_REFRESH_INTERVAL` 添加前缀和 `@Unique` 注解
  - 3 个 `volatile int` 字段改为 `final AtomicInteger`，`++`/`--` 改为 `decrementAndGet()`
- **P1: ItemInfinitySwordReplica NBT key 缺少模组前缀**
  - `MODE_TAG = "mode"` → `"productivebeesgenesis_mode"`
  - `KILL_MODE = "infinity_sword_kill"` → `"productivebeesgenesis_kill_mode"`
- **P1: FilterConfigSyncPayload 单条字符串长度未限制**：限制每条字符串最大 256 字符，防止恶意客户端 OOM
- **P1: PbRecipeProcessor NBT key 违反命名规范**：`"PbProgress"` → `"productivebeesgenesis_pb_progress"`
- **P1: gradle.properties 包含开发者绝对路径**：移除 `org.gradle.user.home=E:/Gradle/.gradle`
- **P1: BeeConfigApplier TOCTOU 竞态**：`data` 引用获取移入 `synchronized` 块内
- **P1: MyriadCreationsEventHandler 字段命名违规**：3 个 `static volatile` 字段改为 camelCase，1 个 `static final` 改为 UPPER_SNAKE
- **P1: CreativeTabEventHandler 注释与实现不符**：改为真正遍历副本（`new ArrayList<>(entries)`）
- **P1: IrisConfigPlugin LoadingModList.get() 缺少异常保护**：添加 try-catch，异常时返回 `Boolean.FALSE`
- **P1: 16 个 Mixin 类缺少 abstract 修饰符**：全部添加 `abstract`
- **P1: 4 个 datagen 类未声明 final**：`ModRecipes`、`ModLootTables`、`ModBlockTagsProvider`、`ModLanguageProvider` 添加 `final`
- **P2: PbRecipeCompleter slotIndex 跳过零输出时不递增**：在 `continue` 前增加 `slotIndex++`
  - 修复副产物可能错误占据主输出槽的 bug
- **P2: TileEntityMekCentrifugeFactory 能量计算可能负值**：添加 `Math.max(0, ...)` 保护
  - 修复 `super.onUpdateServer()` 内部回填能量导致负值的 bug
- **P2: MekCentrifugeSlotManager endOutputBatch 下溢保护**：`batchDepth < 0` 时重置为 0 并记录 warn 日志
- **P2: 多处 isValidInputItem 未检查 level == null**：三个工厂类添加 `level == null` 检查
- **P2: AbstractClientCombEventHandler poseStack.popPose() 不在 finally 块**：移到 finally 块
  - 修复异常时 GL 栈状态泄漏的 bug
- **P2: CosmicShaders 三个 shader 注册共用 try-catch**：拆分为独立 try-catch，单个失败不影响其他
- **P2: ModPayloads validateAndDeduplicate O(N²) 性能**：改用 `LinkedHashSet`，O(N²)→O(N)

### 变更

- **Mixin 命名规范统一**：所有 Mixin 新增字段使用 `productivebeesgenesis$` 前缀 + `@Unique` 注解
- **资源文件缩进统一**：11 个 JSON/mcmeta 文件统一为 2 空格缩进
- **IBlockEntityExtensionMixin remap 评估**：保留 `remap = false`，添加注释说明原因（NeoForge 接口 default 方法非 Mojang 映射）

### 删除

- **gradle.properties 开发者配置**：移除 `org.gradle.user.home=E:/Gradle/.gradle` 绝对路径

## [1.5.0] - 2026-07-04

### 新增

- **Jade AE2 设备在线状态显示**：离心机方块在 Jade 悬停面板中显示 AE2 网络连接状态
  - 完全复刻 AE2 原版 `GridNodeStateDataProvider` 的显示设计与翻译键（DeviceOffline/NetworkBooting/DeviceMissingChannel/DeviceOnline）
  - 服务端通过 NBT 同步状态 ordinal，客户端不引用 AE2 类，避免类加载依赖
  - 仅当方块实体实现 `IAe2OutputHost` 且 AE2 集成已启用时显示
  - 注册到全部 4 种离心机方块实体类型（基础/原版工厂/ME 工厂/EME 工厂）

### 性能优化

针对 256× 加速 + 32 速度升级 + 14 台离心机的极端高压环境进行深度优化，TPS 从 11.32 提升至 20.01（+76.6%），本模组占比从 32.64% 降至 29.13%。

- **InputValidationCache 指纹键优化**：从 9.87% 降至 4.73%
  - 新增 `InputFingerprint` record（Item + beeType）替代 `ItemStack.isSameItemSameComponents`，避免 owo `DerivedComponentMap.hashCode()` 和 `PatchedDataComponentMap.hashCode()` 的高昂开销
  - 对 configurable_honeycomb / configurable_comb_block 仅提取 bee_type 组件，跳过全组件哈希
  - 三级缓存：identity 短路 → 指纹比对 → 完整校验
  - TTL 从 20 tick 延长到 100 tick（减少 80% 的 validator 调用）
- **RecipeCacheManager 轻量 CacheKey**：`computeKey` 从 1.85% 降至 1.02%
  - `CacheKey` record 从 `(Item, int componentHash)` 改为 `(Item, ResourceLocation beeType, int componentHash)`
  - 对 configurable_honeycomb/comb_block 用 beeType 替代 componentHash，避免 `hashItemAndComponents` 调用
- **Ae2OutputPusher 同 key 批量合并**：从 9.77% 进一步优化
  - 扫描所有进程的 primary/secondary/tertiary 输出槽，按 AEItemKey 分组
  - 对每个 AEItemKey 调用一次 `poweredInsert`（合并 totalCount），减少 extendedae_plus `InfinityBigIntegerCellInventory.getUUID` 等昂贵操作的调用次数
  - 槽位数 ≤ 3 时直接逐槽推送，避免 HashMap 分配开销
  - 部分成功时按顺序清空槽位，异常时回滚所有相关槽位
- **TileComponentEjectorCooldownMixin 配置缓存**：消除 256× 加速下每 tick 32256 次配置读取
  - 9 项配置缓存到 `@Unique volatile` 实例字段，每 100 tick 刷新一次
  - 命中缓存时配置读取降为 0 次/tick（14 台离心机 × 256 加速 = 3584 次方法调用无配置读取）
- **PbRecipeCompleter IdentityHashMap**：避免 `ItemStack.hashCode()` 全组件遍历
  - `pendingOutputs` 从 `LinkedHashMap` 改为 `IdentityHashMap`
  - key 来自缓存的 `pendingRecipeOutputs.entrySet()`，同一配方 key 实例稳定不变，引用相等即可
  - 消除 256× 加速下每 tick 约 10752 次 `ItemStack.hashCode()` 调用

### 修复

- **Ae2OutputPusher 异常吞没与产物复制风险**：
  - `catch (Throwable t)` 完全吞没异常且无日志 → 改为 `catch (Exception e)` + 限流日志（每 1024 次记录一次）
  - `InterruptedException` 恢复中断标志
  - `poweredInsert` 异常后槽位可能已部分接收 → 异常时重新读取槽位检查 count 是否异常增加，防止产物复制
- **MyriadCreationsEventHandler 内存泄漏**：`onServerStopped` 未清理静态缓存字段
  - 添加 `CACHED_BEE_TYPES`、`CACHED_HONEYCOMB_TEMPLATES`、`CACHED_COMB_BLOCK_TEMPLATES`、`lastCacheUpdateTick`、`MyriadSelectionCache.invalidate()` 的完整清理
- **OutputSlotFlagManager NPE 与 batchDepth 下溢**：
  - `tertiary` 槽位未做空检查 → 添加 null 检查与 `secondary` 一致
  - `endBatch` 未配对调用时 `batchDepth` 变为 -1 → 添加 `<= 0` 保护防止标志位永久失效
- **BlockEntityItemStackHandlerDebounceMixin 强耦合**：依赖另一个 Mixin 实现 `IInventoryDirtyDebouncer`
  - `instanceof` 检查改为双重检查（`AdvancedBeehiveBlockEntity` + `IInventoryDirtyDebouncer`），防止 Mixin 加载失败时 ClassCastException
- **Ae2OutputPusher SlotEntry 日志缺失**：`SlotEntry` 构造函数未接收 `process` 和 `slotIdx` 参数（固定为 -1），导致异常日志无法定位槽位
  - 构造函数补充参数传递，异常日志可准确显示进程索引和槽位索引

### 变更

- **移除性能监控功能**：`PerformanceMonitor.java`、`PerfCommand.java` 及相关配置项
  - 原因：监控本身在高频调用下产生不可忽视的资源占用，与性能优化目标相悖
  - `CommonConfig` 中保留空 push/pop 占位防止配置失效
- **OutputSlotFlagManager 延迟刷新模式**：减少 SFM `extractItem` 序列的 O(N × processes) tick 开销
  - `onSlotChanged()` 仅设置 dirty 标志，`updateAll()` 延迟到下次读取时执行
  - 配合 `flushDirty()` 实现"写时标记、读时刷新"的批量优化


## [1.4.1] - 2026-07-02

### 修复

- **P0: 7 处 LOGGER.debug 违规**：违反项目硬约束"生产代码无 debug 级日志"
  - `BeeRecipeReloader` — 配置未加载提示从 `debug` 改为 `info`（启动阶段信息）
  - `BeeConfigApplier` — 配置未加载提示从 `debug` 改为 `info`（启动阶段信息）
  - `ProductiveBeesGenesisJEI`（5 处）— 反射隐藏配方失败从 `debug` 改为 `warn`（潜在兼容性问题）
- **P1: JEI 静默异常吞没**：`ProductiveBeesGenesisJEI.isMyriadCreationsRecipe` 中 `catch(Exception e)` 仅注释 `// 忽略反射错误` 未记录日志，补充 `warn` 级别日志便于排障
- **P1: 缩进不一致**：`MekCentrifugeFactoryHelper.processPbRecipesAndUpdate` 中 `if (input.isEmpty())` 块内注释和代码缩进层级错误，统一为正确 Tab 缩进
- **P2: 类型宽化映射澄清**：`BeeRecipeReloader.createBiomeHolderSetFromString` 中 `.map(named -> named)` 并非恒等映射，而是将 `Optional<Named<Biome>>` 宽化为 `Optional<HolderSet<Biome>>` 以使 `orElse` 类型匹配，添加显式类型见证和注释说明
- **P2: 残留测试文件清理**：删除 `mek/test_write.txt`（开发遗留文件）


## [1.4.0] - 2026-07-02

### 修复

- **配方禁用失效（首次启动）**：修复首次进入世界时木棍转化配方依然有效的问题
  - 新增延迟重试机制：当配置未加载时，在服务器 tick 中等待配置就绪后自动应用配方修改
  - 使用 `volatile` 标志位实现 O(1) 快速检查，正常游戏过程中几乎零开销
  - 最多重试 60 次（约 3 秒），超时后放弃并记录警告
- **代码规范**：`MekCentrifugeContainerRegistrar` 移除已弃用的 `bus = EventBusSubscriber.Bus.MOD` 参数（NeoForge 1.21+ 不再需要）
- **模组名称乱码**：`neoforge.mods.toml` 中 `displayName` 改为英文 `Productive Bees Genesis`，避免中文编码问题
- **MEK离心机描述显示**：修复基础MEK离心机 Shift+N 描述显示问题，使用 `descriptionLang()` 替代 `lang()`
- **万象创世蜜蜂刷怪蛋**：禁用万象创世时，从创造模式物品栏和JEI中隐藏其刷怪蛋

### 变更

- **ME/EME 等级机器名称颜色同步**：MEK Extra 和 Evolved MEK Extra 等级的离心机工厂在物品栏中显示的名称颜色与原模组对应等级特效一致
  - ME ABSOLUTE: 黄绿色 (237, 238, 70)
  - ME SUPREME: 红色 (166, 0, 2)
  - ME COSMIC: 青色 (75, 248, 255)
  - ME INFINITE: 品红色 (247, 135, 255)
  - EME 等级使用动态 RGB 渐变效果
- **万象创世蜜蜂启用开关**：新增 `myriadCreationsEnabled` 配置项（默认 `true`）
  - 设置为 `false` 可完全禁用万象创世蜜蜂及其相关功能，仅保留 MEK 离心机功能
  - 禁用时自动隐藏相关配方、物品、JEI条目和创造模式物品栏条目
- **MEK离心机描述更新**：
  - 基础离心机："一台用于加热离心处理蜜脾和蜜脾块的机器，由于温度很高，也可以处理熔炼炉的配方"
  - 所有工厂等级："一台经过工业化升级的机器，能够一次同时处理多个原料"
- **创造模式物品栏排序**：重新组织机器顺序，按等级分组（原版→ME→EM→EME）
- **EME等级中文翻译优化**：
  - "寰宇致密" → "宇宙致密"
  - "悖论多元宇宙" → "无限多元"

## [1.3.4] - 2026-07-01

### 修复

- **P0: iris 依赖缺少 versionRange**：`neoforge.mods.toml` 中 iris 可选依赖缺少版本范围，补充 `versionRange="[1.8.8,)"`
- **P0: 5 处 LOGGER.debug 违规**：违反项目硬约束"生产代码无 debug 级日志"
  - `AbstractCombEventHandler` — 异常路径改为 `warn`
  - `MyriadCreationsEventHandler` — 非异常路径调试日志直接删除
  - `BeeInfoHelper`（2 处）— 异常路径改为 `warn`
  - `BeeRecipeReloader` — 异常路径改为 `warn`
- **P0: mineable 标签文件缺失**：运行 `runData` 生成标签 JSON
  - `data/minecraft/tags/block/mineable/pickaxe.json` — 包含 18 个离心机/工厂方块
  - `data/minecraft/tags/block/mineable/hoe.json` — 包含 `infinitycreation_comb_block`
- **P0: generated lang 与主 lang 键重叠**：`ModLanguageProvider` 生成的 configuration.* 键与主 lang 文件重复，触发 `DuplicatesStrategy.EXCLUDE`
  - 修复：移除 `ModLanguageProvider` 注册，主 lang 文件作为单一真相源（307 键全覆盖）
  - 删除过期的 generated lang 文件（仅含 1 个 stale 键）

### 变更

- **P1: README 包结构描述修正**：`client/gui/` → `client/screen/`，`client/model/` → `client/render/cosmic/`，补充 `capability/`、`command/`、`network/`、`mek/`、`mixin/` 等遗漏包
- **P1: myriadcreations_comb 设计决策注释澄清**：`ServerConfig.produceOutputItem` 注释修正为"使用 configurable_honeycomb 时会自动附加 bee_type 组件"
- **P2: 文件超长拆分**（所有 Java 文件均 < 500 行，符合项目硬约束）
  - `FilterListScreen`（719→430 行）— 抽取 `FilterListInputHelper`、`FilterListActionBar`、`FilterListModeSelector`
  - `TileEntityMekCentrifuge`（612→392 行）— 抽取 `MekCentrifugeSlotManager`、`MekCentrifugeSaveHandler`、`MekCentrifugeTickHandler`
  - `BeeSelectionScreen`（584→411 行）— 抽取 `BeeSelectionSearchBar`、`BeeSelectionGroupRenderer`、`BeeSelectionScrollBar`
  - `TileEntityMekCentrifugeFactory`（517→429 行）— 抽取 `FactoryLayoutHelper`
  - `ServerConfig`（403→253 行）— 抽取 `CentrifugeConfigSection`、`BeeAttributeConfigSection`
- **P2: GUI 颜色常量抽取**：新建 `GuiColors.java`，替换 6 个文件约 65 处硬编码 ARGB 颜色值
- **P2: MyriadSelectionCache 抽取**：从 `MyriadCreationsEventHandler` 抽取类型选择缓存逻辑（SRP），volatile 字段保证多工厂共享安全
- **P3: displayName 属性化**：`neoforge.mods.toml` 的 `displayName` 改为 `${mod_name}`，让 `gradle.properties` 的 `mod_name=资源蜜蜂：创世` 生效
- **P3: 魔法数字命名化**
  - `CosmicRenderTypes` — `0x200000` → `BUFFER_SIZE_2MB`
  - `BeeHelperMixin` — `0xffffffffL` → `FULL_32_BIT_MASK`

### 删除

- **P1: 25 个未使用翻译键**：从 `en_us.json`/`zh_cn.json` 删除（含 3 个 JEI 键 `energy_per_tick`/`mek_centrifuge_recipes`/`processing_time`，经 Grep 验证代码无引用）
- **P1: 19 个空目录**：Java 源码空包（`recipe/`、`screen/`、`block/entity/` 等）+ 资源空目录（`assets/.../atlases/`、`textures/gui/bar/` 等）
- **P1: 2 个空配方子目录**：`data/productivebees/recipe/centrifuge/thermal/`、`data/productivebees/recipe/thermal/centrifuge/`
- **P2: 7 个开发遗留文件**：`bee_attr.txt`、`bee_creator.txt`、`config_bee.txt`、`dependencies.txt`、`run_output.txt`、`apply_perf_patch.ps1`、`fix_debug_logs.py`
- **P2: docs/ 目录**：`CODE_REVIEW_FINDINGS.md`、`CODE_REVIEW_SPEC.md`、`findings.md`（审查文档不入库）
- **P2: 2 个开发辅助脚本**：`check_indent.ps1`、`fix_slot.py`（加入 .gitignore）
- **P2: generated lang 文件**：过期 stale 文件（仅 1 键，与主 lang 重复）

## [1.3.3] - 2026-07-01

### 修复

- **CRITICAL: 可选依赖类加载崩溃**：未安装 ME/EME 时直接引用其 `.class` 触发 `NoClassDefFoundError`
  - `MekCentrifugeContainerRegistrar` — 为 ME/EME 类引用添加 `isXxxLoaded()` 模组加载状态守卫
  - `MekCompatHooks` — 实现反射类缓存（`volatile` + 双重检查锁），修复 `isConfigurationDataCompatible` 中 EME 类加载崩溃
  - `ItemBlockMekCentrifuge` — 修复 Tooltip 和 DataComponent 初始化中的 EME 类加载崩溃
- **MEDIUM: 多进程 unpause 恢复延迟**：`FactoryPbContextDelegate` 中 `sortingMarkedThisTick` 错误抑制了不同进程的 `unpause` 调用
  - 修复：将 `unpause` 移出去抖块，使其每进程独立触发
- **MEDIUM: 万象创世能量提取未批量化**：`MyriadCreationsHandler` 循环内逐次 `extract` 触发大量 listener 回调
  - 修复：引入局部 `availableEnergy` + `opsRun` 计数器，循环后批量 `extract`，与 `PbRecipeProcessor` 优化一致

### 变更

- **LOW: GUI 布局常量提取**：`FactoryLayoutHelper` 提取 `INVENTORY_SLOT_PITCH = 20` 常量区分槽位宽度与间距
- **LOW: Javadoc 术语修正**：`MekCentrifugeFactoryHelper` 中 "CAS" 术语修正为 "状态守卫"

## [1.3.2] - 2026-07-01

### 修复

- **P0 吞异常修复**：4 处 `catch (Exception e)` 块未记录日志直接返回，已全部补充 debug 级别日志
  - `AbstractCombEventHandler.hasCentrifugeRecipe` — 检查异常时保守返回 true，补充日志
  - `BeeRecipeReloader.isMyriadcreations` — 万象创世类型检查异常，补充日志
  - `BeeInfoHelper.isBeeTypeExists` — 蜜蜂类型存在性检查异常，补充日志
  - `BeeInfoHelper.parseBeeType` — ResourceLocation 解析异常，补充日志

### 变更

- **代码风格统一**：5 个文件的空格缩进统一为 Tab 缩进，符合 minecraft-code-standards 规范
  - `CustomConfigScreenFactory`、`ServerConfigScreen`、`ModBlockTagsProvider`、`ModLanguageProvider`、`AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin`
- **文件超长拆分**：`FilterListScreen`（900→722 行）进一步抽取两个组合类
  - `FilterListBeeInfoCache`（98 行）— 蜜蜂图标/名称/产物信息缓存
  - `FilterListSelectionManager`（120 行）— 批量选择与删除逻辑
- **全量代码审计**：完成 163 个 Java 文件的深度审查，覆盖 7 大维度（风格/架构/线程安全/性能/异常处理/MC规范/合规性），审查发现记录在 `docs/findings.md`
- **合规性审查通过**：MIT 许可证完整、无禁止内容、无 Minecraft 原版资产、无恶意代码、无用户数据收集

## [1.3.1] - 2026-07-01

### 修复

- **P0 崩溃修复**：三个工厂类（`TileEntityMekCentrifugeFactory`、`TileEntityExtraMekCentrifugeFactory`、`TileEntityEMExtraMekCentrifugeFactory`）的 `delegate` 初始化从构造函数移到 `addSlots()` 方法中，解决进入游戏世界时的 `NullPointerException` 崩溃
  - 问题原因：`addSlots()` 在 `super()` 调用期间被触发，但 `delegate` 在 `super()` 之后才初始化，导致为 null
  - 修复方案：在 `addSlots()` 方法开头初始化 `delegate`，此时 tier 和 this 引用都已可用

### 变更

- 更新 `gradle-wrapper.properties` 使用腾讯云镜像
- 更新 `settings.gradle` 和 `build.gradle` 添加国内镜像仓库（阿里云、华为云、腾讯云）
- 更新 `gradle.properties` 设置 `org.gradle.user.home`

## [1.3.0] - 2026-06-30

### 新增
- 自定义网络包 `FilterConfigSyncPayload`：多人游戏 SERVER 配置同步（OP 2 权限校验 + 双语错误提示）
- `resetToDefault` 增加 `ConfirmScreen` 二次确认对话框，防止误操作清空过滤列表
- 配置重载即时失效过滤缓存（`MyriadCreationsEventHandler.invalidateFilterCache`）
- `RateLimitedItemHandler` 采用 CAS 占用配额 + 差额回退模式，杜绝 AE2 异步线程超额提取
- `pack.mcmeta`（pack_format=34）
- 方块标签数据生成（`ModBlockTagsProvider`）：离心机加入 `MINEABLE_WITH_PICKAXE`，蜜脾块加入 `MINEABLE_WITH_HOE`

### 修复
- **P0 严重 Bug**：
  - `infinitycreation_comb_block` 战利品表不掉落（迁移到 1.21 格式，含 `random_sequence`/`bonus_rolls`/`survives_explosion`）
  - 3 个 JEI GUI 翻译键缺失（`energy_per_tick`/`mek_centrifuge_recipes`/`processing_time`）
  - 移除 8 处 debug 日志违规（改为 info/trace/warn）
  - `SimulateContext` ThreadLocal 泄漏（改为实例字段 + WeakReference 混合方案）
  - `CosmicRenderQueue` 渲染状态未重置（finally 块增加 `disablePolygonOffset` + `polygonOffset(0,0)`）
  - 输出槽满时仍消耗能量（循环开始处增加前置检查）
- **配置与 GUI**：
  - `FilterListScreen` 滚动条拖拽错位（`mouseDragged` 后追加 `rebuildEntryButtonsOnly`）
  - `produceOutputMin/Max` 和 `ejectDelay/Active` 跨字段联合校验（自动交换/降级）
  - `BeeSelectionScreen.existingBeeTypes` 改用 `Set<String>`（O(1) 查询）
- **资源文件**：
  - `en_us.json` 重复 `createComb` 键
  - `neoforge.mods.toml` sfm 依赖缺少 `versionRange`
  - `CosmicRenderTypes` 命名冲突（改为 `productivebeesgenesis:cosmic_armor`）

### 变更
- **重复代码消除**：
  - `TileEntityMekCentrifuge` 委托 `PbRecipeProcessor`（1091→697 行）
  - 抽取 `FactoryPbContextDelegate` 组合类（消除三个工厂 293 行重复）
- **文件超长拆分**（全部 ≤ 500 行软上限）：
  - `PbRecipeProcessor`（1088→497 行）→ `PbRecipeFinder`（163）/`PbRecipeCompleter`（227）/`MyriadCreationsHandler`（420）
  - `AbstractCombEventHandler`（685→176 行）→ `RandomHoneycombSelector`（394）/`CombBlockCheckCache`（134）
  - `ModConfig`（570→182 行）→ `ClientConfig`（44）/`CommonConfig`（20）/`ServerConfig`（339）
  - `FilterListScreen`（933→802 行）→ `FilterListDragHandler`（235）/`FilterListClipboardHelper`（193）
  - `BeeSelectionScreen`（790→584 行）→ `BeeSelectionSorter`（204）
- **性能优化**：
  - `ticksForBaseCache` 改为 `HashMap`（单线程约束注释）
  - `RenderUtils.bakeItem` 返回 `ArrayList`（替代 `LinkedList`）
  - `hsvToRgb`/`getColor`/`renderAll`/`applyTransform` 复用数组/字段降低 GC 压力
  - `CentrifugeMixinHelper` 补 `try/catch`，`SelectionCache` 字段改为 `volatile`
- **规范清理**：
  - 删除未使用 import 和 8 个未使用翻译键
  - 替换字符串字面量为 `ProductiveBeesGenesis.MOD_ID` 常量
  - 清理冗余资源目录和项目根目录开发辅助文件
  - 统一语言文件生成机制（`ModLanguageProvider` 分工注释）

## [1.2.1] - 2026-06-30

### 变更
- 将 `devMode`（开发者模式）从客户端配置迁移到服务端配置，更符合其“存档/服务器级调试开关”的语义
- 更新 `devMode` 提示文本：说明该选项仅供模组开发者调试使用，普通用户无需开启

### 修复
- 在 `ModCreativeTabs` 读取服务端 `devMode` 时增加 `ModConfig.SERVER_SPEC.isLoaded()` 保护，避免多人游戏客户端未加载服务端配置时崩溃

## [1.2.0] - 2026-06-30

### 新增
- 为 `PerformanceMonitor` 添加 JMX MBean 注销逻辑，服务器停止时自动注销防止重复加载注册失败
- 为 13 个缺少 `package-info.java` 的包补充完整的包级文档和注解
  - `capability`、`command`、`client/jei`、`client/screen`、`client/render/cosmic`、`client/screen/state`
  - `mixin/accessor`、`mixin/beehive`、`mixin/client`、`mixin/iris`、`mixin/mek`、`mixin/recipe`
  - `util/beehive`
- 新增客户端 `devMode`（开发者模式）配置，开启后创造模式标签页才会显示无尽·创世蜜脾、蜜脾块和寰宇支配之剑（验证）
- 为 `FilterListScreen` 蜜蜂过滤界面增加重置、导入、导出三个工具按钮：
  - 重置：清空列表并将过滤模式恢复为默认 `DISABLED`
  - 导出：将当前过滤列表以 JSON 数组形式写入剪贴板
  - 导入：从剪贴板导入 JSON 数组或逗号/换行分隔的蜜蜂类型，自动校验并去重

### 修复
- **代码风格统一**：将 72 个 Java 文件的 4 空格缩进统一为 Tab 缩进，符合 minecraft-code-standards 规范
- **ProductiveBeesGenesis.java**：修复 DeferredRegister 注册块的空格缩进为 Tab
- **CentrifugeRecipeIndex.java**：`catch (Exception ignored)` 改为记录 debug 级别日志，避免异常被静默吞掉
- **MekCompatHooks.java**：统一异常日志策略，为 `isEMTierAboveOverclocked`、`isMETier`、`isEMETier` 方法的 `ClassNotFoundException` 和 `NoSuchFieldException|IllegalAccessException` catch 块添加 debug 日志
- **过滤配置持久化**：修复 `FilterListScreen` 点击保存后配置未写入 `server.toml` 的问题，保存后显式调用 `ModConfig.SERVER_SPEC.save()`

### 变更
- **高级蜂箱缓存优化**：`isSim()` 与 `hasNectar()` 缓存为默认开启且不可关闭的内部优化，已从服务端配置界面移除对应配置项，避免玩家误关闭导致性能回退

## [1.1.0] - 2026-06-29

### 新增
- 深度性能优化管线：256 倍加速下的离心机配方处理、弹出器限流、配方索引
- Ejector 持续高负载下降频机制
- 单 tick 弹出次数上限配置
- 输出槽内容未变化时跳过 outputItems
- 宇宙着色器渲染系统（Iris 兼容）
- ME/EME 工厂等级支持
- 自定义蜜蜂过滤界面
- JEI 配方集成

### 修复
- 修复基础离心机冗余计算
- 修复工厂版激活状态计数器
- 修复 RecipeCacheManager 键生成
- 修复多个线程安全和内存泄漏问题

## [1.0.0] - 2026-06-15

### 新增
- 万象创世蜜蜂
- Mekanism 离心机集成（基础 + 4 级工厂）
- 宇宙着色器蜜脾渲染
- Mixin 条件加载系统（ME/EME 兼容）
- 配置系统（CLIENT/COMMON/SERVER）
- 数据生成（配方、战利品表、语言文件）
