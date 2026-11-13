# CurseForge / Modrinth 发布清单

本文记录 Productive Bees Genesis 在 CurseForge、Modrinth 及类似模组平台发布时使用的统一资料。平台项目 ID、下载页 URL 与自动发布凭据在项目创建后再填写，不提交访问令牌。

## 统一项目资料

| 字段 | 内容 |
| --- | --- |
| 项目名称 | Productive Bees Genesis / 资源蜜蜂：创世 |
| 模组 ID | `productivebeesgenesis` |
| 内部/构建版本 | 以 `gradle.properties` 的 `mod_version` 为准；当前版本为 `1.2.4` |
| CurseForge 版本 | 以 `curseforge_release_version` 为准；当前版本为 `1.2.4` |
| 发布状态 | `1.2.4` 于 2026-11-13 在 [GitHub 正式发布并设为 Latest](https://github.com/Ayoshiko/productive-bees-genesis/releases/tag/v1.2.4)，标签提交为 `863aa5be74ecc56f9ee6fa0cf2e43e8c8cd56dc7`。[维护 Build](https://github.com/Ayoshiko/productive-bees-genesis/actions/runs/43378990852)、[标签 Build](https://github.com/Ayoshiko/productive-bees-genesis/actions/runs/43381786352) 及 [CurseForge 上传](https://github.com/Ayoshiko/productive-bees-genesis/actions/runs/43381809241) 均成功。公开 JAR 5,689,027 字节，重新下载 SHA-256 为 `0f181b726bf868080f723cd089245cd7e91393ea94ab958df2926763c857ffe9`，与上传附件一致。CurseForge 公共文件信息已列出 [文件 9587266](https://www.curseforge.com/minecraft/mc-mods/productive-bees-genesis/files/9587266)，正式类型、1.21.1／NeoForge 与文件大小均匹配；上传工作流使用同一已核对的 GitHub 附件 |
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

Build 工作流使用 [固定开发依赖清单](gradle/development-dependencies.json)，运行普通 `build` 编译打包，随后记录并上传 JAR、版本、大小与 SHA-256 等附件信息。测试、示例检查和产物检查按实际需要显式选择，不再由普通构建隐式执行。

CurseForge 工作流在公开的非预发布 GitHub Release 发布后自动触发，排除 Forge 标签；也可手动选择 Release。工作流直接下载 Release 中的同名 `productivebeesgenesis-<版本>.jar`，核对实际 JAR 元数据、版本、完整性与摘要后镜像，不重新构建。历史 CI 证明、提交祖先关系、所有者身份和许可清单状态不再作为脚本阻断条件，旧版 `build-proof.json` 不是镜像前置。

`## English` 后的说明完整传入 CurseForge，独立 `EOF` 行不会截断正文。GitHub 公开、镜像工作流完成和 CurseForge 审核／可下载是不同结果，按实际返回分别记录，不能从本地检查推断远端成功。

第三方来源与许可仍以 `NOTICE`、`THIRD_PARTY_LICENSES.md` 和 `gradle/license-manifest.json` 的事实记录为准。现行未决包括部分 Re:Avaritia 着色器／图片来源与适用条件、派生代码历史映射及外部 GPL／LGPL 组合条件。已移除 `verifyDistributionPermissions` 自动阻断任务；通知中的未决状态（包括 `provenance_complete`）继续如实保留，不代表取得新的授权或全部来源已经核实。

## 协作与构建约束

2026-11-13 起，仓库分支 ruleset、强制 PR 策略、CODEOWNERS 与共享自动提交检查已取消。维护、网络和 Forge 分支按现有仓库写权限协作，Forge 维护者可直接更新对应分支；无写权限的贡献者继续使用 Fork PR。编码检查脚本保留供手动调用。平台／加载器差异仍须按实际代码适配，不把实验功能自动合入正式维护版。

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

## 当前发布步骤

1. 从目标提交读取 `mod_version`、`curseforge_release_version`、游戏／加载器／Java 与依赖范围。CHANGELOG 使用正式版本与实际日期，Release 保留准确的 `## English` 标题。
2. 按实际变更选择必要检查，复用仍有效的结果。普通 `build` 只编译打包；测试、示例与附件检查显式执行。单次本地验证及其子进程最多 300 秒，不自动展开全量测试或重复构建。
3. 选择本次发布的 JAR，核对文件名、Manifest、NeoForge 元数据、大小与 SHA-256，记录第三方通知的真实状态。将同一文件附到对应 GitHub Release，镜像阶段不另造同版本包。
4. 公开 Release 后跟踪 Build 与 CurseForge 同步的实际状态，保存失败根因；GitHub 页面可见、工作流成功与外部平台审核分别记录。无需补造历史 CI 证明，不静默替换已有标签或旧附件。
5. 核对平台返回的附件版本、大小与摘要，更新现行状态及双语 README 的版本链接。下方或上方注明日期的历史快照只解释当时结果，不作为现行门禁。

## 文件命名

构建产物由 Gradle 统一命名：

```text
productivebeesgenesis-<mod_version>.jar
```

不要为平台上传手工重命名 JAR，以便校验哈希并确认各平台提供的是同一构建产物。
