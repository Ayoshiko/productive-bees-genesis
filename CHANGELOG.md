# Changelog

所有重要变更将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本管理遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
- 版本号从 1.1.0 递增至 1.2.0
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
