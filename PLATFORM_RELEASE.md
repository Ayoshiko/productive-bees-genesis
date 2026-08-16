# CurseForge / Modrinth 发布清单

本文记录 Productive Bees Genesis 在 CurseForge、Modrinth 及类似模组平台发布时使用的统一资料。平台项目 ID、下载页 URL 与自动发布凭据在项目创建后再填写，不提交访问令牌。

## 统一项目资料

| 字段 | 内容 |
| --- | --- |
| 项目名称 | Productive Bees Genesis / 资源蜜蜂：创世 |
| 模组 ID | `productivebeesgenesis` |
| 内部/构建版本 | `1.0.0` |
| CurseForge 正式版本 | `1.0.0` |
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

内部版本 `1.0.0` 与 CurseForge 首个正式版本 `1.0.0` 是同一次发布，使用同一个 JAR：

```text
CurseForge 文件展示名: Productive Bees Genesis 1.0.0 (MC 1.21.1)
上传文件: productivebeesgenesis-1.0.0.jar
JAR 内部版本: 1.0.0
CurseForge 版本: 1.0.0
发布渠道: Release
```

CurseForge 的 `1.0.0` 与 NeoForge 元数据、Manifest `Implementation-Version` 及 JAR 文件名保持一致。

## 当前正式版产物

```text
文件: build/libs/productivebeesgenesis-1.0.0.jar
大小: 1,684,601 bytes
SHA-256: DD8A4AC3FE7234FA8492F77855F2CE3079A995091DFE58BFBDA9A3AABC919868
```

发布 JAR 排除了本地材质备份、预览文件和 Java 调试符号；已通过两次强制重建验证哈希一致，上传后应以此 SHA-256 核对平台下载文件。

## 图标

- 平台上传及模组内统一使用：`src/main/resources/productivebeesgenesis.png`
- 格式：PNG
- 尺寸：1024 x 1024
- SHA-256：`D4ED2554E4A88A3EAEC43F224E80EB9CF8E0B1C542C96FAB2B7F4A75CE381DF6`
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

1. 确认 `gradle.properties` 中 `mod_version=1.0.0`、`curseforge_release_version=1.0.0`。
2. 将 `CHANGELOG.md` 的 `1.0.0` 章节作为 CurseForge `1.0.0` 更新日志基础。
3. 运行 `./gradlew cleanTest test build verifyReleaseArtifact --rerun-tasks --no-daemon`。
4. 确认 `build/libs/productivebeesgenesis-1.0.0.jar` 存在并记录 SHA-256。
5. `verifyReleaseArtifact` 必须确认 JAR 内含 NeoForge 元数据、图标、Manifest、MIT 许可证和第三方许可说明。
6. 检查 `neoforge.mods.toml` 中版本 `1.0.0`、依赖范围、主页、问题反馈和 `logoFile`。
7. 在干净的测试实例中至少完成客户端启动、服务器启动、蜂箱/离心机放置及 AE2 连接测试。
8. CurseForge 文件版本填写 `1.0.0`，渠道固定选择 `Release`，上传第 4 步的同一 JAR。
9. 发布完成后，将 CurseForge 与 Modrinth 项目页链接补充到中英文 README。

## 文件命名

构建产物由 Gradle 统一命名：

```text
productivebeesgenesis-1.0.0.jar
```

不要为 CurseForge 的 `1.0.0` 手工重命名 JAR，以便校验哈希并确认各平台提供的是同一构建产物。
