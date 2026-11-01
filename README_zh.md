# 资源蜜蜂：创世 — Forge 1.20.1

**语言：** [English](README.md) · [中文](README_zh.md)

这是资源蜜蜂：创世的 **Forge 1.20.1** 维护分支。本模组是资源蜜蜂与通用机械的附属，
包含万象创世蜜蜂、通用机械蜂箱和离心机，以及可选的 AE2 联动。

| 组件 | 本分支基线 |
| --- | --- |
| 分支 | `forge/1.20.1` |
| 创世移植快照 | `1.2.1-forge-1.20.1` |
| Minecraft | `1.20.1` |
| Forge | `47.4.0` |
| Java | `17` |
| Productive Bees | `1.20.1-12.6.0` |
| Mekanism | `1.20.1-10.4.16.80` |

本次移植由 [mybong9](https://github.com/mybong9) 通过
[PR #13](https://github.com/Ayoshiko/productive-bees-genesis/pull/13) 贡献，
Ayoshiko 维护上游项目并协助本分支维护。
[bpkiu 的独立移植](https://github.com/bpkiu/Productive-Bees-Genesis-1.20.1)
属于另一个仓库与代码来源，其发行文件不属于本分支。

当前版本已移植上游 1.2.1 的资源蜜蜂编辑器、资源产出等功能，并包含编辑器平滑与汉化、发布核验和 Mekanism Extras 工厂初始化修复。
正式 JAR 与 SHA-256 位于 [releases/1.2.1](releases/1.2.1/)。

新增联动的六项编译依赖需要按 `tools/forge_optional_compile_inputs.json` 准备；
无用联动使用本地无用 1.4.6 / 无用核心 1.0.17 的实际接口。可使用
`tools/prepare_optional_compile_inputs.py` 从已有模组目录准备依赖，详见 [构建说明](README-FORGE.md)。
这些第三方 JAR 不随源码或发行 JAR 一同提交。

## 构建

使用 JDK 17 和 Python 3.11 或更高版本：

```sh
python tools/prepare_forge_dependencies.py
./gradlew --no-daemon jarJar reobfJarJar
python tools/verify_forge_artifact.py
```

Windows 下将 `./gradlew` 替换为 `.\gradlew.bat`。
产物为 `build/libs/productivebeesgenesis-1.2.1-forge-1.20.1-all.jar`，
必须在重混淆和产物检查完成后使用。
依赖准备与打包细节见 [README-FORGE.md](README-FORGE.md)。

## 当前状态与兼容边界

本分支移植了上游 1.2.1 的编辑器与扩展资源创作系统；已完成本地编译打包，完整游戏行为和所有可选组合仍需实际运行确认。
保留的旧 JUnit 测试尚未迁移，`build` 和 `test` 不是目前已验证的打包命令。

游戏启动、客户端渲染、可选模组组合、机器行为及存档恢复仍需运行验证。
CI 产物不能视为稳定发行版，也不能证明与另一移植版本的存档兼容。
[PORT-COMPATIBILITY.md](PORT-COMPATIBILITY.md) 记录了移植取舍与待验证项目。
本 Forge 分支尚未配置自动发布到 CurseForge 的流程。

[NeoForge 1.21.1 分支](https://github.com/Ayoshiko/productive-bees-genesis/tree/main-neo/1.21.1)
是独立维护的新版及其文档入口。该分支的依赖、发行文件、编辑器功能与运行验证结论不适用于此分支。

## 参与维护

Forge 修复和翻译请以 **`forge/1.20.1`** 为目标分支。其他贡献者可 Fork 仓库后提交 PR，
注明 Java、Forge、依赖版本、打包结果以及实际验证过的运行场景。
反馈问题时请在标题注明 **Forge 1.20.1**。

本分支通过经审查的 PR 更新，Forge 维护者也使用同一流程。合入需要通过 Forge 平台校验与
Java 17 构建；新改动使旧批准失效，审查对话须先解决。CI、发布、构建输入和平台元数据需要
Ayoshiko 审查。其他分支和发行标签仍由所有者管理。外部 PR 的 Actions 可能需要维护者先批准运行。

两个加载器之间共享的修复应分别适配 API 并验证。不要将整个 Forge 源码树合入
`main-neo/1.21.1`，也不要用 NeoForge 构建配置覆盖本分支。

## 许可证

项目代码采用 [MIT License](LICENSE)。第三方声明见
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)，仅编译期使用的 JDTE 接口来源见
[src/compatApi/README.md](src/compatApi/README.md)。
