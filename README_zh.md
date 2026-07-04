# 资源蜜蜂：创世

![Version](https://img.shields.io/badge/version-1.6.1-blue) ![MC Version](https://img.shields.io/badge/Minecraft-1.21.1-green) ![Loader](https://img.shields.io/badge/NeoForge-21.1.214-orange)

资源蜜蜂（Productive Bees）和通用机械（Mekanism）附属模组，添加了通用机械（Mekanism）风格的离心机，可处理蜜脾和蜜脾块。添加了万象创世蜜蜂，其蜜脾可转化为整合包内所有资源蜜蜂的蜜脾。蜜脾转化功能拥有详细可配置的过滤名单。万象创世蜜蜂的数据包可以在配置文件里自定义修改。
万象创世蜜脾拥有无尽贪婪（Re:Avaritia）模组的寰宇支配之剑（Sword of the Cosmos）同款的星空遮罩材质。

完整变更记录请查看 [CHANGELOG](CHANGELOG.md)。

## 语言

- [English](README.md)
- [中文](README_zh.md)

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 万象创世蜜蜂 | 8秒彩虹渐变，彩虹粒子特效；其蜜脾随机产出其他资源蜜蜂的蜜脾。可通过配置完全禁用。 |
| 星空蜜脾材质 | 万象创世蜜脾拥有无尽贪婪（Re:Avaritia）模组的寰宇支配之剑（Sword of the Cosmos）同款的星空遮罩材质。 |
| MEK离心机 | Mekanism风格离心机，可处理资源蜜蜂的蜜脾和蜜脾块。还可以处理充能冶炼炉 (Energized Smelter) 的配方。|
| 工厂等级 | 覆盖Mekanism、Mekanism Extras 、Evolved Mekanism、Evolved Mekanism Extras 共17个等级。工厂物品名称显示与原模组对应的颜色特效。 |
| 蜜蜂过滤界面 | 游戏内蜜蜂黑白名单编辑器，支持搜索、排序、折叠。 |
| JEI集成 | 完整的JEI支持，禁用万象创世蜜蜂时自动隐藏相关配方。 |
| AE2集成 | 离心机可作为AE2网格节点，直接将产物推送到ME网络，绕过外部物流。 |
| Jade集成 | 离心机在Jade悬停面板中显示AE2网络连接状态（离线/加载中/缺少频道/在线）。 |


### Mekanism 附属 资源蜜蜂离心机

- MEK离心机：添加Mekanism风格离心机，可处理资源蜜蜂的蜜脾和蜜脾块。还可以处理充能冶炼炉 (Energized Smelter) 的配方。
- 工厂升级兼容：兼容Mekanism Extras、 Evolved Mekanism、Evolved Mekanism Extras的工厂升级。
- 输出安全：输出槽合并相同堆叠；离心机满槽时暂停，避免空耗能量
- 弹出速度优化：所有Mek离心机类型使用优化可配置的弹出延迟，更快地将产物转移到相邻容器

### 配置系统

- 客户端配置：蜜蜂过滤UI设置
- 通用配置：万象创世蜜蜂属性（外观、授粉、PB属性、基础属性、繁殖、环境）
- 服务端配置：蜜蜂类型过滤（黑名单/白名单）、Mek离心机参数（含弹出延迟活动/空闲）、万象创世蜜蜂启用/禁用开关

### 蜜蜂过滤配置界面

- 完整的蜜蜂选择界面，支持搜索、排序、分组折叠
- 过滤列表支持黑名单/白名单模式
- 已添加和未添加蜜蜂的视觉区分
- 过滤列表数字索引
- 特殊蜜蜂的动态产物信息显示
- 排序模式和折叠状态持久化

## 配置

模组提供了游戏内配置界面，可通过模组菜单访问。玩家可以修改：

- **外观**：主颜色、次要颜色、粒子颜色、发光颜色
- **授粉**：授粉物品
- **资源蜜蜂属性**：天气耐受性、性格、行为、耐力、产量
- **基础属性**：蜜脾产出、大小、速度、攻击力
- **繁殖**：繁殖物品、繁殖物品数量、种内繁殖
- **环境**：防水、防火
- **蜜蜂过滤**：黑名单/白名单模式、过滤蜜蜂类型列表
- **Mek离心机**：流体槽容量、弹出延迟、处理参数

### 访问配置界面

1. 打开 Minecraft 主菜单
2. 点击"模组"
3. 找到"资源蜜蜂：创世"
4. 点击"配置"按钮

配置修改在重启游戏或执行 `/reload` 后生效。

### 语言支持

配置界面支持多种语言（中文/英文），会自动适应客户端语言设置。


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
| Mekanism Extras | ME等级工厂。 |
| Evolved Mekanism | EM等级工厂。 |
| Evolved Mekanism Extras | EME等级工厂。 |
| Mekanism Unleashed | 扩展升级上限。 |
| Applied Energistics 2 | 直接ME网络产物输出集成。 |
| Jade | AE2网络状态悬停显示。 |
| Iris | 宇宙渲染光影兼容。 |
| JEI | 配方查看。 |

## 使用方法

1. 安装 NeoForge 和必需前置模组。
2. 将模组 jar 放入 `mods` 文件夹。
3. 启动游戏，通过资源蜜蜂的蜂房获取万象创世蜜蜂。
4. 在 Mek 离心机或其工厂版本中处理万象创世蜜脾。

## 架构

### 包结构

- (根包)：主模组类（`ProductiveBeesGenesis`、`ProductiveBeesGenesisClient`）、蜜脾事件处理器（`AbstractCombEventHandler`、`MyriadCreationsEventHandler`）、`RandomHoneycombSelector`（随机蜜脾分配算法）、`CombBlockCheckCache`（空转拦截缓存）
- `capability/`：能力辅助类 — `RateLimitedItemHandler`（限流物品处理器）、`IInventoryDirtyDebouncer`（库存标记防抖）
- `client/`：客户端事件处理器（`AbstractClientCombEventHandler`、`MyriadCreationsClientEventHandler`）
- `client/jei/`：JEI配方类别（PB离心机配方）
- `client/jade/`：Jade插件 — AE2网络状态显示（`JadePlugin`、`JadeAe2StatusProvider`）
- `client/render/cosmic/`：宇宙着色器系统、烘焙模型（`AbstractBakedModelCosmic`、`BakedModelCosmic`、`BakedModelHell`、`BakedModelHalo`）、渲染队列、Iris兼容、`AbstractMaskGeometryLoader`基类
- `client/screen/`：配置和Mek离心机GUI界面 — 主屏幕（`FilterListScreen`、`BeeSelectionScreen`）配合组合助手（`FilterListDragHandler`、`FilterListClipboardHelper`、`BeeSelectionSorter`）和渲染器（`FilterListRenderer`、`BeeSelectionRenderer`）；Mek离心机GUI（`GuiMekCentrifuge`、`GuiMekCentrifugeFactory` 及工厂变体）
- `client/screen/state/`：界面状态管理（`BeeSelectionState`、`BeeSelectionCache`）
- `command/`：指令（包保留供未来扩展）
- `config/`：配置定义拆分为 `ClientConfig`/`CommonConfig`/`ServerConfig`，`ModConfig` 作为聚合入口，支持中英文双语
- `datagen/`：数据生成（方块标签、配方、战利品表、语言文件）
- `init/`：DeferredRegister注册（方块、物品、方块实体、菜单类型、创造模式标签、统计）
- `item/`：自定义物品（无尽之剑复刻）
- `mek/`：Mekanism离心机方块、方块实体、容器、配方处理 — `PbRecipeProcessor` 协调器委托给 `PbRecipeFinder`/`PbRecipeCompleter`/`MyriadCreationsHandler`，`FactoryPbContextDelegate` 工厂组合类，隔离的可选依赖BlockType（`MekCentrifugeMEBlockType`、`MekCentrifugeEMEBlockType`），`OutputSlotFlagManager`（延迟刷新输出槽标志位），`MyriadBatchPlanner`（零拷贝批量插入规划器）
- `mek/ae2/`：AE2集成 — `Ae2OutputPusher`（批量合并产物推送），`Ae2GridNodeManager`（网格节点生命周期），`AeItemKeyCache`（AEItemKey identity缓存），`IAe2OutputHost`（宿主接口）
- `menu/`：容器菜单定义（Mek离心机及工厂容器）
- `mixin/`：Mixin类，含 `MixinConfigPlugin` 条件加载；子包：`accessor/`（访问器）、`beehive/`（蜂箱/库存防抖与缓存）、`client/`（客户端 — 蜜蜂颜色、宇宙物品渲染）、`iris/`（Iris着色器兼容，含 `IrisConfigPlugin`）、`mek/`（Mekanism离心机/工厂/弹出器）、`recipe/`（配方序列化兜底）
- `network/`：网络通信 — `ModPayloads`（载荷注册）、`FilterConfigSyncPayload`（过滤配置同步）
- `util/`：`BeeInfoHelper`、`RecipeCacheManager`、`BeeConfigApplier`、`BeeIngredientFallback`、`CentrifugeMixinHelper`、`CentrifugeRecipeIndex`、`InputOutputCompatibilityCache`、`InputValidationCache`（指纹键输入缓存）、`BeeRecipeReloader`、`PBConstants`

### 关键抽象

- **`AbstractCombEventHandler`**：`MyriadCreationsEventHandler` 的基类，提取公共的蜜蜂类型缓存、随机蜜脾生成和离心机拦截逻辑。随机蜜脾分配委托给 `RandomHoneycombSelector`，空转拦截委托给 `CombBlockCheckCache`。
- **`RandomHoneycombSelector`**：随机蜜脾分配算法的静态工具类（Fisher-Yates 洗牌、Stars-and-Bars 分配、均匀分配），事件处理器和 Mekanism 批量规划器共用。
- **`CombBlockCheckCache`**：空转操作拦截缓存，在输出满时防止冗余的方块状态检查。
- **`AbstractBakedModelCosmic`**：`BakedModelCosmic` 和 `BakedModelHell` 的基类，提取宇宙渲染管线（着色器uniform、mask精灵、Iris延迟）
- **`AbstractMaskGeometryLoader`**：`GeometryLoaderCosmic` 和 `GeometryLoaderHell` 的基类，提取公共的 mask 解析和父模型解析逻辑
- **`CentrifugeMixinHelper`**：工具类，从6个离心机Mixin中提取公共逻辑（canOperate检查、canProcessRecipe检查、completeRecipeProcessing追加）
- **`BeeIngredientFallback`**：工具类，为5个配方 Serializer Mixin 提供 fallback 序列化，防止 BeeIngredientFactory 未就绪时 NPE
- **`PBConstants`**：公共常量类，统一 `MYRIADCREATIONS_TYPE` 等全局共享常量
- **`MekCentrifugeMEBlockType`/`MekCentrifugeEMEBlockType`**：ME/EME 可选依赖的隔离 BlockType 定义，仅在对应模组存在时加载，防止 `NoClassDefFoundError`
- **`MixinConfigPlugin`**：条件Mixin加载器 — 当ME/EME未安装时跳过相关Mixin，防止崩溃
- **`PbRecipeProcessor`**：PB配方处理协调器，持有共享状态数组并委托给专门组件 — `PbRecipeFinder`（双层缓存配方查找）、`PbRecipeCompleter`（输出聚合与批量插入）、`MyriadCreationsHandler`（万象创世特殊路径）。
- **`FactoryPbContextDelegate`**：组合类，消除三个工厂方块实体中约 293 行重复的 PB 配方上下文逻辑。
- **`BeeSelectionSorter`**：从 `BeeSelectionScreen` 抽取的组合类，处理蜜蜂类型排序/过滤逻辑及缓存显示项。
- **`FilterListDragHandler`/`FilterListClipboardHelper`/`FilterListBeeInfoCache`/`FilterListSelectionManager`**：从 `FilterListScreen` 抽取的组合助手，分别负责拖拽/滚动交互、剪贴板导入/导出、蜜蜂信息缓存和选择管理。

### 线程安全

- 静态字段使用 `volatile` 保证跨线程可见性
- 并发集合：`ConcurrentHashMap`、`CopyOnWriteArrayList`
- 原子计数器：`AtomicInteger`、`AtomicLong`
- Holder模式实现线程安全延迟初始化
- 按实例缓存替代全局静态缓存
- 非并发集合的复合操作使用 `synchronized` 块保护
- 服务器停止事件清理静态缓存并注销 JMX MBean，防止内存泄漏

### Mixin 命名规范

所有Mixin方法和字段使用 `productivebeesgenesis$` 前缀（如 `productivebeesgenesis$onInit`、`productivebeesgenesis$getProductivityModifier`）。

## 问题反馈

遇到问题或有建议请提交到 [GitHub Issues](https://github.com/Ayoshiko/productive-bees-genesis/issues)。

## 许可证

本项目采用 MIT 许可证， 详见[MIT License](LICENSE)。

## 致谢

- Productive Bees 开发团队
- Mekanism 开发团队
- NeoForge 开发团队
- Re:Avaritia（宇宙着色器参考）
