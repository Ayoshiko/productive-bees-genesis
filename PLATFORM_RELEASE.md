# CurseForge / Modrinth 发布清单

本文记录 Productive Bees Genesis 在 CurseForge、Modrinth 及类似模组平台发布时使用的统一资料。平台项目 ID、下载页 URL 与自动发布凭据在项目创建后再填写，不提交访问令牌。

## 统一项目资料

| 字段 | 内容 |
| --- | --- |
| 项目名称 | Productive Bees Genesis / 资源蜜蜂：创世 |
| 模组 ID | `productivebeesgenesis` |
| 内部/构建版本 | 以 `gradle.properties` 的 `mod_version` 为准；当前开发版本为 `1.2.4` |
| CurseForge 版本 | 以 `curseforge_release_version` 为准；当前开发版本为 `1.2.4` |
| 发布状态 | 当前 `1.2.4` 为 Unreleased 待发布候选；本地默认构建、换料与独立恢复、真实重连和 42 个编辑表单已有受限证据。生产提交 `500c11e` 的 [GitHub Build](https://github.com/Ayoshiko/productive-bees-genesis/actions/runs/42483843621) 已成功，25 项固定开发依赖下载及 JAR／构建证明已核验；正式分发仍须完成下列来源条件，既有 `v1.2.3` 附件保持原状 |
| CurseForge 渠道 | Release |
| Minecraft | `1.21.1` |
| 模组加载器 | NeoForge |
| 许可证 | 自有内容 MIT；第三方条款和未决来源见 `NOTICE`、`THIRD_PARTY_LICENSES.md` 与 `gradle/license-manifest.json` |
| 项目主页 | `https://github.com/Ayoshiko/productive-bees-genesis` |
| 问题反馈 | `https://github.com/Ayoshiko/productive-bees-genesis/issues` |
| 英文介绍 | `README.md` |
| 中文介绍 | `README_zh.md` |
| 版本说明 | `CHANGELOG.md` 对应版本章节 |

## 版本映射

版本由目标标签提交的 `gradle.properties` 决定，标签 `v<mod_version>`、文件名 `productivebeesgenesis-<mod_version>.jar`、NeoForge 的本模组版本与 Manifest `Implementation-Version` 必须一致。`CurseForge-Release-Version` 必须等于同一提交的 `curseforge_release_version`。当前两个属性为 `1.2.4`；后续发布不能按本文旧示例倒改版本。

Build 工作流使用 [固定开发依赖清单](gradle/development-dependencies.json)，成功构建后在同一 Actions artifact 保管精确 JAR 与 `build-proof.json`。证明包含仓库、提交、事件／引用、run ID／attempt、版本、文件大小和 SHA-256。标签构建还执行 `verifyDistributionPermissions`；普通开发构建不受未决权利清单阻断。

CurseForge 工作流从维护分支检出校验工具，读取标签的真实提交与版本，要求该提交位于维护历史，并从 GitHub Actions API 取得同一仓库、同一提交、正确 Build 工作流的成功运行。PR 合并构建或网络分支构建不作为维护发布证据。镜像附件必须与该运行保管的 JAR 摘要一致，并通过内嵌版本、通知、逐条目摘要和权限检查；镜像阶段不重新构建 JAR。旧正式版本缺少新格式证明时会拒绝自动镜像，需要先形成可核验的候选，不能据此宣称旧附件有错。

`## English` 后的说明完整传入 CurseForge，包含独立 `EOF` 行也不会截断。输出分隔符与正文逐行检查冲突。发布授权和远端运行验收单独处理；本地脚本测试不表示 GitHub Actions 已实际成功。

## 历史开发版本

首个正式版前的所有 GitHub Release（原 `v1.0.0` 至 `v2.0.9-hotfix`）均为开发快照，不是正式发行版。
其 Git 标签统一使用 `dev-v...` 前缀，Release 标题使用 `dev-...` 前缀并标记为 Pre-release；新的
`v1.0.0` 和 `v1.0.1` 是不带该前缀的正式标签。历史 Release 的 JAR 资产维持原文件名和校验和，不重新打包或改名。

## 历史快照：1.0.9 正式发布产物（2026-09-26）

```text
文件: build/libs/productivebeesgenesis-1.0.9.jar
大小: 2,793,872 bytes
SHA-256: CBD6280828A5AF08A92A9F857FB886B4CBC3E1C05773765AE80A12675E1FA2E5
```

该历史 JAR 排除了本地材质备份、预览文件和 Java 调试符号；上述 SHA-256 仅用于核对当时的 1.0.9 附件，不代表当前工作区。

2026-09-26 正式发布验证：

- `.\gradlew cleanTest test --no-build-cache --no-daemon`：完整普通测试成功，共 694 项，零失败、零错误，2 项按环境条件跳过。
- `.\gradlew clean build verifyReleaseArtifact --no-daemon`：干净构建成功；版本元数据、许可证、图标及开发资源排除检查通过，体积明细保存在 `build/reports/release-artifact.txt`。
- ECO `21.2.0-beta6-hotfix3` 的定向 NeoForge 原生兼容用例通过；详情见 `CHANGELOG.md` 的 1.0.9 验证记录。
- GitHub Release `v1.0.9` 已成为 Latest；远端附件大小、重新下载的 SHA-256 和 GitHub API 摘要一致。CurseForge 自动发布工作流 `36215349725` 成功，使用同一 GitHub Release JAR。
- 本轮未完成真实玩家存档中的客户端交互、在线 AE2 网络端到端及固定场景 Spark/MSPT 对照；不据此宣称实机性能提升比例。

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
- GuideME
- Patchouli

若平台没有对应项目，保留在项目介绍的兼容列表中，不要错误标记为必需依赖。

## 每次发布前

1. 从目标维护提交读取 `mod_version`、`curseforge_release_version`、游戏／加载器／Java 与依赖范围；在现有 CHANGELOG 汇总上一正式标签后的实际变化，使用实际发布日期。Release 保留准确的 `## English` 标题。
2. 按变更运行必要场景并复用仍有效证据；包末将 `build verifyReleaseArtifact verifyNoExampleBeesInJar` 合并执行。单次验证及其子进程最多 300 秒，不以全量 clean 强制重做无关检查。
3. 正式分发前执行 `verifyDistributionPermissions`。当前明确未决为 Re:Avaritia 的两组／18 个文件：6 个 cosmic/hell shader 与 12 张 cosmic/halo 图片，其原作者、更早来源、代码／素材许可适用范围及实际用途下的条件仍须核实。另保留 6 个 Java 派生文件的历史原件／版本／更早来源映射，以及列明外部 GPL／LGPL 版本的 API 例外、only-or-later 和适用组合、替换／重链接条件；不能以根 MIT 或未内嵌 JAR 推导全部豁免。普通公共 API、公式和原则参考不自动变成需重新授权的全类复制。已确认的项目素材和手册使用授权继续有效，退役副本不清除历史分发记录。通知完整不代替这些实际条件闭合；公开新的正式 Release 前须完成相应核实。
4. 已获发布授权后，等待对应维护提交／标签的 Build 成功，使用该运行的精确 JAR 和证明。缺失、失败、跳过、超时及过期 artifact 均不能视为成功；不得换成本地同版本测试包。
5. 从同一成功 Build 取出附件，核对 Manifest、NeoForge 元数据、完整通知、图标、排除边界、大小及 SHA-256，再发布 GitHub Release。镜像工作流会独立验证标签→提交→成功运行→附件字节的一致性。
6. 跟踪 CurseForge 同步工作流至成功，核对平台实际附件版本与摘要；外部平台审核与工作流完成分别记录。失败保管日志并定位，不静默替换历史标签或附件。
7. 更新现行发布状态和双语 README 平台链接。历史 1.0.9 记录保持注明日期，不被当前构建结果覆盖。

## 文件命名

构建产物由 Gradle 统一命名：

```text
productivebeesgenesis-<mod_version>.jar
```

不要为平台上传手工重命名 JAR，以便校验哈希并确认各平台提供的是同一构建产物。
