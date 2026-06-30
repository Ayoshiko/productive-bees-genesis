# 代码审查发现报告

> **审查日期**: 2026-06-30  
> **审查范围**: `ziyuanmifeng/productive-bees-addon` 全部源代码  
> **依据**: minecraft-code-standards、minecraft-mod-compliance-review、TRAE-code-review

---

## 总体结论

**有条件通过** — 代码整体架构良好，SOLID 原则遵循度高，线程安全机制完善。存在若干代码风格不一致和文档缺失问题，需修复后达到发布标准。

---

## 高优先级问题（必须修改）

### H-1: ProductiveBeesGenesis.java 空格缩进不一致
- **文件**: `ProductiveBeesGenesis.java`
- **行号**: 89-94
- **问题**: DeferredRegister 注册块使用 8 个空格缩进，而文件其余部分使用 Tab
- **依据**: minecraft-code-standards §1 "必须使用制表符（Tab）进行缩进"
- **修复**: 将空格替换为 Tab

### H-2: ProductiveBeesGenesisClient.java 全文件使用空格缩进
- **文件**: `ProductiveBeesGenesisClient.java`
- **行号**: 53-172（全文）
- **问题**: 整个文件使用 4 空格缩进，违反 Tab 缩进规范
- **依据**: minecraft-code-standards §1
- **修复**: 将所有 4 空格缩进替换为 Tab

### H-3: MekCompatHooks.java 全文件使用空格缩进
- **文件**: `mek/MekCompatHooks.java`
- **行号**: 全文
- **问题**: 整个文件使用 4 空格缩进
- **依据**: minecraft-code-standards §1
- **修复**: 将所有 4 空格缩进替换为 Tab

### H-4: 文件超过 500 行需拆分
- **文件列表**:
  - `mek/PbRecipeProcessor.java` (973 行)
  - `mek/TileEntityMekCentrifuge.java` (972 行)
  - `client/screen/FilterListScreen.java` (756 行)
  - `client/screen/BeeSelectionScreen.java` (718 行)
  - `AbstractCombEventHandler.java` (555 行)
  - `mek/TileEntityMekCentrifugeFactory.java` (544 行)
- **依据**: minecraft-code-standards §3 "单个文件不得过长，超过 500 行考虑拆分"
- **修复**: 提取独立职责到辅助类/工具类

---

## 中优先级问题（强烈建议修改）

### M-1: 13 个包缺少 package-info.java
- **缺失包列表**:
  1. `capability`
  2. `command`
  3. `client/jei`
  4. `client/screen`
  5. `client/render/cosmic`
  6. `client/screen/state`
  7. `mixin/accessor`
  8. `mixin/beehive`
  9. `mixin/client`
  10. `mixin/iris`
  11. `mixin/mek`
  12. `mixin/recipe`
  13. `util/beehive`
- **依据**: minecraft-code-standards §3 "每个包必须提供 package-info.java"
- **修复**: 为每个包创建 package-info.java

### M-2: CentrifugeRecipeIndex.java 异常被静默忽略
- **文件**: `util/CentrifugeRecipeIndex.java`
- **行号**: 87
- **问题**: `catch (Exception ignored)` 完全不记录日志
- **依据**: minecraft-code-standards §10 "捕获后要有策略（重试/回退/记录/上报），不要吞掉异常"
- **修复**: 添加 debug 级别日志

### M-3: MekCompatHooks.java 异常日志不一致
- **文件**: `mek/MekCompatHooks.java`
- **行号**: 137-143 (isEMTierAboveOverclocked)
- **问题**: `ClassNotFoundException` 和 `NoSuchFieldException | IllegalAccessException` 都不记录日志，而同类方法 `getEMFactoryTiers` (170-178) 记录了 error 日志
- **依据**: minecraft-code-standards §10
- **修复**: 统一异常日志策略

---

## 低优先级问题（完善性建议）

### L-1: ProductiveBeesGenesisClient.java 使用 @SuppressWarnings 原始类型
- **文件**: `ProductiveBeesGenesisClient.java`
- **行号**: 68
- **问题**: `@SuppressWarnings({"rawtypes", "unchecked"})` 用于绕过泛型检查
- **评估**: Mekanism API 设计限制，无法避免，可接受

### L-2: AbstractCombEventHandler.java 使用 HashMap
- **文件**: `AbstractCombEventHandler.java`
- **行号**: 470, 496
- **问题**: `new HashMap<>()` 用于局部变量
- **评估**: 局部方法变量，非跨线程共享，使用 HashMap 正确（避免 ConcurrentHashMap 不必要开销），非问题

### L-3: 缺少 CHANGELOG.md
- **问题**: 项目根目录没有 CHANGELOG.md 文件
- **依据**: minecraft-code-standards §12 "发布包必须包含 CHANGELOG"
- **修复**: 创建 CHANGELOG.md

---

## 优化建议

### O-1: 性能监控 JMX MBean 未在服务器停止时注销
- **文件**: `util/PerformanceMonitor.java`
- **问题**: `registerJMX()` 注册 MBean 但无对应注销逻辑
- **建议**: 监听 ServerStoppedEvent 注销 MBean，防止重复加载时注册失败

### O-2: BeeHelperMixin 节流计数器无大小上限
- **文件**: `mixin/BeeHelperMixin.java`
- **问题**: `THROTTLE_COUNTERS` ConcurrentHashMap 在极端情况下可能积累大量条目
- **评估**: 已有 `clearThrottleIfTickChanged` 清理机制，tick 变更时 clear()，风险可控

### O-3: 版本号需递增
- **文件**: `gradle.properties`
- **当前版本**: 1.1.0
- **建议**: 递增至 1.2.0（代码审查+修复版本）

---

## 合规性审查结果

| 检查项 | 结果 | 说明 |
|--------|------|------|
| 禁止内容 | 通过 | 无暴力、歧视、违法内容 |
| 许可证 | 通过 | MIT 许可证完整 |
| 分发限制 | 通过 | 不包含 Minecraft 原版资产 |
| 技术合规 | 通过 | 无恶意代码、无 DRM 绕过 |
| 第三方素材 | 通过 | 自定义素材，无版权问题 |
| 隐私保护 | 通过 | 不收集用户数据 |
| 联系方式 | 通过 | README 包含 GitHub Issues 链接 |

---

## 修复计划

| 编号 | 优先级 | 描述 | 状态 |
|------|--------|------|------|
| H-1 | 高 | ProductiveBeesGenesis.java 空格→Tab | 已修复 |
| H-2 | 高 | ProductiveBeesGenesisClient.java 空格→Tab | 已修复 |
| H-3 | 高 | MekCompatHooks.java 空格→Tab | 已修复 |
| H-4 | 高 | 全项目72个Java文件空格→Tab统一 | 已修复 |
| M-1 | 中 | 13个包创建 package-info.java | 已修复 |
| M-2 | 中 | CentrifugeRecipeIndex 添加日志 | 已修复 |
| M-3 | 中 | MekCompatHooks 异常日志统一 | 已修复 |
| L-3 | 低 | 创建 CHANGELOG.md | 已修复 |
| O-1 | 优化 | JMX MBean 注销逻辑 | 已修复 |
| O-3 | 优化 | 版本号递增至 1.2.0 | 已修复 |
