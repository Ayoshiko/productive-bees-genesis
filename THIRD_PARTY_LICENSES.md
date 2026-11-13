# 第三方来源与许可证 / Third-Party Sources and Licenses

2026-11-08 勘误：本文件纠正以前发布说明的错误许可名称和使用范围，不改写历史 tag、附件或已有效授予的权利。当前仍有未决来源和授权；登记或署名不等于取得授权。
Correction dated 2026-11-08: this notice corrects earlier license names and use descriptions without rewriting historical tags, artifacts, or valid grants. Unresolved provenance and permissions remain; recording or crediting a work is not permission.

2026-11-13 流程调整：项目取消以未决清单作为自动开发和发布门禁，以下来源记录、第三方条款及实际未决事实保持原状，不由此新增或撤销授权。
Process update dated 2026-11-13: unresolved records no longer act as automatic development or release gates. The provenance records, third-party terms and unresolved facts below remain unchanged; this does not add or revoke a grant.

Productive Bees Genesis 自有且可由其权利人授权的代码、文档、技术资源及已有效采用 MIT 的既有自有素材继续采用 MIT。第三方代码、派生实现、字体及素材保持原始许可；普通运行依赖与实际随包复制内容分别列出。明确文件头和原始第三方许可在其范围内优先，致谢不等于转载许可。
Project-owned code, documentation, technical resources, and existing project artwork validly licensed under MIT remain under MIT to the extent their rights holders can grant it. Third-party code, derived implementations, fonts, and assets retain their original terms. Runtime dependencies are distinguished from copied material included in this distribution. Applicable file notices and original third-party terms take precedence within their scope; attribution is not permission.

## Productive Bees / ProductiveLib

- 中文：Productive Bees 1.21.1-13.13.5（源码 `3c818315d67abc16801626ce292bb207a7383f06`）及 13.14.0（`656641c1054dde8aa8690a10e6bb07ff1e75da41`）声明 All Rights Reserved；不得继续记为 MIT。ProductiveLib 0.2.0 的本机 JAR 同样声明 All Rights Reserved，其源码许可对应仍待补齐。本模组使用外部 API，不将这两个 JAR 内嵌。
- English: Productive Bees 1.21.1-13.13.5 and 13.14.0 declare All Rights Reserved at the source revisions listed above. They must not be described as MIT. The local ProductiveLib 0.2.0 JAR also declares All Rights Reserved; its exact source-license mapping remains unresolved. Genesis uses external APIs and does not embed these JARs.
- 当前空槽提示 / Current slot hints：基因小食和蜂笼图标在客户端读取已安装 PB 的资源，在内存转为灰度，保留透明度并跟随资源包重载；方向箭头由本项目绘制。当前 JAR 不复制这些 PB 位图。旧 `gene_treat.png` 灰度副本及蜂笼图集切片于 2026-11-08 退役，历史来源与授权缺口仍保留在台账。Gene-treat and bee-cage hints read installed PB resources and produce grayscale pixels in client memory. Current JARs do not copy these bitmaps; retiring earlier copies does not resolve historical distribution rights.

## Re:Avaritia

- 中文：代码的上游 MIT 正文保留 `Copyright (c) 2022 cnlimiter`，完整文本随包位于 `LICENSES/ReAvaritia-MIT`；随包保留完整原文。上游 README 另列素材 CC BY-NC-SA 4.0。实际引入 commit 尚未唯一确认；引入日前对照为 `c331b7f4838667e6200b24c99dc45459e0b9dafb`，当前核对分支 `neo/1.21.1` 为 `dc8dc69a8a628507a4ddef0041fa2ac36e9f06db`。
- English: The upstream MIT text for code retains `Copyright (c) 2022 cnlimiter`. The complete text is included at `LICENSES/ReAvaritia-MIT` with its original copyright. The upstream README separately lists assets under CC BY-NC-SA 4.0. The exact imported revision remains unresolved; the historical comparison and current branch revisions are listed above.
- 中文：现行清单保留 6 个相关 Java 文件、6 个 cosmic/hell shader 文件和 12 张 cosmic/halo 图片。Java 包括相关渲染类型、模型、队列与 Iris 适配的复制或修改实现，保留已记录的代码 MIT 和原通知；shader 不只是参数参考。修改包括本地命名空间、配色、图集边界、队列／对象池及 Iris 版本适配，逐文件清单见来源台账。
- English: The current manifest retains six related Java files, six cosmic/hell shader files and twelve cosmic/halo bitmaps. The Java rendering, model, queue and Iris-adapter derivatives retain the recorded code MIT and original notice; shader use is broader than parameter reference. Changes include the local namespace, palette, atlas boundaries, queue/pooling behavior and Iris version adaptation; see the per-file inventory.
- 待取证 / Unresolved：6 个 Java 文件仍保留历史原件／版本、具体归属与更早来源映射缺口；6 个 shader 的 MIT code／CC assets／更早第三方许可适用范围和原作者尚未消歧；12 张图片虽已完整 RGBA 匹配，原美术作者、更早来源及实际用途下的 CC 署名、非商业、相同方式共享条件仍待闭合。根 MIT、Java／GLSL 的技术分类或所在目录不能清零这些具体缺口；缺少唯一引入 commit 本身也不证明当前没有适用公开授权。The six Java files retain incomplete historical original/version and earlier-source attribution; the six shaders retain an unresolved code/assets/earlier-grant scope and attribution; the twelve exact-RGBA bitmaps retain artwork attribution, earlier-source and applicable CC attribution, noncommercial and share-alike use conditions. A root MIT grant or directory/technical classification does not clear these gaps, while missing exact import identity alone does not establish absence of a current public grant.
- `TransformUtils` 与 `PerspectiveModelState` 已于 2026-11-08 退役，当前委托 Minecraft 原生物品变换。历史发行的 covers1624 / CodeChickenLib / Forge 来源仍见下一项。Both helpers were retired on 2026-11-08 in favor of native item transforms; their historical provenance remains recorded below.

## 模型变换工具的多代来源 / Earlier model-transform sources

`TransformUtils` 的上游文件记有 covers1624，CodeChickenLib 同名文件记有 Fry / ForgeBlockStateV1；所读 CodeChickenLib 1.21.1 分支使用 LGPL-2.1。具体复制表达、历史版本及例外尚待确认。本项不宣称已获 MIT 再许可，也不认定所有数学公式均受独占保护。
The upstream transform utility credits covers1624; the matching CodeChickenLib file also credits Fry and ForgeBlockStateV1. The reviewed CodeChickenLib branch uses LGPL-2.1. The exact protected material, historical version, and exceptions remain unresolved. This entry does not assert MIT relicensing permission or exclusive rights in general mathematical formulas.

## Mek-Energistics

中文：按方法核对后，`ModItems.machineItemProperties` 与 `MekCentrifugeBlock` 的部分属性初始化／掉落名称结构对应当前 MIT 源码 `af0745d7c80f22baa18e6063aa8ff168f16ee039`，仅将这些表达登记为有限适配。保留 `Copyright (c) 2026 Mek Energistics`，完整原文随包位于 `LICENSES/Mek-Energistics-MIT`。能量桥当前为公共 API 调用及 Genesis 自身结算逻辑，不据架构相似将整类标为复制，也不把直接 Mekanism 参考的版权归到 Mek-Energistics。
English: Method-level comparison identifies only limited item-property, attribute and drop-name adaptations covered by the current MIT source revision above. The complete copyright and license are included in `LICENSES/Mek-Energistics-MIT`. The current energy bridge uses public APIs with Genesis settlement logic. Architecture similarity does not establish whole-class copying, and direct Mekanism references retain their own attribution.

中文：旧 `AeOutputOverlay` 已整体退役；旧 `AeInputOverlay` 的事件、全局缓存、窗口搜索、人工 bounds 和注入算法主体也已退役，同路径仅保留直输／熔炼消费者所需的四字段 `OverlayTarget` 数据 record。现行 `AeOutputButton`／`AeInputButton` 由原生侧配置窗口创建，依据公开 API、窗口所有权和 Genesis 既有配置合同独立组织，不再使用旧 fallback 组合。旧表达的来源版本 `8aac3322190070e05d9966e84c595212643fe1c3` 当时声明 All Rights Reserved，该类先于根 MIT 引入被删除；后加 MIT 不自动证明已删除表达的历史授权。旧摘要、有限表达范围和替换后的当前摘要分别保留在退役记录；本次替换不改写历史分发事实，也未审计其它覆盖层的全部来源。
English: The former `AeOutputOverlay` is retired in full. The former `AeInputOverlay` event, global-cache, window-search, manual-bounds and injection body is also retired; the existing path retains only the four-field `OverlayTarget` data record required by direct-output and smelting consumers. Current `AeOutputButton` and `AeInputButton` controls belong to the native side-configuration window and are independently organized from public APIs, window ownership and existing Genesis settings. The compared historical fallback was declared All Rights Reserved at revision `8aac3322190070e05d9966e84c595212643fe1c3` and was removed before the root MIT introduction. The later MIT is not evidence of a grant for that removed composition. Historical hashes and limited expression scopes are retained separately from current replacements; this work does not rewrite earlier distributions or claim a complete provenance audit of other overlays.

## UselessMod

中文：本模组调用外部合金炉公开 API，并参考饱和运算和批次成熟窗口。编译基线为 2.4.5.13，当前本机运行文件为 2.4.6，均未内嵌在 Genesis JAR 中。逐方法核对将 `SaturatingMath` 的通用公式、`Ae2PendingBatchBuffer` 的成熟窗口原则，与 `UselessResourceIntegration`／`UselessResourceRecipes` 的公共 API 消费分开登记，不再将四个完整类一律列为来源未决。当前源码 `4f0c2ecb4b2d2b98d02c382554042722f5b2c0ce` 的代码 MIT 可用于现行相应短表达；完整原文及 `Copyright (c) 2025-2026 C-H716 and contributors` 随包保留于 `LICENSES/Useless-MIT`。这不授予 Useless 美术素材权利，其第三方文件例外继续有效。
English: Genesis calls the public alloy-furnace API and references saturating arithmetic and batch maturation. The compile baseline is 2.4.5.13 and the local runtime file is 2.4.6; neither is embedded. Method-level records distinguish general formulas, maturation-window principles and public API consumption without treating all four Genesis classes as copied implementations. The current code MIT at revision `4f0c2ecb4b2d2b98d02c382554042722f5b2c0ce` applies to the corresponding currently published short expressions. Its complete text and `Copyright (c) 2025-2026 C-H716 and contributors` are retained in `LICENSES/Useless-MIT`, without extending the grant to artwork or removing third-party exceptions.

中文：2026-07 最初参考包／下载版本仍未唯一映射，这项历史证据缺口单独保留，不能自动否定当前适用的公开 MIT，也不能将普通公式、思想或 API 签名升级为需重新授权的全类复制。历史保管／恢复 API 补丁仅为开发证据，不作为全部官方版本的能力声明。
English: The original July 2026 reference package or download revision is not uniquely mapped. That historical evidence gap is retained separately; it does not automatically invalidate a current applicable public MIT grant or turn formulas, principles or API signatures into whole-class copies requiring new permission. Historical custody/recovery API patches remain development evidence, not a claim about all official versions.

## Just Dire Things Extras (JDTE)

- 作者 / Copyright：`Copyright (c) 2026 JDTE contributors`。
- 许可 / License：MIT；完整原文位于 `LICENSES/JDTE-MIT`。
- 已核版本 / Verified source revisions：0.5.9-alpha1 → `b320c86e5433351ce66845a8c39974e7893106c5`；0.6.1.0 → `0c456aac6002221ada452ce0f0a0579979ecb13c`；0.6.4.3 → `c18a3feebc85738cd7dca3bddf9b3bb8732a8fef`。三者都有 MIT 原文 / All three contain the MIT text.
- 中文：Genesis 编译使用 0.6.1.0；旧合并加速接口、0.6.1.0 专用资源 Mixin 和存在时才接入的公共事件 API 分开守卫。本机 0.6.4.3 与忽略 build 中的历史本地补丁分开记录，不把补丁能力宣传成官方全部版本支持。
- English: Genesis compiles against 0.6.1.0. The coalesced-tick interface, the version-specific resource Mixin, and the optional public-event API have separate guards. Local runtime files and historical patched builds are recorded separately; patched capabilities are not represented as general upstream support.
- 分发 / Distribution：外部依赖，不内嵌原 JAR。若另行提供 JDTE 修改版/补丁，保留其原版权、MIT全文及修改记录。External dependency; modified upstream builds or patches, if separately distributed, retain their original notices and license.

## Applied Energistics 2

中文：同版 19.2.17 的 `appeng.api.storage.MEStorage` 和 `appeng.api.networking.IGrid` 文件采用 MIT，版权 AlgorithmX2；`BaseActionSource` 等实现文件采用 LGPL-3.0-or-later。Genesis 使用 API，也链接部分内部实现；按实际文件和分发形式处理，不以 API 的 MIT 覆盖全部 AE2。
English: In 19.2.17, the reviewed public API files use MIT, while implementation files such as `BaseActionSource` use LGPL-3.0-or-later. Genesis uses both public interfaces and some implementation classes. Their respective terms are assessed by file and distribution form.

## Applied Flux

中文：编译使用 `AppliedFlux-1.21-2.1.5-neoforge.jar`；本机文件与官方 Modrinth 版本摘要对应。官方项目声明 LGPL-3.0-only，本机元数据为 LGPL-3.0；不得记为 MIT。调用 `FluxKey` / `EnergyType` 与 MEStorage，不内嵌库。若构成适用的组合作品，须履行 LGPL 通知、许可全文及可替换／重链接等条件。
English: The 2.1.5 local JAR matches the official Modrinth version digest. The official project declares LGPL-3.0-only and the JAR metadata states LGPL-3.0, not MIT. Genesis uses its key types and MEStorage without embedding the library. Applicable combined distributions must satisfy the LGPL notice, license-text, replacement/relinking, and related conditions.

## Iris

中文：已核对的 `1.8.8+1.21.1-neoforge` JAR 与官方摘要一致，声明 LGPL-3.0-only，不是 LGPL-2.1。通过可选反射 API 及适配 Mixin 使用 Iris 接口，原库不内嵌。Iris 适配中源自 Re:Avaritia 的部分另保留其来源。
English: The verified Iris 1.8.8 JAR matches the official digest and declares LGPL-3.0-only, not LGPL-2.1. It is an external dependency used through optional reflection and integration Mixins. Any Re:Avaritia-derived portion retains its separate provenance.

## Jade

中文：15.10.5 对应源码 `c7bf17be15d43166d9f378de9865b431c1227a8a` 的 LICENSE 及 JAR 元数据为 CC-BY-NC-SA-4.0，不是 MIT。Genesis 通过官方提供的插件 API 展示自己的状态，不打包 Jade 实现或图像。API 使用与复制/改写 Jade 内容的授权分别判断。
English: Jade 15.10.5 declares CC-BY-NC-SA-4.0 in its source license and JAR metadata, not MIT. Genesis displays its own state through the documented plugin API and does not package Jade implementations or images. API use is distinguished from copying or adapting Jade content.

## AE2 Lightning Tech Reborn / ExperienceLib / Ars Nouveau / PneumaticCraft

中文：分别作为闪电、经验、魔源和空气的外部接口依赖。Reborn 2.1.1 与 ExperienceLib 1.2.1 的 JAR 声明 LGPL3；Ars 5.12.1 声明 GPL 家族，PNC 8.2.21 声明 GPLv3。准确版本的 API 例外、only-or-later、Reborn 源码映射仍待补齐。本项不得当作全模组 GPL/LGPL 的自动结论，亦不得当作所有条件均不适用。
English: These are external lightning, experience, source, and air integrations. Local metadata declares LGPL3 for Reborn and ExperienceLib, GPL-family terms for Ars, and GPLv3 for PneumaticCraft. Exact-version API exceptions, later-version options, and the Reborn source mapping remain unresolved. These facts establish neither automatic relicensing of the entire mod nor blanket exemption from the relevant conditions.

## KubeJS / Rhino

中文：KubeJS 编译版本 2101.7.2-build.368 声明 LGPLv3，Rhino 2101.2.7-build.85 声明 MPL-2.0。插件通过 `kubejs.plugins.txt` 发现，插件与脚本输出为 Genesis 自身实现；原依赖 JAR 未内嵌。若复制/修改 MPL 覆盖文件，保留通知并提供其对应源码；普通接口调用与实际复制分开处理。
English: The local KubeJS and Rhino versions declare LGPLv3 and MPL-2.0 respectively. Genesis supplies its own plugin and script output without embedding those JARs. Copying or modifying MPL-covered files requires the applicable notices and corresponding source; ordinary interface use is distinguished from copying.

## 其他运行依赖 / Other runtime dependencies

Mekanism、Mekanism Extras、EvolvedMekanism、EvolvedMekanismExtras、Just Dire Things、JEI、Industrial Foregoing Souls 和 ProjectE 的本次本机元数据列 MIT；准确素材/文件例外继续以原项目为准。Minecraft、NeoForge、GuideME、Java、LWJGL、ICU、Gson、Guava、Netty、ASM 等由游戏、加载器或独立模组提供，不复制其运行库到 Genesis。未单独核对的历史 SFM、Empowered/Unleashed 条目，不升级为“全部已核许可”。
The listed local mod metadata states MIT, subject to the original projects' file and asset exceptions. Game and general-purpose libraries are supplied externally. This review does not convert unverified historical SFM or Empowered/Unleashed entries into verified license conclusions.

## 仅作研究参考 / Research references

ECO 滚动（GPL3）、ModernUI-MC 字体（实际文件 LGPL-3.0-or-later）、LDLib2 布局/图集（LGPL3）与 OmniSequence 缓存（MIT）用于研究原则；已读范围未证实复制其受保护实现，不作为本字体/界面方案运行依赖。DataEnergistics 为后续候选，当前代码 AGPL-3.0-or-later、资源另保留权利；不得因代码许可开放而复制其资源。主设计还记有 Jecharacters/PinIn 与通用 UI 指引参考，未发现相关库捆绑。
ECO, ModernUI-MC, LDLib2, and OmniSequence are recorded as design references within the reviewed scope. No protected implementation copying was established by this review. DataEnergistics remains a candidate with AGPL-3.0-or-later code and separately reserved resources. Other search/UI references are not embedded libraries.

## 编辑器字体 / Editor fonts

中文：Noto Sans、Noto Sans SC 与 JetBrains Mono 的改名字体子集分别为 Bee Designer Sans、Bee Designer CJK、Bee Designer Mono，继续采用 SIL Open Font License 1.1。版权声明、许可元数据与完整 OFL 随字体保留；字体许可不由项目 MIT 替换。本次审计已经核对字体名称、版权、OFL 元数据和原件摘要；字体未变更。
English: The renamed subsets remain under SIL OFL 1.1, with the original copyright, license metadata, and full OFL retained. The project's MIT grant does not replace the font licenses. The audit verified the original-file hashes, names, copyright, and OFL metadata; the fonts have not changed.

完整文本 / Complete texts:
`assets/productivebeesgenesis/font/notosans_ofl.txt`、
`assets/productivebeesgenesis/font/notosanssc_ofl.txt`、
`assets/productivebeesgenesis/font/jetbrainsmono_ofl.txt`。

## 手册素材及未来作品 / Handbook artwork and future works

中文：手册原件由 gummyc_type2 主动提供给本项目；维护者于 2026-11-08 确认已有明确聊天记录并获准使用。当前 bee_designer.png 保留原形状、透明轮廓和书页，封面改黄，按作者授予的本项目使用许可登记，保留署名。此记录依据维护者本次明确确认，未读取原聊天，不推定版权转让、MIT 再许可或项目外再授权；另一张旧紫色 guide.png 不在此授权范围内。未来作品另按文件和摘要登记。
English: gummyc_type2 supplied the handbook artwork directly for this project. On 2026-11-08 the maintainer confirmed an explicit chat record and permission to use it. The current bee_designer.png retains its form, transparency and pages with a yellow cover, under the author's permission for Productive Bees Genesis project use, with attribution preserved. This record relies on that maintainer confirmation; the original chat was not reviewed. It does not imply copyright assignment, MIT relicensing or sublicensing outside this project, and does not cover the separate older purple guide.png. Future works are recorded separately by file and hash.


## 随包素材与文件映射 / Bundled assets and file mapping

维护者于 2026-11-08 确认：项目 logo、旧紫色指南书、机器外观与几何、编辑器控件和指南截图主要为本人／项目原创或生成。这是分组来源声明，未逐文件独立复核原工程；现有项目 MIT 在自有贡献范围内保留。已明确匹配第三方的图片与代码单独列出，不能用该声明覆盖。
On 2026-11-08 the maintainer confirmed that the project logo, older purple guide, machine appearance/geometry, editor controls and guide screenshots are primarily original or generated project work. This is grouped provenance evidence, without independently reviewed original projects for every file. Existing project MIT terms remain within the project's own rights; identified third-party matches are recorded separately.

已退役的两张 `cage_slot_input.png`／`cage_slot_output.png` 与 Modular Bees 3.3 的 `alveary.png` 区域 `(176,0,16,16)`／`(176,16,16,16)` 逐像素相同，来源为 GlodBlock/ModularBees 的 `87a8bbe5062cf72f56741930ceb1f2fdc40808c0`。该仓库声明 LGPL-3.0；这项匹配不证明底层 PB 图像的授权链，也不证明对方没有单独许可。
The two cage-slot images exactly match the stated Modular Bees 3.3 atlas regions. That repository declares LGPL-3.0; the comparison establishes the immediate source, without establishing any earlier PB image grant or ruling out private permission.

已核对12张cosmic/halo图片与Re:Avaritia1.3.9.9完整RGBA相同，历史来源/作者/许可尚待补齐，不能继续宣称“未复制资产”。The audit identifies twelve matching Re:Avaritia bitmaps; historical authorship and terms remain unresolved. Do not state that no assets are used.

Mekanism的10张匹配纹理须单独列入随包清单，保留 `Copyright (c) 2017-2025 Aidan C. Brady` 及完整MIT（LICENSES/Mekanism-MIT）。Ten matching Mekanism textures require the original copyright and complete MIT notice in the distribution containing them. File-specific exceptions remain subject to confirmation.

三字体实际版权/改名/OFL元数据由A14核对未发现新增缺陷；不把CJK版权仅存于二进制name/CFF误认为删除。A14 found no new defect in the reviewed font copyright, naming, OFL metadata, and packaging. Copyright retained in readable font metadata is not treated as missing merely because the adjacent OFL text lacks a copyright heading.

### 字体逐项声明 / Individual font notices


- **Noto Sans → Bee Designer Sans**: 原字体元数据为 Copyright 2015–2021 Google LLC；随附许可另保留 The Noto Project Authors 的版权声明。来源为 [官方 Regular TTF](https://github.com/notofonts/noto-fonts/blob/main/hinted/ttf/NotoSans/NotoSans-Regular.ttf)。分发字体子集 `assets/productivebeesgenesis/font/designer_sans.ttf`，内部字体名改为 Bee Designer Sans，保留原版权和许可元数据；完整 SIL Open Font License 1.1 位于同目录 `notosans_ofl.txt`。
- **Noto Sans SC → Bee Designer CJK**: © 2014–2021 Adobe；来源为 [官方 SC Regular OTF](https://github.com/notofonts/noto-cjk/blob/main/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf)。分发包含当前翻译、3,755 个 GB2312 一级常用汉字及中文标点的子集 `assets/productivebeesgenesis/font/designer_cjk.otf`，内部字体名改为 Bee Designer CJK，原版权和许可元数据保留。完整 SIL Open Font License 1.1 位于同目录 `notosanssc_ofl.txt`。
- **JetBrains Mono → Bee Designer Mono**: Copyright 2020 The JetBrains Mono Project Authors；来源为 [官方 Regular TTF](https://github.com/JetBrains/JetBrainsMono/blob/master/fonts/ttf/JetBrainsMono-Regular.ttf)。分发等宽子集 `assets/productivebeesgenesis/font/designer_mono.ttf`，内部字体名改为 Bee Designer Mono，原版权和许可元数据保留。完整 SIL Open Font License 1.1 位于同目录 `jetbrainsmono_ofl.txt`。
- **生成 / Generation**: `gradle/generate_designer_fonts.py` 使用固定 FontTools 4.68.0 与三份官方原文件的 SHA-256，保留所选字符、hinting 和横排所需的整形、字距及重音定位特性（ccmp、locl、kern、mark、mkmk、rlig、liga、clig、calt、curs），去除编辑器未使用的竖排及装饰替代特性，并改名派生字体。原始字体、工具和生成报告保存在忽略的 build 目录，只有精简字体与完整 OFL 随 JAR 分发；派生字体继续适用 SIL Open Font License 1.1。
- **实现 / Implementation**: 编辑器自行维护 AWT 字素成形、设备尺寸栅格图集、距离场缩放、度量和输入选区。研究参考 [ModernUI-MC](https://github.com/BloCamLimb/ModernUI-MC) 与 [LDLib2](https://github.com/Low-Drag-MC/LDLib2) 的职责与缓存原则，不分发其字体实现代码，也不将它们作为本字体方案的运行依赖。裁剪外字符使用可用的系统逻辑矢量字体，其次使用 Minecraft 原生字形，不复制系统字体到 JAR。

## 清单与范围 / Manifest and scope

维护源 `gradle/license-manifest.json` 按文件记录来源、原件和源文件摘要、作者、有效许可及未决事实。JAR 内 `META-INF/license-manifest.json` 记录本次源文件与类/资源映射，`META-INF/license-entry-hashes.json` 记录精简后条目的 SHA-256；JAR 总摘要在包外发布记录中，清单不散列自身。`source_sha256` 对应本次现行源文件；`audited_source_sha256` 保留先前审查快照，维修后不同并不覆盖旧摘要。`NOTICE` 提供简明入口，完整原文在 `LICENSES/`，字体 OFL 保留原路径。
The maintained manifest records each file's provenance, original and source hashes, attribution, license, and unresolved facts. The JAR's manifest maps source files to distributed classes/resources; its separate entry-hash index records final uncompressed entry bytes after compaction. The archive hash belongs outside the archive. `source_sha256` records current source bytes while `audited_source_sha256` preserves the earlier audit snapshot after maintenance repairs. Complete texts are in `LICENSES/`; font OFL texts retain their existing paths.

自有语言、行为数据、GLSL、blockstate、模型引用和构建文件不因位于 `assets/` 自动改为专有许可。第三方派生文件单独登记。现有有效 MIT 授权保留；未来委托作品清单为空且未启用，本次清单维护不另行生成权利人同意或私人协议。
Project-owned technical resources do not become proprietary because of their directory. Third-party derivatives are recorded separately. Existing valid MIT grants remain effective. The future commissioned-work list is empty and inactive; manifest reconciliation does not create an additional artist consent or private agreement.

GPL/LGPL 运行库独立安装，适用时允许替换、重新链接和为调试其修改而进行逆向工程；Genesis 的 MIT 不增加这类限制。完整 GPL-3.0 与 LGPL-3.0 正文随包保留。是否满足每个准确版本的组合作品和 API 例外条件仍按清单核实，不能从未内嵌 JAR 推导全部豁免。
GPL/LGPL libraries are installed independently. Where applicable, users may replace or relink them and reverse engineer for debugging modifications; Genesis's MIT grant adds no restriction on those activities. Complete GPL-3.0 and LGPL-3.0 texts are included. Exact-version combined-work and API-exception questions remain recorded; absence of embedded JARs is not a blanket exemption.

当前 `reference-code-history` 的分发未决范围限于已列外部 GPL／LGPL 集成的准确版本 API 例外、only-or-later、Reborn 源码映射，以及实际组合作品、替换／重链接条件。两份参考 MIT 的全文与版权已补齐，七个现行方法级参考类不再归入全类授权未决；已退役覆盖层和模型变换助手的历史缺口留在 `retired_files`。当前文件分类、署名和摘要一致不代替这些外部适用条件的最终判断。
The remaining current `reference-code-history` distribution scope is limited to the listed external GPL/LGPL integrations' exact-version API exceptions, only-or-later terms, Reborn source mapping, and applicable combination, replacement or relinking conditions. The two reference MIT texts and copyrights are present; the seven current method-reviewed reference classes are not unresolved whole-class copies. Historical overlay and transform-helper gaps remain in `retired_files`. File classifications, notices and matching hashes do not decide those remaining external conditions.
