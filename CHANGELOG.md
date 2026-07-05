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
