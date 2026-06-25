# 资源蜜蜂：创世

资源蜜蜂模组的附属模组，添加了万象创世蜜蜂和无尽·创世蜜蜂，可以产出整合包中所有资源蜜蜂的蜜脾，并深度集成Mekanism离心机和宇宙星空渲染效果。

## 🌐 语言
- [English](README.md)
- [中文](README_zh.md)

## 功能特性

### 蜜蜂

- **万象创世蜜蜂**：通过Mixin覆盖PB默认1.25秒色彩循环，实现8秒慢速彩虹渐变，配有多色粒子特效。可随机产出整合包内所有其他资源蜜蜂的蜜脾。
- **无尽·创世蜜蜂**：高阶蜜蜂，拥有宇宙星空渲染效果（与无尽之剑相同的着色器）。产出无尽·创世蜜脾，可转化为随机蜜脾。在拥有Omega升级的蜂巢中，产出无尽·创世蜜脾块而非普通蜜脾。

### Mekanism 离心机集成

- **MEK离心机**：自定义Mekanism机器，使用SMELTING配方类型和ENERGIZED_SMELTER声音处理资源蜜蜂蜜脾
- **工厂版本**：跨Mekanism、Mekanism Extras (ME)和Evolved Mekanism Extras (EME)共17个工厂等级：
  - Mekanism：基础、高级、精英、终极
  - ME：超频、量子、密集、多元宇宙、创造、至尊、绝对、无限
  - EME：绝对超频、至尊量子、宇宙密集、无限多元宇宙
- **智能配方处理**：SMELTING配方优先；PB CentrifugeRecipe独立处理，输出线性缩放
- **输出安全**：输出槽合并相同堆叠；离心机满槽时暂停，避免空耗能量
- **Omega升级支持**：无尽·创世蜜蜂在拥有Omega升级的蜂巢中产出蜜脾块而非蜜脾

### 宇宙渲染系统

- 自定义cosmic/hell着色器，实现星空和星云效果
- 通过Mixin实现Iris光影兼容（强制cosmic着色器通过Iris跳过列表）
- Halo光晕渲染，正确管理blend/depth状态
- Iris光影包兼容的延迟渲染队列
- 线程安全渲染队列，带大小限制和异常安全清理

### 配置系统

- **客户端配置**：性能监控开关、蜜蜂过滤UI设置
- **通用配置**：万象创世蜜蜂属性（外观、授粉、PB属性、基础属性、繁殖、环境）
- **服务端配置**：蜜蜂类型过滤（黑名单/白名单）、无尽·创世设置、Mek离心机参数

### 蜜蜂过滤配置界面

- 完整的蜜蜂选择界面，支持搜索、排序、分组折叠
- 过滤列表支持黑名单/白名单模式
- 已添加和未添加蜜蜂的视觉区分
- 过滤列表数字索引
- 特殊蜜蜂的动态产物信息显示
- 排序模式和折叠状态持久化

### 性能优化

- LRU配方缓存，支持缓存"无配方"结果，避免重复全量扫描
- 通过 `TagsUpdatedEvent` 实现配方版本追踪 — 配方重载时自动失效缓存
- 按handler实例存储的阻塞检查缓存（替代全局静态缓存），支持多机器场景
- 类型特定配方查询，替代全量配方管理器扫描
- `PbRecipeProcessor` 缓存 `energyPerTick`/`operationsPerTick`（避免每tick `Math.pow` 计算）
- `BakedModelHalo` 静态 `BakedQuad` 缓存 + 双重检查锁（避免每帧烘焙）
- `IrisCompat` 通过 Holder 模式缓存反射 `Method`（避免每帧反射查找）
- `FilterListScreen` 缓存蜜蜂显示名称和产物信息（避免每帧配方遍历）
- `CosmicRenderQueue` 预分配 `Matrix4f`（避免每帧分配）
- 线程安全集合（ConcurrentHashMap、CopyOnWriteArrayList、AtomicInteger）
- 异常安全的tick处理，自动重置状态

## 安装说明

### 前置要求

- Minecraft 1.21.1
- NeoForge 21.1.214 或更高版本
- Productive Bees 1.21.1-13.13.5 或更高版本
- Mekanism 1.21.1-10.7.14.79 或更高版本（离心机功能必需）
- 可选：Mekanism Extras、Evolved Mekanism Extras（扩展工厂等级）
- 可选：Mekanism Unleashed（扩展升级上限）
- 可选：Iris（光影兼容）
- 可选：RenderBlender（宇宙渲染效果，仅客户端）
- 可选：JEI（配方查看）

### 安装步骤

1. 确保已安装 NeoForge 加载器
2. 下载最新版本的「资源蜜蜂：创世」模组文件
3. 将模组文件放入 Minecraft 客户端的 `mods` 文件夹
4. 启动游戏

## 获取方式

### 万象创世蜜蜂

万象创世蜜蜂是稀有蜜蜂，可以通过以下方式获取：

- 使用资源蜜蜂的蜂巢升级系统
- 在整合包中配置特定的生成条件

### 无尽·创世蜜蜂

无尽·创世蜜蜂是最高阶蜜蜂，拥有宇宙外观。获取方式：

- 高级蜂巢升级
- 整合包特定配置

### 蜜脾产出

- **万象创世蜜蜂**：随机产出整合包内已注册的其他资源蜜蜂的蜜脾
- **无尽·创世蜜蜂**：产出无尽·创世蜜脾（或拥有Omega升级时产出蜜脾块），通过离心机转化为随机蜜脾

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

## 兼容性

- Minecraft 版本：1.21.1
- 加载器：NeoForge 21.1.214+
- 必需前置：Productive Bees 1.21.1-13.13.5+
- 必需前置：Mekanism 1.21.1-10.7.14.79+
- 可选：Mekanism Extras（ME工厂）
- 可选：Evolved Mekanism Extras（EME工厂）
- 可选：Mekanism Unleashed（扩展升级上限）
- 可选：Iris（光影兼容）
- 可选：RenderBlender（宇宙渲染，仅客户端）
- 可选：JEI（配方查看）

## 架构

### 包结构

- `block/`：自定义方块（离心机框架、装饰方块）
- `client/gui/`：Mek离心机GUI辅助类和工厂GUI辅助类
- `client/jei/`：JEI配方类别（PB离心机配方）
- `client/model/`：自定义模型加载器和几何加载器
- `client/render/cosmic/`：宇宙着色器系统、烘焙模型（`AbstractBakedModelCosmic`、`BakedModelCosmic`、`BakedModelHell`、`BakedModelHalo`）、渲染队列、Iris兼容
- `client/screen/`：配置和Mek离心机GUI界面（`FilterListScreen`、`FilterListRenderer`）
- `compat/`：跨模组兼容辅助类
- `config/`：ModConfig定义（CLIENT/COMMON/SERVER），支持中英文双语
- `datagen/`：数据生成（方块标签、配方、战利品表）
- `init/`：DeferredRegister注册（方块、物品、方块实体等）
- `item/`：自定义物品（无尽之剑复刻、生成蛋）
- `mek/`：Mekanism离心机方块、方块实体、容器、配方处理（`PbRecipeProcessor`、`RecipeCacheManager`）
- `menu/`：容器菜单定义
- `mixin/`：Mixin类（PB离心机、蜜蜂颜色、工厂升级链、Iris），含 `CentrifugeMixinHelper` 消除重复和 `MixinConfigPlugin` 条件加载
- `network/`：网络数据包定义
- `recipe/`：自定义配方类型
- `screen/`：服务端界面持有者
- `util/`：`BeeInfoHelper`、`RecipeCacheManager`、`PerformanceMonitor`、`BeeConfigApplier`

### 关键抽象

- **`AbstractCombEventHandler`**：`MyriadCreationsEventHandler` 和 `InfinityCreationEventHandler` 的基类，提取公共的蜜蜂类型缓存、随机蜜脾生成和离心机拦截逻辑
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

所有Mixin方法和字段使用 `productivebeesgenesis$` 前缀（如 `productivebeesgenesis$onInit`、`productivebeesgenesis$getProductivityModifier`）。无尽体系方法使用 `productivebeesgenesis$infinity$` 前缀，以与万象体系方法在同一目标类上共存。

## 问题反馈

如果遇到问题或有功能建议，请通过以下方式联系：

- GitHub Issues: https://github.com/Ayoshiko/productive-bees-genesis/issues

## 许可证

本项目采用 MIT 许可证，详见 [LICENSE](LICENSE) 文件。

## 致谢

- Productive Bees 模组团队
- Mekanism 开发团队
- NeoForge 开发团队
- Re:Avaritia（宇宙着色器参考）
