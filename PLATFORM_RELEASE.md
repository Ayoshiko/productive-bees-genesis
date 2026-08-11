# CurseForge / Modrinth 发布清单

本文记录 Productive Bees Genesis 在 CurseForge、Modrinth 及类似模组平台发布时使用的统一资料。平台项目 ID、下载页 URL 与自动发布凭据在项目创建后再填写，不提交访问令牌。

## 统一项目资料

| 字段 | 内容 |
| --- | --- |
| 项目名称 | Productive Bees Genesis / 资源蜜蜂：创世 |
| 模组 ID | `productivebeesgenesis` |
| 当前版本 | `2.0.9-hotfix` |
| Minecraft | `1.21.1` |
| 模组加载器 | NeoForge |
| 许可证 | MIT |
| 项目主页 | `https://github.com/Ayoshiko/productive-bees-genesis` |
| 问题反馈 | `https://github.com/Ayoshiko/productive-bees-genesis/issues` |
| 英文介绍 | `README.md` |
| 中文介绍 | `README_zh.md` |
| 版本说明 | `CHANGELOG.md` 对应版本章节 |

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

1. 确认 `gradle.properties` 中的 `mod_version` 与 Git 标签、平台版本号一致。
2. 将 `CHANGELOG.md` 中对应版本章节作为平台更新日志基础。
3. 运行 `./gradlew clean build`。
4. 确认 `build/libs/productivebeesgenesis-<version>.jar` 存在。
5. 确认 JAR 内含 `META-INF/neoforge.mods.toml`、`productivebeesgenesis.png`、MIT 许可证和第三方许可说明。
6. 检查生成的 `neoforge.mods.toml` 中版本号、依赖范围、主页、问题反馈和 `logoFile`。
7. 在干净的测试实例中至少完成客户端启动、服务器启动和基础机器放置测试。
8. 上传同一个 JAR 到各平台；发布渠道按版本稳定性选择 Release、Beta 或 Alpha。
9. 发布完成后，将 CurseForge 与 Modrinth 项目页链接补充到中英文 README。

## 文件命名

构建产物由 Gradle 统一命名：

```text
productivebeesgenesis-<version>.jar
```

不要手工重命名不同平台的 JAR，以便校验哈希并确认各平台提供的是同一构建产物。
