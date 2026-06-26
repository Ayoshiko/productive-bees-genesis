# 资源蜜蜂：创世

资源蜜蜂（Productive Bees）和通用机械（Mekanism）附属模组，添加了通用机械（Mekanism）风格的离心机，可处理蜜脾和蜜脾块。添加了万象创世蜜蜂，其蜜脾可转化为整合包内所有资源蜜蜂的蜜脾。蜜脾转化功能拥有详细可配置的过滤名单。万象创世蜜蜂的数据包可以在配置文件里自定义修改。
万象创世蜜脾拥有无尽贪婪（Re:Avaritia）模组的寰宇支配之剑（Sword of the Cosmos）同款的星空遮罩材质。

## 语言

- [English](README.md)
- [中文](README_zh.md)

## 功能特性

| 功能 | 说明 |
| --- | --- |
| 万象创世蜜蜂 | 8秒彩虹渐变，彩虹粒子特效；其蜜脾随机产出其他资源蜜蜂的蜜脾。 |
| 星空蜜脾材质 | 万象创世蜜脾拥有无尽贪婪（Re:Avaritia）模组的寰宇支配之剑（Sword of the Cosmos）同款的星空遮罩材质。 |
| MEK离心机 | Mekanism风格离心机，可处理资源蜜蜂的蜜脾和蜜脾块。还可以处理充能冶炼炉 (Energized Smelter) 的配方。|
| 工厂等级 | 覆盖Mekanism、Mekanism Extras 、Evolved Mekanism、Evolved Mekanism Extras 共17个等级。 |
| 蜜蜂过滤界面 | 游戏内蜜蜂黑白名单编辑器，支持搜索、排序、折叠。 |


### Mekanism 附属 资源蜜蜂离心机

- MEK离心机：添加Mekanism风格离心机，可处理资源蜜蜂的蜜脾和蜜脾块。还可以处理充能冶炼炉 (Energized Smelter) 的配方。
- 工厂升级兼容：兼容Mekanism Extras、 Evolved Mekanism、Evolved Mekanism Extras的工厂升级。
- 输出安全：输出槽合并相同堆叠；离心机满槽时暂停，避免空耗能量
- 弹出速度优化：所有Mek离心机类型使用优化可配置的弹出延迟，更快地将产物转移到相邻容器

### 配置系统

- 客户端配置：性能监控开关、蜜蜂过滤UI设置
- 通用配置：万象创世蜜蜂属性（外观、授粉、PB属性、基础属性、繁殖、环境）
- 服务端配置：蜜蜂类型过滤（黑名单/白名单）、Mek离心机参数（含弹出延迟活动/空闲）、Mek离心机弹出延迟（活动/空闲）

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
| Iris | 宇宙渲染光影兼容。 |
| JEI | 配方查看。 |

## 使用方法

1. 安装 NeoForge 和必需前置模组。
2. 将模组 jar 放入 `mods` 文件夹。
3. 启动游戏，通过资源蜜蜂的蜂房获取万象创世蜜蜂。
4. 在 Mek 离心机或其工厂版本中处理万象创世蜜脾。

## 架构

### 包结构

- `block/`：自定义方块（离心机框架、装饰方块）
- `client/gui/`：Mek离心机GUI辅助类和工厂GUI辅助类
- `client/jei/`：JEI配方类别（PB离心机配方）
- `client/model/`：自定义模型加载器和几何加载器
- `client/render/cosmic/`：宇宙着色器系统、烘焙模型（`AbstractBakedModelCosmic`、`BakedModelCosmic`、`BakedModelHell`、`BakedModelHalo`）、渲染队列、Iris兼容、`AbstractMaskGeometryLoader`基类
- `client/screen/`：配置和Mek离心机GUI界面（`FilterListScreen`、`FilterListRenderer`）
- `compat/`：跨模组兼容辅助类
- `config/`：ModConfig定义（CLIENT/COMMON/SERVER），支持中英文双语
- `datagen/`：数据生成（方块标签、配方、战利品表）
- `init/`：DeferredRegister注册（方块、物品、方块实体等）
- `item/`：自定义物品（无尽之剑复刻、生成蛋）
- `mek/`：Mekanism离心机方块、方块实体、容器、配方处理（`PbRecipeProcessor`、`RecipeCacheManager`）、隔离的可选依赖BlockType（`MekCentrifugeMEBlockType`、`MekCentrifugeEMEBlockType`）
- `menu/`：容器菜单定义
- `mixin/`：Mixin类（PB离心机、蜜蜂颜色、工厂升级链、Iris、配方序列化兜底），含 `CentrifugeMixinHelper` 消除重复和 `MixinConfigPlugin`/`IrisConfigPlugin` 条件加载
- `recipe/`：自定义配方类型
- `screen/`：服务端界面持有者
- `util/`：`BeeInfoHelper`、`RecipeCacheManager`、`PerformanceMonitor`、`BeeConfigApplier`、`BeeIngredientFallback`、`PBConstants`

### 关键抽象

- **`AbstractCombEventHandler`**：`MyriadCreationsEventHandler` 的基类，提取公共的蜜蜂类型缓存、随机蜜脾生成和离心机拦截逻辑
- **`AbstractBakedModelCosmic`**：`BakedModelCosmic` 和 `BakedModelHell` 的基类，提取宇宙渲染管线（着色器uniform、mask精灵、Iris延迟）
- **`CentrifugeMixinHelper`**：工具类，从6个离心机Mixin中提取公共逻辑（canOperate检查、canProcessRecipe检查、completeRecipeProcessing追加）
- **`MixinConfigPlugin`**：条件Mixin加载器 — 当ME/EME未安装时跳过相关Mixin，防止崩溃
- **`PbRecipeProcessor`**：PB配方处理辅助类，缓存 `energyPerTick`/`operationsPerTick` 并支持配方版本追踪

### 线程安全

- 静态字段使用 `volatile` 保证跨线程可见性
- 并发集合：`ConcurrentHashMap`、`CopyOnWriteArrayList`
- 原子计数器：`AtomicInteger`、`AtomicLong`
- Holder模式实现线程安全延迟初始化
- 按实例缓存替代全局静态缓存
- 非并发集合的复合操作使用 `synchronized` 块保护
- 服务器停止事件清理静态缓存，防止内存泄漏

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
