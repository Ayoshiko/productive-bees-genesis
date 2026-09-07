# CurseForge / Modrinth 发布清单

本文记录 Productive Bees Genesis 在 CurseForge、Modrinth 及类似模组平台发布时使用的统一资料。平台项目 ID、下载页 URL 与自动发布凭据在项目创建后再填写，不提交访问令牌。

## 统一项目资料

| 字段 | 内容 |
| --- | --- |
| 项目名称 | Productive Bees Genesis / 资源蜜蜂：创世 |
| 模组 ID | `productivebeesgenesis` |
| 内部/构建版本 | `1.0.7` |
| CurseForge 待发布版本 | `1.0.7` |
| 发布状态 | 准备中，尚未创建本版本标签或发布 Release |
| CurseForge 渠道 | Release |
| Minecraft | `1.21.1` |
| 模组加载器 | NeoForge |
| 许可证 | MIT |
| 项目主页 | `https://github.com/Ayoshiko/productive-bees-genesis` |
| 问题反馈 | `https://github.com/Ayoshiko/productive-bees-genesis/issues` |
| 英文介绍 | `README.md` |
| 中文介绍 | `README_zh.md` |
| 版本说明 | `CHANGELOG.md` 对应版本章节 |

## 版本映射

内部版本 `1.0.7` 与计划发布的 CurseForge 版本 `1.0.7` 使用同一个 JAR：

```text
CurseForge 文件展示名: productivebeesgenesis-1.0.7.jar (MC 1.21.1)
上传文件: productivebeesgenesis-1.0.7.jar
JAR 内部版本: 1.0.7
CurseForge 版本: 1.0.7
发布渠道: Release
```

CurseForge 的 `1.0.7` 与 NeoForge 元数据、Manifest `Implementation-Version` 及 JAR 文件名保持一致。

## 历史开发版本

首个正式版前的所有 GitHub Release（原 `v1.0.0` 至 `v2.0.9-hotfix`）均为开发快照，不是正式发行版。
其 Git 标签统一使用 `dev-v...` 前缀，Release 标题使用 `dev-...` 前缀并标记为 Pre-release；新的
`v1.0.0` 和 `v1.0.1` 是不带该前缀的正式标签。历史 Release 的 JAR 资产维持原文件名和校验和，不重新打包或改名。

## 1.0.7 待发布产物

```text
文件: build/libs/productivebeesgenesis-1.0.7.jar
大小: 1,836,452 bytes
SHA-256: 2AF7958F7F66BC1317D6E539C49A9F7B55A0C802E681C365638D957EE88FDF3E
```

发布 JAR 排除了本地材质备份、预览文件和 Java 调试符号；上传后应以此 SHA-256 核对平台下载文件。

2026-09-07 发布准备验证：

- `.\gradlew cleanTest build --no-build-cache --no-daemon`：构建成功，90 个测试类共 521 项测试实际执行，零失败、零错误、零跳过。
- `verifyReleaseArtifact`：版本元数据、许可证、图标及开发资源排除检查通过。JAR 不再使用 1,800,000 字节硬上限，体积明细保存在 `build/reports/release-artifact.txt`。
- 288 个资源 JSON 可解析；中英文语言文件各 1,100 个键，键集合一致。
- 本轮未执行客户端/专用服务器游戏内冒烟测试，正式发布前仍需完成下方第 7 项。

## 图标

- 平台上传及模组内统一使用：`src/main/resources/productivebeesgenesis.png`
- 格式：PNG
- 尺寸：256 x 256
- SHA-256：`E1502E87A2C5EE69AC5AFDF3B7551A83E57C0A69BD41E6906717D8942C5A6A5D`
- NeoForge 元数据：`logoFile="productivebeesgenesis.png"`

不要另外压缩、截图或从 README 下载图标，避免不同平台出现不同版本。

## 依赖关系

平台依赖字段应与 `neoforge.mods.toml` 保持一致。

必需依赖：

- Productive Bees
- Mekanism
- NeoForge

可选集成：

- Mekanism Extras
- Evolved Mekanism
- Evolved Mekanism Extras
- Mekanism Empowered
- Applied Energistics 2
- Applied Flux
- Jade
- Iris Shaders
- Super Factory Manager
- KubeJS
- Just Enough Items

若平台没有对应项目，保留在项目介绍的兼容列表中，不要错误标记为必需依赖。

## 每次发布前

1. 确认 `gradle.properties` 中 `mod_version=1.0.7`、`curseforge_release_version=1.0.7`。
2. 以 `CHANGELOG.md` 的 `[1.0.7]` 中英文内容整理发布说明，不使用过期的本地草稿。GitHub Release body 必须包含 `## English` 段落（从 changelog 提取时将 `### English` 调整为该标题），工作流会将其后的英文段落同步到 CurseForge。
3. 在 PowerShell 运行 `.\gradlew cleanTest build --no-build-cache --no-daemon`。
4. 确认 `build/libs/productivebeesgenesis-1.0.7.jar` 存在并记录 SHA-256，并先将同一 JAR 附加到 GitHub Release；CurseForge 工作流会下载该已校验资产，不依赖 CI 的本地 `libs/` 开发库。
5. `verifyReleaseArtifact` 必须确认 JAR 内含 NeoForge 元数据、图标、Manifest、MIT 许可证和第三方许可说明。
6. 检查 `neoforge.mods.toml` 中版本 `1.0.7`、依赖范围、主页、问题反馈和 `logoFile`。
7. 在干净的测试实例中至少完成客户端启动、服务器启动、蜂箱/离心机放置及 AE2 连接测试。
8. CurseForge 文件版本填写 `1.0.7`，渠道固定选择 `Release`，上传第 4 步的同一 JAR。
9. 正式发布时将 changelog 的 `未发布` 改为实际日期、更新本文发布状态，并核对中英文 README 的平台链接。

## 文件命名

构建产物由 Gradle 统一命名：

```text
productivebeesgenesis-1.0.7.jar
```

不要为平台上传手工重命名 JAR，以便校验哈希并确认各平台提供的是同一构建产物。
