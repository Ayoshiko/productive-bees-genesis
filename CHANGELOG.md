# Changelog

所有重要变更将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本管理遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
