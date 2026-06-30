# Productive Bees Genesis — 深度代码审查与修复规范

> **状态**: 待确认  
> **日期**: 2026-06-30  
> **审查范围**: `ziyuanmifeng/productive-bees-addon` 全部源代码  
> **依据规范**: minecraft-code-standards、minecraft-mod-compliance-review、TRAE-code-review、用户规则

---

## 一、任务概述

对 Productive Bees Genesis 附属模组进行全量深度代码审查，覆盖所有 `.java` 源文件，确保代码在合规性、性能、安全、稳定性、线程安全、风格统一等方面达到发布标准。审查后修复所有发现的问题（不论大小），更新文档和版本号，推送到 GitHub。

## 二、审查范围

### 2.1 源代码文件（约 80+ Java 文件）

| 模块 | 路径 | 文件数（约） |
|------|------|-------------|
| 主类与事件 | `ProductiveBeesGenesis.java`, `*EventHandler.java` | 5 |
| Mekanism 集成 | `mek/` | 20 |
| Mixin | `mixin/` 及子包 | 28 |
| 客户端渲染 | `client/render/cosmic/` | 24 |
| 客户端界面 | `client/screen/`, `client/gui/` | 12 |
| 客户端其他 | `client/jei/`, `client/model/` | 5 |
| 配置 | `config/` | 2 |
| 注册 | `init/` | 7 |
| 工具类 | `util/` | 12 |
| 数据生成 | `datagen/` | 4 |
| 其他 | `block/`, `item/`, `menu/`, `recipe/`, `screen/`, `capability/`, `command/`, `compat/` | 15 |

### 2.2 排除范围

- `build/`、`bin/`、`run/` 目录（构建产物和运行时文件）
- `.gradle/` 目录（Gradle 缓存）
- `decompiled-productivebees/`（反编译参考代码）
- 非代码文件（`.json`、`.png`、`.fsh`、`.vsh` 等）仅在合规性审查时检查

## 三、审查维度（7 大维度）

### 维度 1: 代码风格与格式化
- Tab 缩进（不使用空格）
- K&R 大括号风格
- 行宽 ≤ 120 字符
- 导入顺序与通配符限制
- 命名约定（类名 PascalCase、字段 camelCase、常量 UPPER_SNAKE、Mixin 成员 `modid$` 前缀）
- NBT key 使用 snake_case + 模组前缀

### 维度 2: 架构与设计
- SOLID 原则遵循
- 单一文件不超过 500 行
- 包职责清晰，无循环依赖
- 接口隔离，公共 API 有稳定性声明
- 模板方法模式正确使用

### 维度 3: 线程安全与并发
- `volatile` 用于跨线程可见性
- `ConcurrentHashMap` 替代 `HashMap`
- `CopyOnWriteArrayList` 用于并发遍历
- `AtomicInteger`/`AtomicLong` 保证原子操作
- `synchronized` 保护复合操作
- Tick 处理轻量，无主线程阻塞
- 静态缓存在服务器停止时清理

### 维度 4: 性能优化
- 无 O(n³) 复杂度算法
- 缓存策略合理（LRU、过期清理）
- 避免重复计算
- 对象池和复用
- 日志级别合理（高频路径不使用 INFO/WARN）

### 维度 5: 异常处理与健壮性
- 不吞掉异常
- 异常捕获后有策略（重试/回退/记录/上报）
- 可选依赖有 `ClassNotFoundError` 防护
- 空指针防护
- 边界检查

### 维度 6: Minecraft 模组规范
- Mixin 命名前缀 `productivebeesgenesis$`
- `@Unique` 注解用于 Mixin 新增字段
- `package-info.java` 完整性
- Javadoc 覆盖所有 public 成员
- 事件处理线程语义标注
- `ResourceLocation` 构造方式
- 配置文件默认值与迁移

### 维度 7: 合规性审查
- 无禁止内容（暴力、歧视、违法）
- 许可证完整性（MIT）
- 无 Minecraft 原版资产打包
- 第三方素材授权
- 隐私与数据保护
- 无恶意代码

## 四、任务分解（8 个阶段）

### 阶段 1: 并行调查（4 个并行搜索）
- **搜索 A**: Mekanism 集成层 — 审查 `mek/` 包所有文件的线程安全、性能、异常处理
- **搜索 B**: Mixin 层 — 审查 `mixin/` 所有子包的命名规范、注入安全性、条件加载
- **搜索 C**: 客户端渲染层 — 审查 `client/` 的 cosmic 渲染系统、GUI、JEI 集成
- **搜索 D**: 核心基础层 — 审查主类、事件处理、配置、注册、工具类、数据生成

### 阶段 2: 问题汇总与分类
- 合并 4 个搜索的结果
- 去重、分类（高/中/低/优化建议）
- 记录到 `docs/CODE_REVIEW_FINDINGS.md`

### 阶段 3: 修复高优先级问题
- 线程安全问题
- 潜在崩溃/NPE
- 异常处理缺失
- 安全漏洞

### 阶段 4: 修复中优先级问题
- 性能优化
- 代码风格统一
- Javadoc 补充
- 架构改进

### 阶段 5: 修复低优先级问题
- 命名规范微调
- 导入排序
- 注释优化
- `package-info.java` 补全

### 阶段 6: 修复优化建议
- 接口扩展性提升
- 缓存策略改进
- 日志级别优化
- 配置完善

### 阶段 7: 构建验证
- 执行 `.\gradlew build --no-daemon` 验证编译通过
- 检查无新增警告

### 阶段 8: 文档更新与发布
- 更新 `README.md` / `README_zh.md`
- 更新 `CHANGELOG.md`
- 递增 `gradle.properties` 版本号
- Git commit 并推送到 GitHub

## 五、验收标准

| 编号 | 验收项 | 验收方法 |
|------|--------|----------|
| AC-1 | 所有 Java 文件使用 Tab 缩进 | grep 检查无空格缩进 |
| AC-2 | 所有 Mixin 成员使用 `productivebeesgenesis$` 前缀 | grep 验证 |
| AC-3 | 所有 public 类/方法有 Javadoc | 人工审查 |
| AC-4 | 每个包有 `package-info.java` | 文件系统检查 |
| AC-5 | 无吞掉异常的 catch 块 | 代码搜索 |
| AC-6 | 静态缓存有清理机制 | 代码搜索 |
| AC-7 | 并发集合正确使用 | 代码搜索 |
| AC-8 | 单文件不超过 500 行 | wc -l 检查 |
| AC-9 | `.\gradlew build` 编译通过 | 构建验证 |
| AC-10 | README/CHANGELOG 已更新 | 文件审查 |
| AC-11 | 版本号已递增 | gradle.properties 检查 |
| AC-12 | 已推送到 GitHub | git log 检查 |
| AC-13 | 所有问题记录在 `docs/CODE_REVIEW_FINDINGS.md` | 文件存在且完整 |
| AC-14 | 无禁止内容 | 合规性审查 |
| AC-15 | LICENSE 文件完整 | 文件检查 |

## 六、执行约束

1. **PowerShell 环境**: 使用 `;` 分隔命令，不用 `&&`
2. **路径**: 使用 `.\gradlew` 而非 `./gradlew`
3. **构建参数**: 始终使用 `--no-daemon`
4. **中文注释**: 适当添加精简中文注释
5. **不新建 md 文件**: 除审查报告和 CHANGELOG 外不新建文档
6. **Git 提交信息**: 使用规范的中文提交信息
