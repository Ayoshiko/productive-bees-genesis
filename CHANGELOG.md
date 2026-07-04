# Changelog

所有重要变更将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本管理遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.5.0] - 2026-07-04

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

## [1.4.3] - 2026-07-04

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


## [1.4.2] - 2026-07-02

### 修复

- **P0: 7 处 LOGGER.debug 违规**：违反项目硬约束"生产代码无 debug 级日志"
  - `BeeRecipeReloader` — 配置未加载提示从 `debug` 改为 `info`（启动阶段信息）
  - `BeeConfigApplier` — 配置未加载提示从 `debug` 改为 `info`（启动阶段信息）
  - `ProductiveBeesGenesisJEI`（5 处）— 反射隐藏配方失败从 `debug` 改为 `warn`（潜在兼容性问题）
- **P1: JEI 静默异常吞没**：`ProductiveBeesGenesisJEI.isMyriadCreationsRecipe` 中 `catch(Exception e)` 仅注释 `// 忽略反射错误` 未记录日志，补充 `warn` 级别日志便于排障
- **P1: 缩进不一致**：`MekCentrifugeFactoryHelper.processPbRecipesAndUpdate` 中 `if (input.isEmpty())` 块内注释和代码缩进层级错误，统一为正确 Tab 缩进
- **P2: 类型宽化映射澄清**：`BeeRecipeReloader.createBiomeHolderSetFromString` 中 `.map(named -> named)` 并非恒等映射，而是将 `Optional<Named<Biome>>` 宽化为 `Optional<HolderSet<Biome>>` 以使 `orElse` 类型匹配，添加显式类型见证和注释说明
- **P2: 残留测试文件清理**：删除 `mek/test_write.txt`（开发遗留文件）


## [1.4.1] - 2026-07-02

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

## [1.4.0] - 2026-07-01

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
