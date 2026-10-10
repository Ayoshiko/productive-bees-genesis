# 蜂业网络参考实现与采用边界

[主设计入口与维护规则](bee-processing-network-design.md) · [实施路线与当前状态](bee-processing-network-roadmap.md#current)

本文件维护固定版本、源码入口和采用／拒绝的原则；行为合同与验证结果分别回到主设计或视觉合同、验收记录。历史研究结论保留原版本口径。

<a id="s13"></a>
## 13. 参考实现与证据边界

<a id="s13-1"></a>
### 13.1 本仓库源码入口

- [蜂箱分组与进度](../src/main/java/com/ayoshiko/productivebeesgenesis/apiary/BeeSlotTickProcessor.java)、[产出处理](../src/main/java/com/ayoshiko/productivebeesgenesis/apiary/BeeProduceProcessor.java)、[蜜蜂数据](../src/main/java/com/ayoshiko/productivebeesgenesis/apiary/BeeSlot.java)。说明可以复用已有业务知识，但当前仍与成员槽位和方块上下文耦合。
- [离心上下文](../src/main/java/com/ayoshiko/productivebeesgenesis/mek/PbRecipeContext.java)、[共享虚拟刻计划](../src/main/java/com/ayoshiko/productivebeesgenesis/apiculture/centrifuge/PbVirtualTickPlan.java)、[共享数量采样](../src/main/java/com/ayoshiko/productivebeesgenesis/apiculture/centrifuge/CentrifugeProductionSampling.java)。离心批量保底与原始二项分布不同，数量和仍采用现有近似，设计与验收必须如实反映。
- [现有 AE2 外部存储](../src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/CentrifugeExternalAeStorage.java)、[节点生命周期](../src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2GridNodeManager.java)。已支持 long 数量接口、物品／流体提取和实际插入数量，但后端是本机物理槽位。

<a id="s13-2"></a>
### 13.2 本地其他模组参考

下列路径以仓库根目录为参照，本地参考目录不随仓库分发；记录版本与类名，供开发者定位对应源码。反编译源码用于确认行为和 API，最终实现需独立编写；涉及复制代码时另行核对其许可证及现有第三方声明，本设计未复制第三方实现。

AE2LT 唯一主参考为 `E:/mczuixin/MCkaifa/1.21.1kaifa/闪电全版本/ae2lt-src-2.1.0-beta.5`，2026-09-20 `pull --ff-only` 后仍为 `1d4589b6bd50672051f78d766505530beacfebc0`。EAEP 的 [1.21.1 分支](https://github.com/GaLicn/ExtendedAE_Plus/tree/1.21.1) 同日从 `85a50ea5` 快进至 `93a08b673eaf2145920c1a9bbfb8e36ad1c683f1`，新增上传终端小接口等改动。两个工作树均干净。

ECO 主参考为用户指定的 [v21.1.2 维护分支](https://github.com/DancingSnow0517/NeoECOAEExtension/tree/v21.1.2)，2026-09-20 再次拉取后 HEAD 仍为 `1cae738ad9761d8c06b1902f828dc06dc57548b6`（“优化网络同步增量传输与发包预算”），拉取到新 tag `21.2.0-beta4`；分支与远端一致，工作树干净。gradle.properties 声明 mod_version 为 `21.2.0-beta4`，分支名与发布版本号分别记录。旧 `neoecoaeextension-21.2.0-source` 仅保留作历史比较。

DataEnergistics 的 [1.21 分支](https://github.com/ModularMCLib/DataEnergistics) 位于 `../decompiled-reference/productive-bees-addon-1.21.1/dataenergistics-1.21-source`，2026-09-20 先更新至 `26219f02`，D15 前再次拉取并快进至 `47a416e0aa7f161d8f229cd6b263fcce6c2ac207`（“统一 ProjectE 依赖声明与版本管理 #348”，只涉及依赖声明文件），版本仍为 `3.3.0`。本轮重点复核 `routeExact` 的精确接收与剩余量，以及 `consumeCurrent(BigInteger)` 的扣减；工作树干净。原 `dataenergistics-3.1.1-source` 仅保留历史比较。

Useless 主参考为 `.tmp_useless_src` 的 [1.21 分支](https://github.com/SorrowMist/UselessMod/tree/1.21)，2026-09-20 从 `267b38a6` 快进至 `951e8bd8fda9b07d596b5547b13bf3eaabffdee6`（“增加无限配置”），版本 `1.21.1-2.3.8.3`，工作树干净。前轮核对的 `AlloyFurnaceBigIntegerCpuAdapter.claimOutputs` 仍是所有权交付参考；本轮不把新配置功能视为已经完成审查或直接采用的设计。旧 `uselessmod-1.21.1-2.2.4-fix1-source` 只作历史参考。

D15b1 于同日再次检查边界与工作树后，对本步使用的两个仓库执行 `pull --ff-only`：Useless 快进至 `abafc543e0d5961d84e83014ebd583ea6e554335`，DataEnergistics 快进至 `a1be3eb402879abde55368ed65fe2b0ac3bfefaa`，两者工作树干净。本步读取 Useless 的 `AlloyFurnaceBigIntegerCpuAdapter/Adapters`（提交后通知、整批接管及注册生命周期）和 DataEnergistics 的 `PersistentTrinityPatternCore.applyPersistedState/TrinityHostedActionTicket`（恢复候选及代际）；新增区块卸载 Mixin、采矿与其它无关更新不属于本步审查范围，其余参考版本仍为 D15a 核实时刻。

D16b2c3b 于 2026-09-22 使用当前系统代理再次 `pull --ff-only`，DataEnergistics 快进至 `7416267860ee446bf45bceb1bd0aeb43d848ebf4`，版本仍为 3.3.0，工作树干净。恢复候选及动作代际两个参考类与上述提交无差异；本步采用范围与测试对应关系见 [10.37](bee-processing-network-evidence-production.md#s10-37)，未宣称审查全部上游新增功能。

复用已固定版本时直接引用已读入口，不把每轮联网拉取当作前置；只有任务需要更新上游或现有 API／行为发生变化时，先确认独立仓库边界、工作树和上游，再执行 `pull --ff-only`；若有本地改动或不能快进，保留现场并记录原因，不自动 stash／reset。PB 13.13.5、Mekanism 10.7.19.85 的版本源码、`.tmp_gtnh_src`／`.tmp_gtceu_src` 摘录与 Thunderbolt JAR 没有可拉取的独立 Git 元数据，不能报为已更新；固定依赖 API 继续以实际编译 JAR 为准。第 5.4 节保留存储算法原审查提交，最新工作副本与本轮新增核对范围以本节为准。

AE2LT 参考源码使用 NeoForge 21.1.220，EAEP 使用 21.1.238，ECO 使用 21.1.233、Useless 使用 21.1.249；这些是历史参考快照的 API 基线；本项目当前开发基线为 21.1.216、发布最低为 21.1.214，不因参考它们而自动升级依赖。涉及具体生命周期 API 时以本项目编译基线重新验证。

| 来源与本地位置 | 已检查内容 | 借鉴及边界 |
| --- | --- | --- |
| `../../闪电全版本/ae2lt-src-2.1.0-beta.5` | `InfiniteStorageCellItem`、`ModItems`、`LayeredReservedStockPolicy`、`ReservedStockRepository`、`InventoryMaintenanceDecision`、`TianshuInventoryMaintenanceService` | 索引存储定义、双层组保留、滞回和 requester；库存实现委托 Thunderbolt，不把外壳类当完整存储引擎 |
| `run/mods/thunderbolt-2.0.0-beta.3.jar` | javap 核对 `core.storage.cell.IndexedStorage`、`DualLong126`、`IndexedStorageCellInventory`、`IndexedCellStorageSavedData`、`core.crafting.pattern.CraftingStockPolicy` | 对齐上述 AE2LT 声明的依赖；确认 primitive 数组与有限双 long、存储生命周期及非原生的合成策略接口 |
| `../decompiled-reference/productive-bees-addon-1.21.1/extendedae-plus-1.21.1-source` | `util/storage/InfinityDataStorage`、`InfinityStorageManager`、`api/storage/InfinityBigIntegerCellInventory` | long／BigInteger 双层、降级、总数增量缓存、storageRevision、纯模拟、饱和上报；已替换旧 1.20.1 副本 |
| `.tmp_neoccoaeextension_src`（main／21.2.0，`70cea49f`） | `ECOInfiniteStorageData.save/add/subtract/replayJournal`、`ECOInfiniteStorage.neoecoae$visitExactAmounts` | 普通量变标脏、结构缓存 revision、原子快照、旧日志迁移和所有可见键的精确观察；早期 `neoecoaeextension-v21.1.2-source` 仅保留历史依据 |
| `../../闪电全版本/Thunderbolt-Core-Reborn`（`42e0e7d2`） | `IndexedStorage.insert/insertExact/setAmountExact`、`BigAmounts`、`BigStorageOps` | 按键数组、结构／数量脏分离和实际接受量桥；精确模式仍有 16,384 位上限及大数开销，不采用其容量限制 |
| `.tmp_useless_src`（2.4.5.4／`1733aef5`，本步复核见 [13.16](#s13-16)） | `AlloyFurnaceBigIntegerCpuAdapter.claimOutputs`、`AdvancedAlloyFurnaceAeManager`、`MultiblockRecoveryData` | 大数产物按键整批交付、只回网剩余量；接收意味着库存所有权转移。异常后按零接收继续普通插入不能用于结果未知的权威交接；新配置改动尚未作为实现依据 |
| `../decompiled-reference/productive-bees-addon-1.21.1/dataenergistics-1.21-source` | `TrinityDataCoreStorageSavedData`、`TrinityDataCoreStorageProfile`、`PersistentTrinityPatternCore`、`TrinityHostedActionTicket` | 宿主身份、BigInteger、分类总数、排序缓存、拆卸作业保管／认领和窗口代际；其存储读取中坏记录跳过与未知 schema 返回空对象不能用于本项目权威域 |
| `../decompiled-reference/productive-bees-addon-1.21.1/ae2-19.2.17-decompiled` | `appeng/api/storage/MEStorage`、`IStorageProvider`、`api/networking/storage/IStorageService`、`me/service/StorageService` | 稳定库存提供者、long 操作、挂载生命周期；缓存更新仍会枚举库存，不能假设免费增量 |
| `../decompiled-reference/productive-bees-addon-1.21.1/mekanism-10.7.19.85-sources` | `common/content/qio/QIOFrequency`、`common/inventory/container/QIOItemViewerContainer` | 库存键索引、updatedItems、只向查看者同步、避免同时保存的思路；QIO 有容量且其终端协议不等于本方案的服务端分页 |
| `../decompiled-reference/productive-bees-addon-1.21.1/productivebees-13.13.5-decompiled`；`libs/productivebees-1.21.1-13.13.5.jar` | `AdvancedBeehiveBlockEntity` 的模拟／蜂笼／释放入口、`ConfigurableBee` 花源；JAR 的两条 `anvil_repair*` 配方 | 保留身份与真实副作用边界；铁蜂只有在关闭转化的已审查路径中才属于当前静态适配，不直接托管原版蜂箱 |
| `.tmp_gtceu_src` | `MEStockingBusPartMachine`、`ExportOnlyAEItemSlot` | 库存视图与真实消耗分离、周期刷新及防重复匹配；本地摘录未核实发行版本，不照搬 API 或轮询频率 |
| `.tmp_gtnh_src` | KubaTech `MTEMegaIndustrialApiary`、`MTEIndustrialApiary` | 集中蜜蜂数据、花朵需求去重、缺失原因可见；属于旧 Forestry／Forge 技术栈，只借鉴职责，不套用 1.21.1 API |

相关项目：[Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)、[Mekanism](https://github.com/mekanism/Mekanism)、[GTCEu Modern](https://github.com/GregTechCEu/GregTech-Modern)、[KubaTech](https://github.com/GTNewHorizons/KubaTech)。这些仓库主页是定位入口；五个独立 Git 参考仓库均已在 D15 前联网 `pull --ff-only` 并固定上述提交；更新状态以拉取时刻为准，也没有将未经读取的网上介绍作为性能排名。

本地旧研究 `docs/2026-09-15-天枢库存保留研究与对齐方案.md` 是背景资料，其中旧 AE2LT 路径属于历史锚点；合成库存策略的实施结论以[主合同 6.6](bee-processing-network-design.md#s6-6)及新版源码为准。无需为使用保留算法强行引入 Thunderbolt；只有其可选合成扩展需要独立兼容边界。

D16c2b 于 2026-09-23 核对独立 Git 根和干净工作树后更新本轮实际使用的两个参考副本：`.tmp_dataenergistics_src`／`1.21` 为 `5623cc0fbb978879abf6edb1ec0807d644c89819`，`.tmp_neoccoaeextension_src`／`v21.1.2` 为 `7249cb56ba6500668834c18abb8ea85a26453036`。直连 GitHub 失败后通过本机代理完成 `pull --ff-only`，没有更改全局 Git／TLS 设置。复核 DataEnergistics 的 `TrinityHostedActionTicket`、`TrinityHostedActionPayloadHandler.route/claimRoutedAction` 与 ECO `MenuDataTransport.send/tick/receive/stopped`：采用菜单身份、先占动作序号、服务端重建参数、每玩家预算及关闭清理；本项目没有 hosted 子窗口、整表分片队列或 AE2 依赖，使用单个八行回复并由原版开菜单数据下发 nonce。未审查这些仓库的其它上游更新。
<a id="s13-3"></a>
### 13.3 进一步优化与逐步参考路线（2026-09-19 更新）

设计的进一步优化集中在实现顺序、证据边界和故障协议。前轮已复读 DataEnergistics 的 `TrinityDataCoreStorageSavedData`、`PersistentTrinityPatternCore`、`TrinityHostedActionTicket`，ECO 的 `ECOInfiniteStorageDomains/Data/Transfer`、`NEClusterCalculator` 及 AE2LT `InfiniteStorageCellItem`。D09b2a／b2b 实际继续核对 DataEnergistics `HostState.insert/extract/orderedEntries/invalidateView`、ECO `ECOInfiniteStorageData.save(File, ...)`，以及本项目 NeoForge 21.1.214 的 `SavedData`／`DimensionDataStorage`：增量维护状态可借鉴，整表 UI 缓存不能代替权威冻结，原生保存还会整棵复制 NBT，后台写盘不等于主线程工作已预算化。

1. **先区分持久化正确性与保存性能。** D09a 已建立完整 P1 权威 checkpoint、严格读取和真实写入回执；D09b1／b2a 解决余额与元数据的一致冻结，D09b2b 已将纯编码／压缩／原子发布交给唯一后台流任务，b3 已完成预算加载及一致发布。异步任务只读封闭的不可变 payload；加载注册表校验不可据此转到后台，累计分配及大文件等待仍要计量。内存分页不强制磁盘分文件，优先验证单文件续写与原子替换，避免过早增加 manifest／文件回收协议。D09b 未验证前不开放大规模接管。后续新增蜜蜂、供给、储能、升级、交接记录时必须同步升级 schema 和全域恢复测试，不能只往 BE 添一段 NBT。
2. **参考失败路径同样重要。** DataEnergistics 的 detached runtime 保管、claimant、fingerprint 适合学习；它的产物存储 `load/readEntries` 对部分错误返回空对象或跳过记录，不适合本项目的数量守恒目标。ECO 当前原子快照、坏记录保留与写失败重试可作参考；历史逐操作 WAL 与当前 SavedData 周期保存是不同合同。我们继续以自己的完整 checkpoint、真实完成回执和当前 revision 为准，不复制异步回调对可变域状态的访问。
3. **身份引用不等于可执行所有权。** DataEnergistics 的 host/storage/removal 身份和窗口 generation 可用于 D12／D19；ECO 的 seal → cursor → insertOnce receipt → commit → complete 可用于交接。我们仍须覆盖区块与世界数据不同步、旧 BE 残留、source/target 冲突和未知外部结果，不能照搬它们后宣称跨模组原子性。
4. **拓扑算法必须匹配形状。** ECO `NEClusterCalculator` 使用定形结构的包围盒扫描和 AE2 MBCalculator，适合作生命周期对照；本项目为任意六面连通图，仍需 epoch 绑定的预算 BFS、断边分裂和 owner 校验，不能换成无界范围扫描，也不能因此引入 AE2 硬依赖。
5. **查询优化与权威库存分开。** 学习 DataEnergistics 的分类总量、排序缓存和不可变 UI frame，Mekanism QIO 的脏键／订阅；大数精确显示参考 ECO 的独立精确数量协议。排序、统计、终端同步和 AE2 long 投影都不能回写精确余额。AE2LT 实际存储委托 Thunderbolt，数组和数值范围结论必须核对依赖实现。
6. **把不变量维护前移，但保留不可信输入校验。** 内存中的预约状态、成员／通道和滞回由所属服务在更新时维护；统一捕获只冻结已验证的根。文件、外部 Map／List 及恢复构造仍逐项检查，不能用一个公开“trusted”开关跳过校验。新 API 必须拒绝混用不同账本、跨线程／重入捕获、能力版本倒退与未来策略。记录构造和变更、快照冻结、编码、回执分开计量，不能把整表扫描藏在捕获前的构造器中。

**2026-09-28 存储与交付复核：** 基于更新的 `notes.md` 和已固定的本地 ECO `70cea49f`、Thunderbolt Reborn `42e0e7d2` 逐项核对，工作副本独立且干净，本次没有切换或更新参考分支。ECO 已退役逐操作日志，仅在加载旧世界时重放并在快照成功后清理；数量变更只标脏、结构变更推进缓存 revision，不能直接照搬到本项目资产 revision。Thunderbolt 的精确模式将普通 long 调用也导向大数运算；`BigStorageOps` 的单次 long 退化只返回实际接受量，剩余量必须由原所有者继续保管，整表 `snapshotBig` 也不能进入有预算的终端热路径。用户提供的“约 37R 物品／tick、6 ms”仍缺少同场景原始 Spark／活网证据，不作为已复现指标。

后续 D15／D16／D20／D28 的基准须同时记录精确总量、不同键数、组件大小、存取调用数、Long.MAX_VALUE 分段次数、实际接受量、持久化方式、查看者数量及计时范围。Useless 新增 `claimOutputs` 说明大数产物可由 CPU 的精确账本整批接收，从而减少重复 long 分段；本项目内部产物与中间投入也应按键汇总直接在领域账本结算，仅在外部 long 接口边界切段。外部回调抛异常时，不能沿用“视为零接收并重试”的降级：接收结果未知时保留暂存并隔离，须有幂等收据才能重试。

ECO 本轮 `ExactMapSync`／`MenuDataTransport` 采用全量基线加增量、每菜单／通道一个在途消息、每玩家共享发送预算和关闭时取消；这些用于 D18／D19 的同步设计。其整消息大小限制属于传输限制，不能转成产物库容量限制。DataEnergistics `196ca488` 的恢复修复在 `stateRestoreDepth` 内合并脏标记，直到正常服务器 tick 才发布，避免 `setLevel/onLoad` 中重入区块加载；本项目 D12 继续保持 NBT 读取只恢复引用、所有权状态机在服务器 tick 推进。恢复服务必须以当前目录 revision 等待回执，不能拿最后已保存 revision 为同进程重建后的新操作背书。

下表给每一步增加明确的本地学习入口、拟采用内容和必须自行验证的差异。简称路径均见 [13.2](#s13-2)；已完成步骤用于补查回归，未来步骤是实施前的必读清单，不代表其全部调用链已经验证或允许直接复制代码。

| 步骤 | 本地学习入口 | 拟采用内容／需要保留的边界 |
| --- | --- | --- |
| D01 | PB `AdvancedBeehiveBlockEntity`；Mekanism 原机器／工厂 tick | 用真实独立机语义固定产出与能耗，Spark 必须同配置同吞吐比较 |
| D02 | EAEP `InfinityDataStorage`；AE2LT＋Thunderbolt；DataEnergistics／ECO 存储 | 比较相同数量和保存合同，区分宿主外壳、后端布局、索引和 fsync 成本 |
| D03 | Mekanism 工厂／升级；本项目 `PbVirtualTickPlan`；ECO 计算组件 | 每机先计算能力，再汇总；保留逐通道计费与余数，不复制另一模组的等级倍率 |
| D04 | EAEP long／BigInteger 升降级；ECO `InfiniteStorageAmounts`；AE2 键 | 精确值与 long 投影分离，完整组件身份和归零回收独立验证 |
| D05 | PB `AdvancedBeehiveRecipe`／特殊蜂种；ProductiveLib `TagOutputRecipe`；本项目 `CentrifugeRecipeIndex`；AE2 key codec | 从真实产物链编译资格与蜂型，标签不签发资格，拒绝未知组件及占位键 |
| D06 | AE2 `MEStorage`；本项目 `PbRecipeFlusher`；ECO `ECOInfiniteStorageTransfer`；DataEnergistics 作业 outbox | 模拟不变、实际量／剩余量结算；预约、所有权和未决输出分离，外部未知结果只靠原收据解决 |
| D07 | AE2LT `LayeredReservedStockPolicy`、`ReservedStockRepository` | 全局／局部约束取交集，共享组额度不能逐变体重复消费 |
| D08 | AE2LT `InventoryMaintenanceDecision`、维护服务；ECO 调度器 | 保留滞回和优先级，公平性用本项目的重叠规则测试证明 |
| D09a | ECO `ECOInfiniteStorageData.save`；DataEnergistics detached runtime；NeoForge SavedData | 明确创建／读取，完整明细恢复，成功回执与 revision，坏文件不覆盖 |
| D09b1 | DataEnergistics `orderedEntries`；Thunderbolt `IndexedStorage` | 区分 UI 缓存与权威快照，primitive 页及稳定键身份；独立验证碰撞、写时复制与变更代价 |
| D09b2a | DataEnergistics `HostState` 增量统计／视图失效；本项目 D09a／b1 | 更新时维护不可变记录根，统一冻结；公开／恢复输入不跳过校验，元数据与余额都测量 |
| D09b2b | ECO `save(File, ...)`；NeoForge SavedData／DimensionDataStorage；本项目 b2a 不可变根 | 保留成功回执及原子替换；不沿用整域 NBT 构造／复制，后台仅接收不可变 payload，固定流缓冲形成背压；嵌套集合逐项输出，失败／旧回执／队列公平单独验收 |
| D09b3a | DataEnergistics `readEntries/readAmount`；NeoForge `NbtIo/StreamTagVisitor` 与数组 Tag；ECO 域加载隔离 | visitor 不保证数组分片或可恢复 HALT；完整语法、分片背压、CRC 和取消独立验收，不跳过记录 |
| D09b3b | DataEnergistics `HostState.putLoaded`；本项目 `ProductKeyCodec`、D09a 严格 codecs、D09b 不可变根 | 注册表校验在所属线程，恢复时增量维护约束与索引；最终封装不重扫全域，不用公开 trusted 开关跳过输入检查 |
| D09b3c | ECO `ECOInfiniteStorageDomains`；DataEnergistics detached runtime／host generation；NeoForge `DimensionDataStorage` | 显式创建／加载、代际失效及成功回执后发布；重载和旧完成回调不能恢复已取消域，正常 tick 不等待 IO |
| D10 | ECO `NEClusterCalculator`；Mekanism 邻接／多方块生命周期；AE2 节点生命周期 | 学习形成／失效与事件合并，拓扑仍为单区块预算 BFS，不沿用定形扫描 |
| D11 | Mekanism `TileEntityMekanism.tickServer`；本地 JDTE 合并接口 | 审计父类组件、ticker、flush、能力写入口；不能只 return 子类生产方法 |
| D12 | ECO seal／迁移收据／restoreTarget；DataEnergistics detached runtime 与恢复深度；Useless 整批交付 | 当前 revision 回执门控、恢复不得重入区块加载、接收即转移所有权；真实蜂笼／pending／能量专属适配，异常不推断零接收 |
| D13 | PB `beeReleasePostAction/simulateBee`、`anvil_repair*`；KubaTech `onStorageContentChanged`；Useless `claimOutputs`；ECO `InfiniteStorageAmounts` | 已核对逐栈基因、世界副作用、只读花源和按键交付。D13b 以关闭转化为静态铁蜂前提，逐蜂保存未处理轮数与冻结数量；旧映像迁出资产，交还重建当前值；旧 Forestry 只作职责参考 |
| D14 | PB `FeederBlockEntity.getInventoryItems`；Mekanism `getLimit/OVERSIZED_ITEM_CODEC`；更新后的 DataEnergistics `routeExact/consumeCurrent` | 已采用有限原始实物、严格组件往返、实际接收量与同批剩余量。三格容量不足整体拒绝，显式共享不复制样本；旧 KubaTech 摘录仅保留前轮职责背景 |
| D15 | PB `CentrifugeBlockEntity/CentrifugeRecipe`；Mekanism `CachedRecipe.process`；ECO `ECOCraftingDispatchAccounting`；DataEnergistics `routeExact/consumeCurrent` | D15a 保留本项目输入／倍率、保底概率和逐 lane 计费差异；D15b 分开预约、付费推进、结果冻结与实际量交付，固定周期内能力和并行数，拒绝免费重开作业 |
| D16 | ECO `ECOCraftingTaskScheduler`／能量事务；AE2 `TickManagerService/EnergyService` | 真实 tick 编排、检查预算、公平续扫、到期与休眠；回调间隔不等于生产信用。D16b2 继续核对输入代际、保留规则及库存索引 |
| D17 | Mekanism 升级组件；DataEnergistics hosted action | 按 member revision 安装／拆卸，先预约接收空间，逐机返回真实结果 |
| D18 | QIO 订阅／updatedItems；DataEnergistics StorageView／ContentsList；ECO ExactMapSync／MenuDataTransport | 同一不可变查询 frame、服务端分页、增量刷新，不能全表每帧排序 |
| D19 | DataEnergistics `TrinityHostedActionTicket`；AE2 菜单权限；本项目 `PayloadRateLimiter` | 会话 generation／sequence、成员 revision、真实来源槽、频率及距离校验 |
| D20 | AE2 `MEStorage/IStorageProvider/StorageService`；ECO 精确数量协议 | 稳定单桥、实际量结算、超 long 聚合回归；核心无 AE2 类型依赖 |
| D21 | GTCEu `MEStockingBusPartMachine`；AE2LT 维护服务 | 候选缓存与实际提取分离，排除自身、共享来源协调及最终保留校验 |
| D22 | AE2LT 滞回／目标维护；ECO 大订单 admission／progress | 目标扣除在途／在制，概率副产物避免重复需求，不直接照搬合成请求 |
| D23 | Mekanism ejector／capability；AE2 外部存储策略 | 有限槽／罐视图、部分接受、回调重入及无掉落物归还 |
| D24 | 本地 Mekanism Extras／Evolved／EMExtras 工厂 | 每个已声明组合逐级能力和接管测试，运行时 loader 守卫 |
| D25 | PB 13.13.5 特殊蜂；现有 WannaBee／Myriad 适配 | 每种动态产物独立冻结与恢复，禁用条件／关联概率不能省略 |
| D26 | JDTE 0.5.9-alpha4 合并接口；Mekanism 区块生命周期 | ticker／flush 顺序、64×／256×、路径失联和代际校验 |
| D27 | PB／Mekanism 重载；DataEnergistics 持久核心 hydrate | 新计划用新版本，旧已付费结果可结算；NeoForge 配置和双语界面同步 |
| D28 | D02 全部候选；DataEnergistics／ECO／Thunderbolt 后端；Useless claimOutputs | 按键汇总与外部 long 分段分别计数；比较相同保存合同、键分布、内存和尾延迟，不以总物品量替代操作量 |
| D29 | ECO 存储／迁移故障测试；DataEnergistics 恢复／重复身份测试 | 逐阶段故障注入和独立守恒模型，不只覆盖成功 round-trip |
| D30 | D01 当前基线；各模组真实安装组合 | 同产出同能耗 A/B、Spark 调用树和客户端／专服，不能用微基准替代 |
| D31 | 本项目 verifyReleaseArtifact 与第三方依赖元数据 | 检查无探针／参考源码泄漏、依赖与资源归属，再准备发布候选 |
| D32 | AE2 requester API；AE2LT／Thunderbolt crafting stock policy；ECO 大订单 | 请求去重和 link 恢复需单独兼容，不把外部来源保留误当原生 AE2 全局合成约束 |

<a id="s13-4"></a>
### 13.4 前轮参考资料整理记录

已移入 Windows 回收站：本仓库 `.tmp_ae2lt_2_0_7`、`.tmp_ae2lt_207`、`.tmp_ae2lt_208`、`.tmp_ae2lt_208_src`、`.tmp_ae2lt_src`；参考目录的 `ae2lt-2.0.7-decompiled`、`ae2lt-2.0.9-source`、`source-archives/reference-ae2lt-2.0.9.zip`；`闪电全版本/AE2-Lightning-Tech-1.20.1`。共 8 个旧目录和 1 个源码压缩包。

EAEP 新版成功克隆并验证后，旧 `.tmp_eaeplus_src` 1.20.1 副本也已移入回收站。清理前核对了绝对路径、重解析点及存在 Git 元数据的源码工作区状态，未发现待保留的未提交变更；没有永久清空回收站。运行 JAR、Thunderbolt Core、独立 Packaged Pattern Provider、调试日志和其他模组的 AE2LT 集成代码保留。

<a id="s13-5"></a>
### 13.5 多方块报告的采用、校正与追踪（2026-09-22）

用户新增的本地 `docs/reference-multiblock-implementations.md` 为研究输入，按现有忽略规则留在本地；本节与上述合同在版本控制中保留完整结论，不要求远端读者拥有报告或第三方源码。报告当时仍标为编写中，不能把各节建议直接当成已验证 API 或已经完成的实现。

| 本地快照／读取入口 | 学习原则 | 本项目差异与验收去向 |
| --- | --- | --- |
| DataEnergistics 3.3.0／`e028ff2e`，`.tmp_dataenergistics_src`，`VerticalMultiBlockScanner/Definition` | 定义、注入查询、朝向与扫描结果分离，扫描可纯测试 | 拆开候选遍历和逐格预算；未知区块独立建模，不直接复制一次全量扫描；M01／M02 |
| Neo ECO AE／`185e1f67`，`.tmp_neoccoaeextension_src`，`MultiBlockRotation`，报告中的角色／placement 分析 | 控制器锚点统一局部坐标、扫描／预览／搭建复用定义 | 支持每模板自定锚点；竖直朝向明确拒绝，不静默当 NORTH；位置和方块朝向均需旋转；M01／M02／M07 |
| AE2 19.2.17，`.tmp_ae2_19_2_17`，`MBCalculator` | 发布顺序、邻居过滤与提交重入保护 | 不继承 AE2；epoch 拒绝过期结果另加重入守卫，按机器占位拒绝重叠，允许相邻独立矩形；M03 |
| AE2LT／`1d4589b6`，[13.2](#s13-2) 主路径，`TianshuMultiblockScanner/TianshuCoreEffectRenderer/MatrixCoreEffectRenderer` | 同一局部核心位置驱动 BER 与有限剔除盒；天枢缓动与矩阵实体／光晕分层，[10.57](bee-processing-network-evidence-current.md#s10-57) 再核对 | 任意尺寸逐个覆盖区块；不依赖固定 7³ 的四角假设，不引 Veil；实际材质／姿态用本项目客户端验证；M01／M02／M06 |
| OmniSequence 2.0.6／`567e73d`，`.tmp_omnisequence_src`，`SequenceCrownAnimation/MolecularCenterBlockEntity`（[10.55](bee-processing-network-evidence-current.md#s10-55)／[10.56](bee-processing-network-evidence-current.md#s10-56) 复核） | 游戏时间驱动瞬态状态、完成脉冲限频，无库存／工作存档 | 独立实现完整帧身份／序号基线、单后继替换、重载／卸载清理；每机脉冲间隔不代替共享活动流预算；M06 |
| 报告 §2／§3 AAE／EAE 角色和连接材质，§7 ECO 接口／仓口 | 角／棱／面／内部分类、接口与有限 IO 分工 | 本轮作为设计参考；具体模型 loader、更新 flag 和能力 API 在 M03／M06 对实际 NeoForge 21.1.214 再核对 |

以上为本轮读取的本地快照，不声称已同步所有上游仓库。需要更新参考时先核对其 Git 根、分支与脏状态，保留用户本地变更；M 阶段再按使用的确切入口重新读取，不复制源码或额外引入库。

对报告的关键校正：①长方体几何并非不适合本项目，它用于独立机器，BFS 用于蜂业图；②`MBCalculator` 的边界扫描不意味着只支持实心内部，子类可定义空心／角色约束；③epoch 并非全局互斥锁；④四角已加载不能证明更大结构的中间区块已加载；⑤内部主核不是蜂业控制核心，不能继承两份身份／账本；⑥饱和运算仅适用于有上限的能力／展示，不能用于权威产物余额；⑦服务端同步低频事实，纹理／动画相位由客户端派生。按这些差异写验收，而不是照搬参考模组的平衡数值、掉落库存或 AE2 基类。

<a id="s13-6"></a>
### 13.6 官方文档

- [NeoForge 1.21.1 Saved Data](https://docs.neoforged.net/docs/1.21.1/datastorage/saveddata/)：本次联网确认 `SavedData.Factory`、`DimensionDataStorage`、`setDirty` 生命周期。
- [NeoForge 1.21.1 Payload](https://docs.neoforged.net/docs/1.21.1/networking/payload/)：本次联网确认 `PayloadRegistrar`、`CustomPacketPayload`、`enqueueWork`；处理线程仍以实际注册方式校验，主线程变更不得直接在网络线程执行。
- 能力注册接入以当前项目 `Ae2CapabilityRegistrar` 及编译基线 NeoForge API 为依据；其生命周期与失效行为在实施时核对对应版本源码。

<a id="s13-7"></a>
### 13.7 真实访客交换与玩家恢复参考（2026-09-30）

本步复读固定 Mekanism 10.7.19.85 的 `SecurityUtils.canAccessObject` 和 `SecurityFrequency.isTrusted/addTrusted/removeTrusted`：采用服务器 UUID 授权和变化标脏；本项目继续拒绝客户端乐观授权、PUBLIC 模式与 OP 绕过，所有者和访客的管理能力分开。

从本 worktree 的 `build/moddev/artifacts/neoforge-21.1.216-sources.jar` 复核 Minecraft 1.21.1 的 `PlayerList.remove/save/load`、`PlayerDataStorage.save/load` 与 `Util.safeReplaceFile`：让真正 TCP 登录触发原版 player.dat 保存和下一 JVM 加载，探针只在首次新世界种子阶段设置资产，恢复阶段不加载替代玩家 NBT、不重建核心或注入背包。这是固定依赖源码核对，不声称更新了上游参考仓库。

恢复比较须尊重数据语义：产物余额是按完整 ProductKey 标识的映射，序列化列表的遍历顺序不是资产身份。c3b2 使用本项目严格 checkpoint 解码器后比较全部状态，保留完整组件、精确数量、revision、蜂位、食物、授权和调度检查；不删除发生差异的字段，也不放宽非法／重复键验证。实际运行与失败修复见[10.68](bee-processing-network-evidence-current.md#s10-68)。

<a id="s13-8"></a>
### 13.8 同时在线玩家与请求顺序参考（2026-09-30）

复读固定 Mekanism 10.7.19.85 的 `PacketQIOItemViewerSlotTake.handle`：即使玩家已看到库存，执行时仍重新校验当前菜单、同组件堆叠和实际接收空间，并使用真实提取量更新接收方。本项目采用“显示快照不授予资产所有权”的原则，继续通过当前账本和有限背包服务提交，不引入 QIO 鼠标携带栈或回存失败后的掉落补偿。

本地 NeoForge 21.1.216 `PayloadRegistrar.playBidirectional/executesOn` 用于核对 MAIN 队列。开发探针用独立的小型阶段信号确认两名玩家都已读取提交前库存，并在重放请求之后通过同一连接确认服务器已处理；信号仅同步验收阶段与结果，不包含资产键、权限或转移方法。实际存取使用正式 GUI／TerminalRequest 的 TCP 路径。阶段信号和服务器／客户端驱动仅位于 domainProbe 源集，发布 JAR 必须排除。

同时启动两个真实客户端须使用不同的 ModDev run 名称，否则同一工作区生成的启动配置可能被另一个角色覆盖。两个客户端目录、专服世界和正常重启副本各自独立；客户端不共享可写世界。实际证据见[10.69](bee-processing-network-evidence-current.md#s10-69)。

<a id="s13-9"></a>
### 13.9 逐机升级实物与旧工作段（2026-09-30）

D17a 复核固定 Mekanism 10.7.19.85 `TileComponentUpgrade.tickServer/addUpgrades/removeUpgrade/serialize/deserialize`、`Upgrade.getMax/getTag/buildMap`：采用服务器线程、当前上限、先预检接收空间再按实际量扣除、安装记录与输入／输出槽分离。其已安装状态只保存类型／数量，本项目只接收标准完整组件，拒绝有自定义组件的升级件；不采用宽松反序列化的 ordinal 回绕、重复键覆盖或非正数忽略。网络直接改唯一封存映像，原生物理组件保持清空，不调用其 ticker 或产生第二份已安装状态。

本步检查 `.tmp_dataenergistics_src` 为独立干净根后，从 `c95a1244` fast-forward 到 `965b9d98`（`1.21`）；本次上游变化为 AE2 合成 CPU 列表修正及版本／变更记录，不扩大审查范围。复读 `TrinityHostedActionTicket` 与 `TrinityHostedActionPayloadHandler.route/claimRoutedAction` 的会话、代际、实际菜单和单次认领边界；沿用本项目核心菜单的服务器线程／查看者／关闭／重入守卫，并使用目标成员 revision 拒绝旧升级请求。D17a 尚未新增客户端 payload，完整协议与真实客户端联合验证留 D17c；本步服务器服务证据见[10.70](bee-processing-network-evidence-production.md#s10-70)。

<a id="s13-10"></a>
### 13.10 ENERGY 容量、缩容与交还（2026-10-01）

D17b1 从本地固定 Mekanism 10.7.19.85 源码复核 `MekanismUtils.getMaxEnergy(int,long)`、`MachineEnergyContainer.getBaseMaxEnergy/setMaxEnergy/updateMaxEnergy` 及 `TileComponentUpgrade.deserialize/removeUpgrade`：直接使用原生数量重载和本机已平衡的基础容量，对齐当前配置的 long 取整／饱和语义；不读取托管后已清空的升级组件，也不重乘平衡系数。原生 `setMaxEnergy` 会裁掉超出容量的电量，因此网络在发布前按成员本地余额拒绝危险缩容，不在物理组件试装／回退。既有 `MachineAssetStore` 在独立的预检机器中先反序列化升级、重算容量，再恢复 FE 并验证整个映像往返；实际交还沿用同一顺序，不增设第二份能源暂存。

本步确认 `.tmp_dataenergistics_src` 独立且干净后，从 `965b9d98` fast-forward 到 `ddef032e`（`1.21`）；上游变化为封包神秘学仪式回收，与本步升级无关。复读现版 `TrinityHostedActionTicket` 和 `TrinityHostedActionPayloadHandler.route/claimRoutedAction` 的失效／单次认领边界，继续采用 [13.9](#s13-9) 已有服务器菜单与成员 revision 保护，不引入外部恢复流程。

<a id="s13-11"></a>
### 13.11 PB 升级数量、互斥与能力失效（2026-10-01）

D17b2a 复核当前 1.0.10 的 `MekCentrifugePbUpgradeHandler.installPbUpgradeBulk/extractPbUpgradeByType/loadCounts/getLimit/refreshMultiplierCacheIfNeeded`、`BalanceConfig.canInstall/pbUpgradeLimit/refresh` 及 `PbUpgradeInventorySlot.getRepresentativeStack`：安装使用当前白名单、上限和等级互斥，数量变更递增版本使物理倍率缓存失效；旧存档数量保留，取回按真实槽位空间交付。网络沿用相同物品与配置规则，但权威数量只写入封存映像，能力候选由成员 revision 失效；不把升级临时装回托管机器，不使用物理输入槽 ticker，也不采用宽松加载时跳过未知类型的行为。严格数量读取改用服务器局部 EnumMap，不增加缓存或后台任务。

本步复读固定 Mekanism 10.7.19.85 `TileComponentUpgrade.addUpgrades/removeUpgrade/serialize/deserialize` 的预检接收量、成功后重算及输入／输出槽分离，继续采用 [13.9](#s13-9) 的实物交付与会话合同；未引入新的外部框架。实际 PB 依赖为 13.14.0，使用本地 JAR 的 `CentrifugeBlockEntity` API 和本项目当前独立机作对照，不将旧 13.13.5 反编译目录称为当前源码。验证覆盖八种升级的单件／满上限物理能力、上限降低及旧互斥组合取回、旧计划与新效果分离、标准组件守恒、实际保存及最新实物交还；具体结果见[10.72](bee-processing-network-evidence-production.md#s10-72)。

<a id="s13-12"></a>
### 13.12 蜂箱原生升级与周期边界（2026-10-01）

D17b2b1 复核当前 1.0.10 的 `ApiaryUpgradeMath.computeMekSpeedTimeMultiplier`、`ApiaryUpgradeHandler.getTimeMultiplier/invalidateUpgradeCache`、`TileEntityMekApiary.recalculateUpgrades`、`BeeProgressPlan`，继续使用固定 Mekanism 10.7.19.85 的升级单价与容量口径。封存读取仅支持基础蜂箱的 SPEED／ENERGY；PB 原生依赖仍为 13.14.0，静态铁蜂的基因、花朵、配方与气候准入保持原合同。已知改写原生公式的 Unleashed／Empowered、创造与扩展工厂不自动准入。新 `SealedApiaryProfile` 读取本机已经平衡的基础 FE，不重乘平衡系数；物理对照在独立蜂箱安装真实升级，核对每档耗时、单价及容量。

本步沿用 [13.9](#s13-9) 的实物交付和 [13.10](#s13-10) 的危险缩容拒绝。蜂箱与离心机的状态差异决定实现边界：蜂计划属于长期蜂记录，故在旧周期结清后由付款证明同时发布新耗时／单价与首次工作；通用所有权更新仍拒绝任意改计划。名册代际、蜂身份、喂食及产物不因此改变。交还前比较当前升级与部分周期的时间／单价，尚不一致时先拒绝，避免物理机丢失旧收费上下文。升级映像与旧计划本已持久化，恢复后可重新计算下一周期能力，无需新增存档字段、全局缓存或后台队列；原有部分周期在能力一致时仍可交还。正式调度每次一 tick，只有周期起点与冷交还预检读取封存能力；具体证据见[10.73](bee-processing-network-evidence-production.md#s10-73)。

<a id="s13-13"></a>
### 13.13 蜂箱 PB 时间数量与同源公式（2026-10-01）

D17b2b2a 复核本地 1.0.10、网络基线 `0b0523e` 的 `ApiaryPbUpgradeHandler.installPbUpgradeBulk/extractPbUpgradeByType/getPbUpgradeLimit/loadPbUpgradeCounts`、`ApiaryUpgradeHandler.computeTimeMultiplier/invalidateUpgradeCache` 及 `ApiaryUpgradeMath.getPbTimeDivisor`。实际 PB 依赖为 13.14.0，Mekanism 为 10.7.19.85。采用服务器线程、当前蜂箱安装上限、等级互斥、完整组件匹配与先预检接收空间的规则；旧数量由持久化恢复保留，不按当前安装上限裁剪。物理机数量变化失效本机倍率缓存；网络只变更唯一封存映像和成员 revision，下一周期读取当前数量，不调用物理输入槽 ticker 或建立第二份升级缓存。

从既有物理时间计算提取 `computePbTimeDivisor`，保留 TIME 单倍、TIME_2 双倍、非法 bonus 回退及大数量饱和语义；网络与独立蜂箱共用同一公式。蜂箱使用自己的 PB 数量键和安装配置，不能套用离心机白名单或上限。网络严格读取拒绝未知类型、错误 NBT 类型和非正数，不沿用物理旧格式加载中忽略未知项的宽松规则；封存之外的旧存档迁移仍由原有迁移器负责。沿用[13.12](#s13-12)的旧周期、首次新付款及交还边界；通过真实标准升级实物的逐档独立机对照、旧上限／互斥组合拆回、PB 单独周期切换及实际落盘验证，见[10.74](bee-processing-network-evidence-production.md#s10-74)。

<a id="s13-14"></a>
### 13.14 蜂箱生产力轮数与精确聚合（2026-10-01）

D17b2b2b1 读取当前网络基线 `cfa8979` 的 `BeeProduceBatchSampler.sampleRollCount/sampleAmounts`、`BatchProbabilitySampler.sampleBinomial`、`BeeProductionSampling.adjustStackCount` 和 `ApiaryUpgradeHandler.computeProductivityMultiplier`。另用 javap 检查实际 PB 13.14.0 JAR 的 `BeeHelper.getBeeProduce/lambda$getBeeProduce$0`、`AdvancedBeehiveBlockEntity` 生产回调及 `AdvancedBeehiveRecipe.getRecipeOutputs`：轮数为整数部分加小数 Bernoulli，每个原始栈再应用基因，配置蜜脾保留蜂种组件。ProductiveLib 0.2.0 的 `TagOutputRecipe` 仅提供输出目录，随机轮数不在该类中；没有将本地旧 13.13.5 源码目录称作当前依赖源码。

采用逐周期轮数与逐栈取整；验证 2.5 倍的边界、20,480 次固定种子单周期物理采样对照、独立 IEEE-754 整数模型及基因例子。不采用 PB 的世界随机源、可变输出列表或本项目物理采样器的 int 总轮数饱和；网络纯内核接收调用者给定的样本，保持幂等计算，用现有 ProductAmount 表示精确轮数／总量。普通路径保持 long，大数只按实际 float 的二进制值构造，不能用十进制显示值代替。

成功路径只返回私有不可变计算结果；非法倍率、样本和产物参数直接拒绝，不返回空产物掩盖错误。内核没有缓存、世界引用或线程任务，同一实例可只读复用；能力失效、周期所有权与重启不重抽仍由 b2／b3 的服务器事务负责，尚未接入。概率／多产物、Ω／BLOCK 转换不得使用固定单产物汇总捷径。合同与证据分别见[2.3](bee-processing-network-design.md#s2-3)和[10.76](bee-processing-network-evidence-production.md#s10-76)。

<a id="s13-15"></a>
### 13.15 付费随机状态、版本迁移与按键交付（2026-10-01）

本步参考副本 `.tmp_useless_src` 在上轮确认独立根与干净工作树后，从 `23215c7` fast-forward 到 `884e757b`（1.21／2.4.5.4）；本轮再次核对该 HEAD 和干净状态。读取 `AlloyFurnaceBigIntegerCpuAdapter`、`AlloyFurnaceBigIntegerCrafting.unitOutputs/scaledOutputs` 及 `AdvancedAlloyFurnaceAeManager.notifyCpuBatchCompleted/notifyCpuBatchCancelled/claimOutputsFromCpu`：采用服务器线程、入队后固定实际结果、按键精确数量交付及单一终局的职责划分。其“装配一次再乘整批”的折叠语义不能替代本项目逐周期的小数概率；其回调异常后返回零接收也不能用于结果未知的资产交接。既有网络内账本继续直接提交冻结数量，不引入 AE2 回调、外部缓存或机器引用。

随机流依据本地 Java 21 的 `SplittableRandom.nextDouble` 作独立数值对照，以显式 SplitMix64 计数公式固化采样版本 1，不依赖世界 RNG 或 JDK 对象的可变内部状态。种子／游标属于蜂记录；付款形成 pending，采样同时增加游标和冻结数量，结算仅转移冻结数量。旧请求须通过源状态身份校验，通用所有权更新不得改随机状态；移位、升级时间切换及正常恢复保持原流，取出后新装入的蜜蜂按新 beeId 建流。所有操作仍在权威服务器线程发布，没有新增后台计算。

两种 decoder 都验证 schema 6／7；流式读取先记录实际蜂格式，结束时与网络 schema 核对，因此不依赖字段顺序。只接受完整旧字段迁移，缺失或损坏的新字段不降级为旧格式。读取会话独享格式记录，取消／关闭不留下全局状态。证据见[10.77](bee-processing-network-evidence-production.md#s10-77)；正式生产力升级仍按[11.5](bee-processing-network-roadmap.md#s11-5)的 b3 交付。

<a id="s13-16"></a>
### 13.16 正式生产力升级、配置变化与随机交还边界（2026-10-01）

本步复核当前 1.0.10／PB 13.14.0 的 `ApiaryUpgradeHandler.computeProductivityMultiplier`、`PbUpgradeConfig.productivityMultiplier`、`ApiaryPbUpgradeHandler.installPbUpgradeBulk/extractPbUpgradeByType` 与封存升级交换。将物理原有的四档加权求和移入 `ApiaryUpgradeMath`，保留 double 累加顺序和有限 float 转换，网络仅准入 α／β／γ，Ω 自带蜜脾块而继续独立适配。能力在周期起点读取当前配置，原有部分周期不重新读倍率；实物交换只改当前封存数量，旧计划／随机状态直到已付费工作结清。

沿用[13.15](#s13-15)的按键交付原则，并补清交还差异：物理蜂箱没有网络游标字段，故小数倍率部分周期先结清再移交；匹配当前能力的整数倍率部分周期不需要携带随机状态，可以保留原进度。此规则使用正式交还预检；不在物理槽补写私有种子，也不通过换源重新采样。

`.tmp_useless_src` 经独立根和干净状态检查后，从 `884e757b` fast-forward 至 `1733aef5`（1.21／2.4.5.4）。新增 `AdapterUtils.reagentCandidates/isIngredientWithRetry` 把并发注册查询限制为 32 次重试，但仍可能返回缺项，也会在调用线程 sleep；本步不采用该恢复方式。网络生产继续使用已审查的静态配方及服务器线程，没有为升级引入后台世界查询或把失败当空产物；其它上游改动未扩展为本项目支持范围。

<a id="s13-17"></a>
### 13.17 Ω／BLOCK 映射、完整产物键与旧周期（2026-10-02）

复核本 worktree 实际 `libs/productivebees-1.21.1-13.14.0.jar` 的 `BeeHelper.getCombBlockFromHoneyComb` 字节码，以及当前 1.0.10 的 `CombBlockConverter`、`ApiaryUpgradeHandler.hasCombBlockUpgrade` 与升级交换路径。PB 可配置蜜脾生成配置蜜脾块并只复制 BEE_TYPE，原版及 ghostly／milky／powdery 蜜脾走固定映射；其它映射可返回空。独立蜂箱按 1:1 替换后恢复原数量，Ω 与 BLOCK 任一存在即启用，不叠加转换，也不除以 9。网络采用相同物品映射和完整返回组件，保留原配方键用于配方失效检查；不把旧 13.13.5 反编译目录作为新版依赖证据。

成功路径在服务器线程、下一周期付款前解析一个模板键，没有按产量生成物品列表或引入全局缓存。已付费计划固定实际键，配置、升级变化和正常恢复不会重新映射旧产物。独立转换器在已生成产物后捕获异常并保留原栈；网络尚未付款时让映射／组件编码错误向外传播，由既有调度故障路径暂停，不能默默换成另一种产物后收费。无匹配返回空与异常分开处理，前者保留原键。每蜂计划多保存一个完整源键，复杂度取决于单个模板组件字节，不取决于产物数量；无新的世界引用或停服清理责任。

沿用[13.15](#s13-15)的单一所有权与确定随机流。schema 8／采样格式 2 同时保存源键和实际键；schema 6／7 只能迁入各自完整字段，旧源键取原输出，schema 7 的种子／游标不重建。完整解码与预算解码均核对格式和源键字段。对应验证覆盖三类边界：真实核心菜单安装／取回及独立蜂箱对照、原生／配置蜜脾组件和数量、安装及拆除后旧付费文件的跨 JVM 恢复；见[10.79](bee-processing-network-evidence-production.md#s10-79)。其它蜂种、正式升级 GUI、双玩家及性能验收仍沿[11.5](bee-processing-network-roadmap.md#s11-5)推进。

<a id="s13-18"></a>
### 13.18 正式升级页与有界服务器选择（2026-10-02）

读取本地 `mekanism-10.7.19.85-sources` 的 `GuiUpgradeWindow`、`UpgradeUtils` 和 `TileComponentUpgrade`：物品映射使用标准升级类型，操作发往服务器，移除先模拟背包接收、按实际接收量减少安装数，窗口关闭停止跟踪。网络采用标准图标和服务器实际量提交原则；托管后物理升级已清空，展示和交换仍从网络权威 `AssetImage` 读取，不能拿物理组件作为第二份库存。

复用现有 `NetworkSelectionSession`、`TerminalClientState` 与 `MemberUpgradeService` 的菜单身份、所有者、距离、序号、限流及重入边界。每页最多八成员，每成员最多十项升级；客户端只发固定选项、服务端选择令牌、背包格和有限数量。选择持有不可变资产对象作为升级版本：普通生产保留对象，安装／移除和重载产生新对象，防止数量恢复原值后的旧选择重放。页面过期、关闭和动作后释放选择，不增加全网扫描或世界级缓存。

失败路径继续返回实际状态：冲突、超限、组件不匹配或满格不吞物品；已提交后的同步失败不回退并重复发放。客户端超时不重发资产请求，需刷新后再操作。升级选择不改变已付费工作、保存 schema 或升级公式；专项验证覆盖生产期间有效选择、升级变化与 ABA 拒绝、正式客户端点击与实物守恒，联合范围仍见[11.5](bee-processing-network-roadmap.md#s11-5)。

<a id="s13-19"></a>
### 13.19 本页升级批量与能力预估（2026-10-02）

复核同一 Mekanism 10.7.19.85 源码的 `TileComponentUpgrade.addUpgrades`／`removeUpgrade`：服务器按实际上限安装，移除先模拟有限升级输出槽，按接收量减少安装数并重算能力。对[13.18](#s13-18)的接收位置作准确补充：原生组件使用升级输出槽，本项目服务使用玩家选定的主背包槽；采用的是“实际量先确定，再提交”的原则。

本页批量复用同一 `MemberUpgradeService`，按已展示快照顺序处理最多八个成员；不预先复制一份背包给每台、不在部分失败后重放整批，也不把模拟变成预留。能力预估复用 `MemberUpgradeChange` 的私有候选及 `SealedApiaryProfile`／`SealedCentrifugeProfile`，不复制升级公式、不把升级暂装回物理机。客户端只展示服务器数值，超时、换页、关闭和旧资产版本沿用既有会话边界。每次预估只计算一个成员，批量最多八次有限事务；不查询全网配方或库存，不新增后台任务、世界缓存或全网通知。

<a id="s13-20"></a>
### 13.20 托管成员代理与菜单失效（2026-10-02）

沿用[13.18](#s13-18)的同一升级服务，复核本项目 `ManagedMemberEvents`、`MemberBinding.phase/write`、`ManagedProductionAccess.member` 和 `NetworkCoreBlockEntity.usableBy`：绑定更新每次产生新 NBT 对象，菜单同时固定该对象、源 BE、原核心、权威域与交接 claim；距离从核心移到源成员，其余所有权校验继续复用。核心卸载／替换、权限代际或绑定变化不能让旧菜单转向新目标。同时核对 `NetworkTopologyService.watch` 的 200 tick 审计：它与事件重建共用暂时撤下拓扑的状态，因此菜单存活与命令就绪分开。待验证时仍核对源实例、交接引用、权威域、权限与物理空状态，只保留界面；命令必须重新通过完整的 `ManagedProductionAccess`，审计确认断开后关闭旧菜单，不改变原生产调度的拓扑门控。按成员索引直接查询一条，关闭／失效释放选择；不建立第二份物理升级库存、全网游标或全局菜单缓存。

本地 NeoForge 21.1.216 的 `ICommonPacketListener.hasChannel` 会读取连接协商状态。服务器包捕获夹具仅声明 `AdvancedOpenScreenPayload`，其它扩展返回未协商；它验证服务器菜单与有限交换，真实菜单附加数据、注册屏幕和方块右键通过实际客户端另验。两者的证据范围独立，不能把夹具当作真实 TCP 登录。服务端取消托管方块与手持物品的原交互，客户端完成预测往返；独立机保留原入口。未新增外部依赖或更新参考仓库。

<a id="s13-21"></a>
### 13.21 升级授权与真实双玩家恢复（2026-10-02）

复核网络分支 `0627f29` 的 `CoreAccessState`、`CoreAccessCommands`、`NetworkCoreMenu`、`MemberUpgradeService` 和既有 `CompetitionServerProbe/CompetitionClientProbe`。沿用服务器线程内有限 UUID 表、所有者近距离命令、不可复活的菜单代际、提交前后复核和实际玩家背包交换。升级权限作为现有访客的显式子集，不把普通访问权扩大为升级或结构管理，也不引入另一份物品库存。表版本 1 迁入空升级子集；版本 2 严格校验子集和核心身份，损坏数据整体隔离并保留。

双客户端复用既有本地 TCP 登录、阶段屏障、旧正式请求重放、断线重连及原版玩家文件保存。测试专用升级屏障只传阶段和回执，授权由真实所有者客户端执行正式命令；正常升级由真实按钮发送现有请求。已有产品／喂食／蜂笼竞争继续保留，新增升级总量同时计入两名玩家与两成员封存数量，工作段、FE 和账本逐步单独校验。旧蜂周期与离心作业先通过真实服务付款一 tick，再修改升级并保存；新 JVM 先严格比较完整网络、权限表及玩家文件，随后用原计划完成和结算，旧 revision 重放不得再次支付或入账。没有外部参考版本更新、异步世界访问、全网查询或新运行依赖。

<a id="s13-22"></a>
### 13.22 一体机复合计算与有限交付（2026-10-02）

复用网络分支 `813bd98` 的 `BeeWorkExecutor`、`BeeRecord`、`CentrifugeJob`、`CentrifugeEnergyPricing` 及 `CentrifugeRecipePlan.validateFrozen`。单蜂候选沿用原收费／周期／随机内核，由原基础蜂箱入口包装回原有状态；一体机采用显式蜂位容量及同身份作业，避免借两个网络成员或三蜂位容器拼装复合机器。离心计划的必得产物下限要求保留完整冻结样本，因此部分交付单独记录 delivered，不能把剩余量冒充完整样本重建作业。原始冻结量是校验证据，只有 frozen - delivered 是当前待交付资产；完成后释放 lane。固定输入数、种子、旧计划、精确数量及每次原根校验沿用已有原则。此步只复核已有固定实现，无外部仓库更新或新世界 API；数据持久化、线程及角色端口资格在 M04b／c 接入。

M04b1 继续复用网络分支 `f658925` 的 `BeeRecordCodec`、`CentrifugeRecordCodec`、`ProductRecordCodec` 和 `StrictNbt`：抽出单蜂／单作业公开入口，保留原外层网络格式和旧版兼容；完整组件与精确大数共用已有编码，新增的单机容器独立限定容量与 schema。读取只构造私有候选，保留随机种子／游标、原冻结量／已交付量及真实槽序，不进行生产或重新采样。合法旧记录保持可读；未知产品键／数量字段明确拒绝。注册表与实际堆叠上限通过调用方校验，世界保管、身份绑定、缺档与卸载的实际接入见下述 M04b2。没有新增外部参考或依赖。

M04b2 复核网络分支 `331e30a` 的 `MachineWorldService`／`MachineDirectory` 生命周期、`NetworkTickService`／`FairServiceBudget` 公共预算，以及 `CheckpointFiles` 的“临时文件、force、原子发布”原则。独立机器采用单机有限 SavedData 文件，沿用服务器线程所有权和成功写入后确认，不借网络无限 checkpoint 或另开逐部件 ticker；没有复用网络百万键后台分页的规模结论。NeoForge 21.1.216 环境下实际调用原生 SavedData 注册、正常世界保存／停服和另一个 JVM 加载，读取失败返回空与文件缺失须在创建前区分；BE onLoad 晚于 ServerStarted 的时序通过现有重启夹具验证。主世界资产根与活动结构引用分离，旧结构凭据失效不清空资产。工作轮询复用现有 `MachinePartVisuals` 的 `getChunkNow`／已加载区块 BE 查询；直接调用 `Level.getBlockEntity` 会续 UNKNOWN 票据，已由真实完整卸载失败定位，不能只用 hasChunk 前置检查掩盖。实物入口和联网所有权迁移继续由 M04c／M05 独立适配。

M04c1 基于生产提交 `5e2ab4a`，复用 `RuntimeProductPolicies`／`PbProductPolicyCompilation` 的共享配方代际与逐项编译、`AllowedProductDescriptor` 的准入身份和 `ProductKeyCodec` 的完整组件投影；新增只读 peek，端口不调用同步目录编译。复用 `MachineWorkService.Access` 的原根与结构凭据校验，NeoForge 21.1.216 的 ItemHandler／FluidHandler／EnergyStorage 和 BlockCapabilityCache 已由真实专服入口核对。物品按指定槽提交，流体按原有限罐策略提交；不提供 IItemHandlerModifiable 或资产覆盖入口。形成后的部件发布才失效能力缓存，避免在结构事务尚未提交时缓存 null；旧对象始终绑定旧结构。首轮夹具错误假定普通铁锭必定准入，改为使用 PB 13.14.0 铁蜂实际配方产物，没有扩大目录资格。

M04c2a 基于 `9c4416d` 复核 `FeedingItem`／`FeedingSlotStore.Slot`、`FeedingRecordCodec`、`StaticFeedingAdapter` 的完整组件、真实物品上限和禁用／分组规则；只提取单槽 codec，基础蜂箱的三槽容量、legacySlots=9 和 sourceFingerprint 保持。单机六槽直接挂入 `CombinedMachineWork`，不借基础蜂箱的迁移来源字段。`BeeRosterChange` 的原根绑定、版本派生新 beeId、付费积压先结清与未完成周期不退款原则用于单机候选；真正的 PB 蜂笼和玩家背包交换仍需后续适配。复用 `MachineRestartProbe` 在已有满载／旧工作恢复场景中加入真实铁块喂食，验证六槽内容随正常保存和另一 JVM 恢复，避免为同一保存入口新建专用探针。无新外部依赖或源码版本推测。

M04c2b 基于 `b1c5b1d` 复用 `VerifiedCageProjection`、`CoreBeeCageExchange`、`CoreFeedingExchange`、`CoreInventorySync` 与 `TerminalSequence/TerminalRateBudget` 的完整投影、双方候选、提交后绝对同步、拒绝也消费序号和每玩家共享限流；不移植网络成员／全域 checkpoint 身份。真实依赖仍为 PB 13.14.0，本步未把历史投影类注释中的 13.13.5 视为新的 API 核验。`StaticApiaryAdapter`／`StaticCentrifugeAdapter` 抽出不可变能力参数，基础蜂箱三格准入及封存校验保持；一体机直接使用相同静态配方、`MekCentrifugeEnergyScaling.balancedBaseEnergyPerTick` 和固定 Mekanism 10.7.19.85 的 `TileEntityElectricMachine.BASE_TICKS_REQUIRED`（200）。每台计划缓存随控制器卸载清理，无隐藏机器或虚构网络成员。复用 MachineLifecycleProbe、PlayerInventorySyncProbe 和 MachineVisualClientProbe 的专服／真实客户端入口，客户端仅新增聚焦菜单模式，不重跑旧动画矩阵；没有新运行依赖或外部仓库更新。

M04c3a 基于 `f95c117` 复核 `MemberUpgradeService` 的标准完整组件、有限接收空间、双方候选和提交后同步；单机复用 `MachineExchange`，不引入网络成员封存或另一份升级库存。公式来源为当前仓库 `SealedApiaryProfile`／`SealedCentrifugeProfile` 与固定依赖 Mekanism 10.7.19.85；公开已有蜂箱纯时间公式供同机调用，沿用 maxUpgradeMultiplier、费用向上取整及离心并行上限。四个目标槽独立，不采用原生单方块 ENERGY 扩容量规则，因为一体机 capacityVersion=1 已固定有限缓冲；无载体／新容量迁移就不能缩改 FE。已知改写原生公式的集成继续拒绝插件安装。复用既有生命周期、重启和聚焦菜单夹具，补实物安装／取回、单机隔离、旧工作价格与四槽恢复；没有新外部仓库或新运行依赖。

M04c3b 基于 `2f849b7`，复核 `PbApiaryUpgradeCounts`／`PbCentrifugeUpgradeCounts` 的现有白名单，读取 `ApiaryPbUpgradeHandler.getPbUpgradeLimit`／`MekCentrifugePbUpgradeHandler.getLimit` 的成功安装、超限拒绝和取回路径，公开配置查询委托并保留原实例方法签名。两路分别使用 `ApiaryUpgradeMath` 与 `CentrifugePbMultipliers` 已有公式；PB 13.14.0 的标准实物由现有 `PbUpgradeInventorySlot` 投影，BLOCK／Ω 沿用 `StaticApiaryAdapter.output` 的单次映射，副产物过滤沿用 `StaticCentrifugeAdapter.compile`。没有扩大蜂种或升级效果准入。接口资格借鉴 `MachinePorts.Endpoint` 的结构绑定、实际 BE 身份和 getChunkNow 查询，菜单增加入口距离而不另建资产域。正常恢复、失效和失败保管复用当前单机 codec／保存服务，验证继续扩展已有生命周期、重启与聚焦菜单夹具；无新外部仓库或依赖。

M04d 基于 `001d40e`，复核 `ApiaryUpgradeProbe`／`ApiaryProductivityProbe`／`MemberUpgradeProbe` 的实物参考方法：PB 13.14.0、Mekanism 10.7.19.85 的独立蜂箱／离心机安装相同升级，读取时间、FE、生产力、并行和稳定性，并直接核对原配方的物品／流体数量与概率。参考机保持红石停机、无蜜蜂与输入；一体机由正式 `NetworkTickService` 调度，不调用私有生产候选充当期望值。独立账目以真实周期／作业身份核算输入、付款、冻结余量与端口交付，蜂产量用 `SplittableRandom` 顺序样本及独立取整对照；离心随机样本复用已验收内核，仅证明调度与结算符合固定计划，不声称物理逐件 RNG 等价。复用 `MachineProbeFixture`、正常世界复制／停服包装和完整根比较；新增联合模式与旧损坏身份模式相互隔离，reader 校验 writer 模式及依赖一致。没有更新外部源码副本或新增运行依赖。

<a id="s13-23"></a>
### 13.23 D18a 终端入口与本轮文档复核（2026-10-03）

基于网络 worktree 的 `1880cd2` 与未提交 D18a 草稿，复用已验收的 `NetworkCoreMenu`、`MemberUpgradeMenuAccess`、`NetworkSelectionSession`、`OwnedMachines` 和核心访问表。入口按源方块距离校验、固定 BE／权威域／菜单代际；失效先撤销，关闭不保留查询根。类型索引从占用记录派生，读档 Builder 重建，不把分页改成全表过滤。终端没有库存或独立生产，复用已有实际量交换、旧周期和背包同步。既有 QIO／ECO 订阅参考只供 D18b 使用，本步未实现订阅或大数详情。

本轮没有拉取上游或增加第三方实现；固定 API 以 NeoForge 21.1.216／MC 1.21.1 本地编译和真实运行验证。文档复核修正 D18／D19／D20 依赖环、过期 M04 关键路径、D13b 无升级限制、ECO 历史 WAL 与现行快照混写、已有存档兼容和 GUI 授权描述。参考更新改为按需要进行，复用已读版本不反复联网；目标／已实现范围和验证证据继续分开。

<a id="s13-24"></a>
### 13.24 后续科技线与完整终端的本地参考（2026-10-03）

本轮仅核对本地已有源码，不拉取／更新参考库，不复制其代码。ECO、Useless、AE2WT 工作树检查为干净；以下是本轮 HEAD，13.2 的历史读取记录不改写。采用原则对应[2.6](bee-processing-network-design.md#s2-6)、[9](bee-processing-network-design.md#s9)和[11.14](bee-processing-network-roadmap.md#s11-14)，科技线仍等待 T00。

| 本地来源与核实版本 | 实际读取入口 | 采用与不采用 |
| --- | --- | --- |
| `../../闪电全版本/ae2lt-src-2.1.0-beta.5`，2.1.0-beta.5／`1d4589b6bd50672051f78d766505530beacfebc0` | `logic/tianshu/CpuInternalCoreCalculator.calculate` → `ComputingUnitTotals`／统一能力描述调用，非法核心和单元数量拒绝 | 核心等级与存储／并行／放大单元分责，形成后给单份能力；不照搬 26 单元或 16,384 并行常量，也不把源码引用当本项目自动搭建实现证据 |
| `.tmp_neoccoaeextension_src`，21.2.1／`1723bf0665055ae9e601497ef9a9b38da4d3264f` | `data/recipe/CoolingRecipes`；`ECOCraftingCoolingController.getCoolingRecipe/canRefillWith/ensureCoolantAvailable/refillCoolant` | 流体→冷量、最高超频档、同组件液体与热态出口限制；查询可能触发真实 refill，分次整数换算也不能直接套入本项目，改为纯预览、精确余数和单次资产提交 |
| `.tmp_useless_src`，1.21.1-2.4.5.4／`93486296e0e7eb38620aa660cb7ea66b03f92272` | `MultiblockAlloyFurnaceCoreBlockEntity` 的形成、tick、批次入口／单任务并行；`OmniversalCoilStats.singleTaskParallel/threads/processTime/resolveEffect` | 分开线程、单任务并行、周期和计费；最高有用线圈为 1 tick、long 最大视图及整批固定价特例，普通线圈受配置影响。只确立 T07 对照变量，未实测吞吐，不宣称复制这些数字就与万象炉等效 |
| `../decompiled-reference/productive-bees-addon-1.21.1/mekanism-10.7.19.85-sources`，固定本地版本 | `FactoryTier`；`ItemTierInstaller.useOn` 的权限／等级检查、升级数据、放置失败、配置恢复和能力失效 | 3／5／7／9 是工厂进程数；采用逐级解锁、升级前检查与保留机器数据，不照搬方块替换后的外部失败处理。新机容量与多方块蜂位另算，有限账户不允许丢失 |
| `../decompiled-reference/productive-bees-addon-1.21.1/ae2-19.2.17-decompiled`，固定 19.2.17 | `WirelessCraftingTerminalMenuHost.createCraftingInv`、既有 `MEStorageScreen`／终端同步参考 | 真实九格合成材料、宿主生命周期、产物网格与背包分区；本项目不直接把权威大数账户放进物品组件，不采用关闭掉落兜底 |
| `build/reference/ae2wt-1.21.1`，1.21.1 源码／`49f70f0627b40762cfbd33732834c7271d5ec17d`，声明 AE2 19.2.17／NeoForge 21.1.219 | `WUTHandler.setCurrentTerminal/hasTerminal/open/findTerminal`；`wut/recipe/Common.mergeTerminal`；`WTMenuHost.consumeIdlePower/recharge` | 借鉴已安装功能、当前功能和实际物品定位。合并有升级／能量／组件处理，不能简单丢掉另一输入；其 SIMULATE 分支可能 recharge 并 MODULATE，明确不采用。仅源码参考，未安装为项目依赖，未声称本项目已支持 Curios／量子跨维度 |

完整终端先交付 D18d 的无状态有线合并与模式切换，e／f 才接真实合成资产和无线宿主。关闭释放选择、来源 BE／网络身份与权限失效沿用 D18a／D17；服务器线程负责世界和权威资产，不把参考库的后台合成能力推导成本项目可以后台访问世界。新材料的加工配方和附属等级尚未进入编码，需在 T03–T06 核对对应发行源码／JAR 和成功、拒绝、恢复路径。

<a id="s13-25"></a>
### 13.25 独立终端界面、真实合成与功能切换调用链（2026-10-03）

版本与本地根沿[13.24](#s13-24)，本步没有更新参考副本。用户提供的 AE2／QIO／通用无线终端截图用于分区与交互参考，界面和方块纹理由本项目独立生成，不提取截图或上游 PNG。

- AE2 `CraftingTermMenu` 构造九个真实 `CraftingMatrixSlot` 和单个 `CraftingTermSlot`；`updateCurrentRecipeAndOutput` 只在输入变化时重新匹配并生成预览，`hasIngredient` 对已保留材料分别计数。采用真实材料／预览结果分离和变更失效，后续 e2 仍需本项目自己的无掉落提交。
- `CraftingTermSlot.craftItem` 重新取得配方，`extractItemsByRecipe` 校验替代输入仍匹配同一结果，`getRemainingItems` 处理容器余料；`postCraft` 的回存余量会调用 `Platform.spawnDrops`，明确不采用。其全量 `getAvailableStacks` 和模糊枚举也不能直接用于任意大产物账本。
- `PatternAccessTermScreen.postFullUpdate/refreshList` 按供应者分组、筛选并排序，`GuiQIOItemViewer` 使用搜索框、可滚动槽网格及高度布局。采用机器分组／图标／常驻背包，不照搬客户端持有全部机器和库存的刷新成本；e1a 只保留一页，扫描上限、完整索引与订阅分别验收。
- AE2WT `WUTHandler.setCurrentTerminal` 先验证已安装功能，写 `CURRENT_TERMINAL` 并发布当前功能；`cycle/nextTerminal` 在已安装功能间前后切换，`open` 由实际物品定位器和当前功能选择菜单。采用服务器校验功能集合、实际设备定位、切换菜单身份；未来 f 对空集合／未知功能显式拒绝，不能无界轮询寻找不存在的功能。D18d 的有线切换已经用同一来源重新签发会话。
- 本项目的 `NetworkSelectionSession` 保留旧八成员分页，独立产物页扩到 36 格；搜索读取冻结索引且每请求最多检查 128 条，旧页／名册／权限拒绝继续沿用。位置由服务端独立字段下发；采蜜基础图标通过只读字符串字段取得，避免为一个 ID 复制完整 NBT。
- 定位绘制按本项目 NeoForge 21.1.216 的实际 JAR 核对。AE2 参考中的 `LevelRenderer.renderShape` 在本项目未开放访问，不能依赖参考库的 AT；最终使用公开 `renderLineBox`。定位不绑定实体或加载区块，只有一个有过期时间的客户端位置值。

素材生成源位于忽略的 `build/generate-terminal-art.py`；交付为原创终端框架、四态按钮、八个功能图标及三种终端方块纹理。后续完整合成、无线、独立蜂启停与离心能力汇总仍按路线逐项实现，不以本步布局完成推导这些功能已上线。

<a id="s13-26"></a>
### 13.26 AE2 操作、无线选择器与全网蜂务复用边界（2026-10-04）

AE2 固定 19.2.17 的本地路径沿 [13.24](#s13-24)。本次复读 `MEStorageScreen.mouseClicked/setSearchText/storeState`、`Repo.handleUpdate/updateView`：采用输入响应、右键清空、可见范围滚动及更新时保持身份。AE2 的全量客户端 Repo 不能直接用于本项目任意大账本；全局检索／排序和订阅公平继续按 D18b 逐步验收。原版 1.21.1 `AbstractContainerScreen.removed` 会在打开 JEI 时调用菜单 removed；`LocalPlayer.clientSideCloseContainer` 则先替换真实 containerMenu，客户端据此区分子屏幕与关闭，服务器撤销路径保持。

用户指定 [AE2WirelessTerminalLibrary](https://github.com/Mari023/AE2WirelessTerminalLibrary) 的本地 `build/reference/ae2wt-1.21.1` 仍为干净的 1.21.1／`49f70f0627b40762cfbd33732834c7271d5ec17d`，未拉取或安装依赖。本次读取 `WUTHandler.setCurrentTerminal/hasTerminal/cycle/nextTerminal/open/findTerminal`、`TerminalSelectionButton.installedTerminals/renderMenu`、`WTMenuHost.consumeIdlePower/recharge`。采用只展示已安装功能、当前功能高亮、正反轮换和真实设备定位；空功能／未知功能须有界拒绝。SIMULATE 分支 recharge 会执行 MODULATE，继续明确不采用。

本项目当前网络 worktree 的 `ApiaryJeiGuiHandler` 以 PB `BeeIngredientFactory` 向 JEI 注册蜜蜂命中区域；新终端复用该原生原料类型，按本地 JEI 19.36.0.360 API 编译、19.39.0.368 运行，常驻 Screen 不引用 JEI。五项基因读取 PB 13.14.0 已有 `entity_data/neoforge:attachments/productivebees:attributes_handler` 的固定字符串字段，与 `BeeTooltipRenderer` 的翻译键一致；不为每行复制实体附件或伪造缺失属性。

复读当前 `ApiaryQuickInsertHandler`、`GeneTreatAutoFeeder`、`ApiaryGeneTreatRestocker`：沿用权限检查、拦截 useOn、完整组件模板、无提升不消费、TYPE 拒绝、概率和已提取暂存／未知提取隔离原则。其物理槽写入、每实例喂食节流和逐机 AE2 节点不能直接进入托管模式；D18g／h 必须通过网络唯一所有者、旧周期和全服预算重新接线。已有独立机代码不作为新网络输入／自动小食的运行验收证据。

e1b1 继续采用同一 AE2 19.2.17，复核 `Repo.handleUpdate` 的首次完整条目、后续数量替换和删除，以及 `updateView` 的稳定身份／暂停时保持槽位；`MEStorageScreen.setSearchText/storeState` 负责输入后更新及重建时保存搜索。本项目改为服务端仅发送当前有界页和续租，首帧完整页、后续帧带查询及命令确认序号；不照搬 AE2 客户端全量 Repo。读档复用本项目 `SnapshotRecords`／`PagedProductAmounts` 不可变根，订阅接入 `FairDueQueue`／`NetworkTickService` 已有全服公平框架，取消和停服释放队列，失败只暂停该订阅。AE2WT 参考仍为上述干净提交，本步未更新副本或添加依赖。

g1a 复核本 worktree 的 `SealedApiaryProfile`、`StaticApiaryAdapter.upgradeCapacity/caged`、`ApiaryUpgradeMath` 和 `PbUpgradeConfig`：排名复用封存升级和运行配置的同源公式，不读取托管期间为空的物理升级组件。`ApiaryCageHandler.buildBeeDataFromSpawnEgg` 的 PB 13.14.0 默认属性／captureEntity 路径抽到 `BeeSpawnEggHelper.contents`，独立机与网络共用；保持仅解析注册蛋和类型组件的既有语义，不把任意蛋 NBT 写入蜜蜂。正式入蜂仍走 `CoreBeeCageExchange`／`BeeRosterChange` 的准备、双侧复核和无回调提交，按 `ManagedProductionAccess` 实时拒绝失效目标。未移植独立机物理槽写入或每实例预算；托管实物快捷入口与基因小食保留各自后续门。

g1b 继续采用上述网络 worktree 的独立机交互原则，核对 `ManagedMemberEvents` 的 HIGHEST 隔离拦截先于 `ApiaryQuickInsertHandler` 的 HIGH 处理，世界入口因此接在隔离事件最前。原版 Minecraft 1.21.1 的主副手 `useItemOn`／携带槽与潜行包负责传递交互，`Inventory.getItem/setItem` 的副手索引为 40；不构造临时菜单或新增资产 payload。NeoForge 21.1.216 本地源码中的 `PlayerContainerEvent.Open` 和登出事件撤销 pending，共享 `CoreBeeInputSearch` 与 `CoreBeeCageExchange` 保留有界扫描、准备后复核和无回调提交。没有把原版 RightClickBlock 写成拥有终端自定义序号，也没有移植独立机物理槽或每实例预算。

2026-10-05 e1b2 复核本 worktree 的 `PagedProductAmounts`、`ProductAmountTrie`、`SnapshotRecords`、`TerminalLiveQuery` 和订阅生命周期：数量使用 `ProductAmount.compareTo` 比较，独立测试以 `exact` 导出 BigInteger 对照，冻结余额差量跳过共享子树，排序只在完整一轮完成后发布；没有把数量排序塞进生产写路径。沿用本节 AE2／QIO 的身份保持与按需订阅原则，本轮未更新或重新引入第三方参考代码。直接读取 PB 13.14.0 JAR 中 en_us／zh_cn 的 `entity.productivebees.iron_bee`，确认为 Iron Bee／铁蜜蜂；五项基因继续使用已核对的 PB 附件字段与本模组翻译键。服务器搜索字典通过 `ModuleClassLoader.getResource` 跨命名模块读取，停服清理，不依赖客户端 Language。实际启动确认原版 JAR 仅带 en_us，zh_cn 位于客户端资产；因此原版名称补充由客户端按当前语言匹配固定注册表并编码位图，服务器按已同步原版 ID 排序解释，不复制分发 Mojang 语言文件，不信任客户端提供资产身份。

2026-10-05 e1c 沿用本 worktree 的 `BeeWorkExecutor` 已付费积压优先路径、`BeeAssetProjection.attach` 交还及 `VerifiedCageProjection` 的 PB 13.14.0 完整 entity_data 往返。网络启停位保存在 BeeRecord 外层，不写入 PB 实体／蜂笼数据；因此同一 beeId 移位和保存保留，结束网络托管后不把控制位带入独立机。复核 `BeeMemberState.rosterVersion`、`NetworkSelectionSession.sameRoster` 与服务器菜单授权，控制变更重新签发名册戳，普通生产保留名册戳。网络 schema 9 单独增加必填 enabled，独立一体机继续使用原单蜂编码；两种 decoder 对新字段严格校验，旧 6–8 格式显式默认启用。本轮未读取或复制新的第三方实现。

2026-10-05 D19a 复核本 worktree 的 `NetworkTerminalAccess.valid/switchMode`、`NetworkCoreMenu.terminalRequest/terminalSearch/removed`、`CoreTerminalCommands` 及 `TerminalSubscriptionService`：来源实例、权限代际、会话、服务端行选择与资产提交检查沿现有正式服务；当前页数量索引在取消／最后订阅关闭时释放。验证复用[13.21](#s13-21)的 `CompetitionServerProbe`／`CompetitionClientProbe` 双 TCP 玩家屏障及原版正常保存／登录链路，新增终端阶段在开发源集中运行，独立核算两人背包、网络余额及蜂笼内完整蜂数据。客户端只在屏障后发送已冻结的正式请求，开发回执不携带资产键、不授予权限；生产协议 16 和 schema 9 不变。本步没有更新参考副本或添加第三方依赖。

<a id="s13-27"></a>
### 13.27 有限合成账户、原生制作回调与恢复（2026-10-05）

读取本地 `build/moddev/artifacts/neoforge-21.1.216-sources.jar` 中 Minecraft 1.21.1 的 `CraftingMenu.slotChangedCraftingGrid`、`CraftingInput.ofPositioned`、`ResultSlot.onTake/checkTakeAchievements`、`RecipeCraftingHolder`、`RecipeManager` 和 `SavedData.save`。采用裁剪输入后的原网格偏移、当前配方／功能开关与受限合成检查、容器余料、制作回调及配方奖励；不采用 `ResultSlot` 在背包满时的掉落兜底，也不调用 `CraftingMenu.removed` 的清空材料路径。地图制作回调会改变新结果的组件和地图身份，因此先记录一次已付费结果，再调用原生／NeoForge 回调并交付，不能只通知一份与实际输出无关的副本。

持久化复核本 worktree `MachineWorkService.attach` 与 `MachineAssets`：原生 SavedData 读取失败也可能返回 null，不能据此新建空资产；NeoForge 原生保存会排后台 IO 并清 dirty，因此有限账户采用同源的服务器线程捕获、独立临时文件、force 和原子替换，成功才确认，失败保留状态。合成文件按维度／位置直接定位，BE 只记曾引用标志，菜单保留有界展示副本；无全服账户枚举、额外可选依赖或后台世界访问。完整组件与账户所有者在加载／交付时核对，损坏数据保留原始 NBT 并隔离，非空账户不能改所属所有者。

<a id="s13-28"></a>
### 13.28 片状终端与无线双宿主（2026-10-05）

本地 AE2 19.2.17 的 `AbstractTerminalPart`／`AbstractDisplayPart` 及 AE2WT `WTMenuHost` 路径与版本沿[13.24](#s13-24)：采用薄片显示面板、真实设备定位与功能分工；外形使用本项目三张终端纹理，独立六面方块模型和同尺寸碰撞框，不采用 AE2 宿主框架或掉落材料路径。无线像素图由本项目终端正面缩放并新增手持机壳／天线生成，生成源在忽略的 `build/generate-wireless-art.py`；未复制外部 PNG。再次核对 `consumeIdlePower(SIMULATE)` 会执行 recharge 的上游路径，继续不采用，其模拟不能作为本项目收费参考。

复核当前 worktree 的 `MachineControllerEntity`、`MachinePartEntity`、`MachineMenu`、`MachineWorkService` 和 `MachineDirectory.Binding`：内部 CORE 解析已形成控制器，远程菜单保留原绑定实例与机器 generation，操作仍经 MachineExchange 的权威候选与守恒提交。只扩大合法访问来源，不创建第二机器、网络或物理库存，不提前实施 M05 联网。设备材料沿[13.27](#s13-27)同一 SavedData 保存路径新增 device UUID 的 schema 2，位置账户继续 schema 1；网络 checkpoint 仍为 9。NeoForge 21.1.216 编译与实际物品能力注册使用 `Capabilities.EnergyStorage.ITEM`／`IEnergyStorage`，充能的 simulate 分支不创建绑定或改动数据。

<a id="s13-29"></a>
### 13.29 JEI 正式转移 API 与统一工作终端补充（2026-10-05）

本步直接通过本地 `jei-1.21.1-neoforge-19.36.0.360.jar` 的 javap 核对 `IRecipeTransferHandler`、`IRecipeTransferRegistration`、`IRecipeTransferHandlerHelper`、`IRecipeTransferManager`、`IRecipesGui.getParentScreen`、`IRecipeLayoutDrawable.getRecipeSlotsView` 和 `RecipeTypes.CRAFTING`，运行仍使用 JEI 19.39.0.368。2026-10-06 补核运行版 `RecipeTransferManager.getHandler`：索引键是菜单 Java 类与 RecipeType，同类不同 MenuType 不能重复注册。三种终端共用一个 NetworkCoreMenu 处理器，`getMenuType` 返回空可选值，入口再检查专用终端；通过公开父屏幕 API 回到合成页，旧成员快照不渲染为产物行；只发送本模组的有界配方意图，不采用 JEI 默认服务器槽搬运。主菜单、配方解析和物料事务不引用 JEI 类型，服务器无需 JEI。

Minecraft 1.21.1 的本地 NeoForge 21.1.216 JAR 核对 `ShapedRecipe`／`ShapelessRecipe` 的宽度、配方原料及标准 CraftingInput 匹配。容量匹配与物品副本沿本项目 TerminalCraftingPlan，不复制 AE2 的全量库存扫描。2026-10-06 f2b 复核本 worktree 的 `WirelessDeviceSession`、`WirelessMachineAccess`、`MachineMenu` 与 `CoreInventorySync`：以设备原 UUID／owner 直接定位 schema 2 账户，结构代际、真实手持栈、范围及 FE 的原检查继续限定入口。将原 `CoreCraftingMenu` 的账户访问和实际菜单抽为三个宿主方法后更名为 `TerminalCraftingMenu`，网络／独立机共用同一制作、余料和未知结果保管实现；本机操作与合成命令各自消费序号，但共用菜单重入锁和全服预算。JEI 处理器按两种菜单 Java 类注册，共用转移逻辑，服务器仍不引用 JEI。本地 `neoforge-21.1.216-sources.jar` 的 `AbstractContainerScreen.removed` 第 639–642 行会在切换 JEI 屏幕时调用客户端菜单 removed，因此只在服务端真正关闭时撤销会话，客户端返回原菜单时保留序号／展示；不把换屏当作服务端关闭。没有新增存档格式、资产来源或第三方依赖。

f2a 核对同一 Minecraft 1.21.1 源码中的 `DataComponentPatch.forget`、`ItemStack.applyComponents`，以及当前 `WirelessTerminalItem`、`TerminalCraftingAccount.wireless` 和 `CoreInventorySync`。采用只更新所保留设备的能量／token、其它组件原样复制，模组数据与宿主身份分别核对的方式；两套不同非空账户不自动拼网格。材料账户不发布新版本、不跨文件转存或删除源文件，旧空账户保持可恢复。原版主副手物品列表在服务器线程连续提交，之后同步已提交内容；模拟不创建绑定 token、不扣 FE，不新增合并配方、外部能力调用或后台任务。

用户新图仅作为上部物品网格、合成区、常驻背包和侧签交互参照；完整 AE2 操作、下单／任务和蜂务／升级／机器信息的目标已同步主合同，尚无实际 ME 网格的能力不能由图示或 AE2 已安装状态推断为已实现。

2026-10-06 e3b 以网络 worktree 的 `28b8318` 为基线复核 `TerminalRecipeFillPlan/TerminalCraftingPlan/TerminalCraftingAccount`、`CoreProductWithdrawal`、`LedgerCheckpoint/NetworkCheckpoint`、`ProductLedger/LedgerTransaction/ReservationBook/TransferStaging` 与 `ProductKeyCodec`。采用既有冻结账本候选、可用量扣除预约、完整组件往返和同服务器调用内有限接收的提交方式；九格与背包先规划，账户下一状态在账本扣款前构建，全部缺额只发布一次。外部接收结果未知的 TransferStaging 路径保留给真实外部接口，本步没有外部容器回调，不为内部账户再建转移队列。已有 `PagedProductAmounts` 按 ID／种类／组件排序的索引支持 O(log N) 定位，补充单调前缀下界查询以跳过无关库存；无新增生产索引、全表扫描或跨请求缓存。候选访问有明确预算，最小费用容量匹配按实际本地份额计算缺额，独立穷举对照不复用匹配实现。仍沿本节固定 Minecraft／NeoForge／JEI API，不更新第三方副本，不引入 AE2 材料或新依赖。

<a id="s13-30"></a>
### 13.30 D20a 真实桥接节点与可选依赖边界（2026-10-06）

继续核对固定 AE2 19.2.17 反编译源码 `../decompiled-reference/productive-bees-addon-1.21.1/ae2-19.2.17-decompiled` 中的 `IManagedGridNode`、`IGridNodeListener`、`IInWorldGridNodeHost`、`GridHelper.getNodeHost/getExposedNode`、`InWorldGridNode.findInWorldConnections`、`IPlayerRegistry` 和 `Platform.hasPermissions`。采用 NeoForge 世界节点能力、REQUIRE_CHANNEL、节点元数据往返及服务器侧玩家 ID 映射；实际访问以节点活动状态和本项目宿主／所有者／菜单边界共同决定。该版 IGrid 不再提供旧安全终端服务，Platform 的位置访问采用同维度与 Level.mayInteract；不照搬旧版 SecurityPermissions。首步仅所有者访问，访客与自动化授权随后续入口独立设计。

复核本项目 `Ae2GridNodeManager.prepareNode/connectNode` 的延迟连接原因：NBT／clearRemoved 期间不能连接并递归查询邻居。桥在首个有效服务器 tick 创建；公共 Link 与菜单无 AE2 类型，ModList 守卫后才进入兼容 holder 注册能力及构造节点，查询不创建节点。每次访问重新检查最多六个相邻宿主和六个桥、实际实例／代际与已加载区块，不使用全世界桥表或后台世界访问。旧能力对象在宿主失效后返回空节点，正式卸载销毁节点；桥异常只隔离并保留原数据。没有复用独立机逐槽存储包装或新增 IStorageProvider，双向账本仍属 D20b。

桥接材质由本项目忽略目录 `build/generate-me-bridge-art.py` 用 Pillow 生成，方块及物品共用原创像素图，不复制第三方资产。菜单使用同一个 ME 指示组件与有界枚举，没有全网内容同步。

2026-10-07 D20b1 复读本 worktree 的固定 `.tmp_ae2_19_2_17/appeng/me/storage/NetworkStorage.java` 和 `appeng/me/service/StorageService.java`，并以 `libs/appliedenergistics2-1.21.1-19.2.17.jar` 的 javap 核对字段及调用点。NetworkStorage 用 mountsInUse 拒绝重入、每提供者直接累加 KeyCounter；StorageService 按需重建缓存并通知 watcher，增删 global provider 本身不保证同 tick 缓存刷新。采用稳定挂载对象、明确请求重挂载和显式缓存失效；不更改实际存取循环，不把外部显示饱和写回内部 BigInteger 账本。全聚合修复通过 WrapOperation 为每提供者隔离贡献后安全相加，保持第三方包装链；字节码结构不匹配时不启用存储挂载，负贡献锁定兼容故障。AE2 API 与世界操作仍在服务器线程；首次键适配按完整组件往返验证，数量读取沿本项目冻结账本及预约扣除。运行结果与未覆盖的自动合成边界见[10.116](bee-processing-network-evidence-current.md#s10-116)，没有更新参考副本或新增运行依赖。

<a id="s13-31"></a>
### 13.31 ME 合成计划／任务与 AE2 19.2.18 兼容（2026-10-06）

继续读取固定 19.2.17 的 `ICraftingService`、`ICraftingPlan`、`ICraftingCPU`、`ICraftingLink`、`CraftingService.submitJob/getCpus`、`CraftingCPUCluster`、`CraftingCpuLogic.cancel/storeItems/getLastLink` 与 `NetworkCraftingProviders.getCraftables`。采用官方 beginCraftingCalculation 返回的 Future 与原生 submitJob，所有本模组访问和提交仍在服务器线程。原生 catalogue API 会复制集合，首次快照／筛选并非严格恒定耗时；按网格共享 40 tick 快照并限制全服昂贵查询频率，旧菜单最多保留一个列表。计划最多四份等待且限时，关闭和失效撤销未提交 Future；资产不进入本模组库存。

ICraftingCPU 公共接口不提供任务 UUID；对已核实的原生 CraftingCPUCluster，从 craftingLogic.getLastLink 取得真实 link／UUID，提交取消前同时核对原 CPU、link、UUID 及当前网格成员资格。其它 CPU 实现只读，不能仅凭同名产物或进度近似判断旧任务。采用 link.cancel 标记后由 AE2 本身退出作业；原生 storeItems 按实际存入量扣除 CPU 保管库存，剩余继续留存，不复制任何世界掉落路径。

按用户补充核对[19.2.17 → 19.2.18 完整差异](https://github.com/AppliedEnergistics/Applied-Energistics-2/compare/neoforge/v19.2.17...neoforge/v19.2.18)：`CPUSelectionList.formatStorage(CraftingStatusMenu.CraftingCpuListEntry)` 被删除，drawBackgroundLayer 改用 `Tooltips.getByteAmount` 与 `Tooltips.getAmount`。注入旧方法或调用点的附属 Mixin 可能失效。本项目未引用这些 CPU 列表目标，新工作页及格式化独立实现；本步采用的计划／CPU／任务接口和原生 link 入口没有出现在该版本改动列表。该版本还调整 FillCraftingGridFromRecipePacket，包含剩余物品掉落兜底；本项目继续使用已验收的自有 JEI 填格事务，不移植该兜底。

编译基线保留 19.2.17，运行脚本新增显式 `-Ae2Version 19.2.18` 选择与实际加载版本核对。19.2.18 来自官方项目 Modrinth 发布文件，8,236,552 字节，SHA-256 `df15a07f86ca1ca93aa66373f9d5d84dd3d4586631fceb38f4fbc85dad591e39`，并核对发布端 SHA-512；JAR 只保存在忽略的 libs，比较原始响应在 `build/ae2-19.2.18-compare.json`。运行结果以本步验收记录为准，不将源码差异核对等同于所有 AE2 附属模组的兼容验收。

<a id="s13-32"></a>
### 13.32 网络工作台的双视图复用（2026-10-06）

以本 worktree 的 `02b74ca` 为基线读取 `TerminalClientState`、`NetworkSelectionSession`、`CoreTerminalSubscription`、`TerminalSubscriptionService`、`TerminalCraftingMenu` 及 JEI 转移入口。采用现有会话／代际校验、当前页续租、全服公平调度、数量索引 retain/release 和真实材料账户；常驻产物区使用独立会话，但只在原队列条目中轮流执行现有查询步。不得把管理页 generation 当作产物授权，也不复制库存／合成服务。界面复用本项目九宫格皮肤、物品预览与原版控件，延续 [13.24](#s13-24) 的分区原则；本步未更新参考副本或引入外部贴图／依赖。

<a id="s13-33"></a>
### 13.33 独立机器工作状态与菜单详情（2026-10-06）

以网络分支 `e76583e` 为基线读取 `MachineMenu`、`MachineWorkService.access/commit`、`MachineAssets.work/commit`、`CombinedMachineWork`、`FiniteProductBuffer`、`MachineUpgradeProfiles`、`BeeRecord.drained` 与 `CentrifugeJob.paid`。详情沿当前工作根和形成绑定读取，不调用升级预估或配方重算；旧周期已固定的进度／并行与下一周期配置不能混用。采用原菜单 ContainerData 的 16 位拆分传递有符号坐标及 long 数量，固定六蜂位／三进程字段，十 tick 采样且只同步变化；现有读写守卫和无线材料账户继续负责操作。未引入第三方 UI／可选模组类型，未更新外部参考副本。

D20b2 于 2026-10-07 继续复核上述固定 AE2 的 `CraftingCalculation` 构造器、`NetworkCraftingSimulationState`、`CraftingCpuLogic.trySubmitJob/finishJob/storeItems` 和 `CraftingCPUCluster.getSrc`：计划在主线程捕获库存，后台使用封闭快照；提交按玩家 source 实际取料，CPU 持有输入，取消／完成通过自身 MachineSource 回流。接收方不可用或当前类型被拒绝时，CPU 只扣实际存入量并保留余量。以真实 CPU、现有终端点击和蜂业账本验证这些路径，不移植 CPU 私有资产或增加第二份合成库存。测试配方替换必须同时调用本项目正式 `CentrifugeRecipeIndex.rebuild` 与递增代际；只换 RecipeManager 而保留旧派生蜜脾块索引不能代表正常重载。

<a id="s13-34"></a>
### 13.34 用户 WCWT 截图与标准槽位目标（2026-10-07，2026-10-08 补充）

来源为用户本轮提供的 13 张 AE2 WCWT 截图，物品 tooltip 标识为 `wcwt:wireless_comprehensive_work_terminal`。可直接确认：综合工作台的连续库存／手动合成／背包、样板编辑与批处理区域；名称／数量／模组排序、升序、已存储／可合成浏览；物品／流体／其它类型过滤及可见类型列表；无线与终端设置中的选块、缺料合成、补货、磁力、拾取入网、样板／工具包选项、完成置顶／提醒及搜索模式／记忆／焦点／JEI 同步。截图不能证明插件版本、服务端协议或整理兼容；当前未找到可核实的本地 WCWT JAR／源码，因此不把图中所有功能写成 AE2 19.2.17 原生 API，也不据图复制上游实现或素材。

2026-10-08 继续读取原聊天最后四张截图及本地固定 AE2 19.2.17 的 MEStorageScreen 构造、SettingToggleButton 和库存点击入口；采用顶部搜索、左侧排序／显示／类型／方向工具栏、连续格距、右侧滚动条、下方合成与背包的组织方式。截图已从原聊天的嵌入图片恢复至忽略的 build/ae-terminal-user-reference-1.png 至 -4.png。工具栏仅连接已实现行为，面板与图标由本项目绘制；本轮不调用 AE2 客户端内部类，也不引入 WCWT 依赖。目录只扩大当前有界库存页至 36 项，保留 32 KiB 预算及服务器键身份，不沿用 AE2 整目录客户端仓库。

D18j4c2 读取固定 AE2 19.2.17 的 ToolboxMenu、MEStorageMenu 构造／tick、NetworkToolItem.findNetworkToolInv／getInventory／NetworkToolInventoryFilter、NetworkToolMenuHost、ItemMenuHost.isValid、RestrictedInputSlot.UPGRADES，以及 AppEngInternalInventory.toItemContainerContents／fromItemContainerContents。原工具包读取网络工具的九格 CONTAINER 并锁住本体，过滤沿 Upgrades.isUpgradeCardItem；上游 copyInto 会只复制目标槽数，本项目先拒绝超过九格的原数据，避免裁剪后覆写。只认已核对格式的 AEItems.NETWORK_TOOL，并把上游按物品类型的定位加强为原位置／对象与组件根绑定，不复制定位到另一件同类物品的行为。另读当前 NeoForge 21.1.216 源码中的 ItemContainerContents（getSlots／getStackInSlot／fromItems）和 SimpleContainer（setItem／setChanged／getItems），按原格式写回完整稀疏九格；覆盖 setItem 的超量输入以避免其默认 limitSize 静默截断。类型查询留在 MeBridgeIntegration.Loaded，原生九格和物品组件保管不依赖 AE2 菜单父类。没有增加依赖、更新参考副本、编译或运行游戏。

D18j4c1 读取当前 worktree 的 build/moddev/artifacts/neoforge-21.1.216-sources.jar 内 Minecraft 1.21.1 InventoryMenu、ArmorSlot、Slot 与 AbstractContainerMenu；只把对应源码提取到忽略的 build/net/minecraft/world/inventory。原版 ArmorSlot 为包内类，采用其 canEquip、单件、PREVENT_ARMOR_CHANGE、onEquipItem 和原版空图标规则自行封装；副手沿 InventoryMenu 的普通槽规则。AbstractContainerMenu.SWAP 直接调用 Inventory.setItem 操作另一端，且大栈分支可能调用 drop，因此在既有容器边界增加另一端无线设备锁并继续拒绝掉落分支。读取本地 AE2WT 1.21.1 参考 build/reference/ae2wt-1.21.1 的 WCTMenu 与 ArmorSlot，工作树干净、HEAD 49f70f0627b40762cfbd33732834c7271d5ec17d，gradle.properties 指定 NeoForge 21.1.219／AE2 19.2.17；采用装备部位／副手可达及在用副手槽锁定的原则，以本项目 21.1.216 原版规则为准，不复制其包装库存或把护甲诅咒施加到副手。另读固定 Mekanism 10.7.19.85 的 ArmorSlot 核对相同回调；未更新参考副本、增加依赖、运行编译或游戏。

D18j4a1 读取本地固定 AE2 19.2.17 的 PatternDetailsHelper、EncodedProcessingPattern、AEProcessingPattern、AEPatternHelper、EncodedPatternItem、GenericStack、AEComponents 与 AEItems。原生处理样板上限为 81 个输入位／27 个输出位；EncodedProcessingPattern 保留稀疏 null 位，GenericStack 的数量为 long，缺失资源可能由容错 codec 变成 MISSING_CONTENT。AEPatternHelper.condenseStacks 使用 Long::sum，因此本项目在改写前同时检查逐格乘法和同键合计，缩小逐格精确整除，不依赖静默溢出的合并结果。使用 AEProcessingPattern.encode 在原物品副本上仅替换编码组件，并重新构造原生详情；不使用 PatternDetailsHelper.encodeProcessingPattern 创建默认新物品覆盖自定义组件，不调用上游物品右键清空入口。样板模式及输入／输出对比复用本项目有界 ME 回执；确认沿 TerminalCursorExchange 的服务器保管投影、容器锁与原位同数量发布。无需新增运行依赖或世界／ME 库存查询，没有移植 WCWT 代码与材质。源码接口核对不代替编译、运行或保存恢复证据。

D18j4a2 继续读取同一固定 AE2 19.2.17 的 PatternEncodingTermMenu.encode／encodeCraftingPattern、PatternDetailsHelper.encodeCraftingPattern、AECraftingPattern 构造／encode、EncodedCraftingPattern，以及 StorageCellUpgradeRecipe 的 ItemStack.transmuteCopy 用法。原生合成编码保存九格输入、输出、配方 ID 和两种替代标记；详情构造按当前配方重新匹配及组装。本项目沿 TerminalCraftingMenu.find 的普通配方／有限合成规则，捕获实际材料账户的一件样本，默认不允许物品或流体替代。用 transmuteCopy 保留空白样板组件与张数，仅增加合成编码，再构造原生详情比较输出。借鉴原生空白样板消耗语义，但不复制其菜单槽位、自动补充或直接设置结果栈；确认复用本项目账户锁与 cursor 保管。材料与配方身份仅存当前预览，未引入新依赖、长期缓存或上游参考更新。

D18j4a3 读取固定 AE2 19.2.17 的 ContainerItemStrategies.getContainedStack(ItemStack, AEKeyType)、ContainerItemStrategy、FluidContainerItemStrategy、GenericContainerHelper、AEKey.getType 及 EncodedProcessingPattern；复用前包已核对的 AEProcessingPattern.encode／构造与原生数量边界。带类型重载只查询对应注册策略，流体默认委托 FluidUtil.getFluidContained，不会遍历全部键类型；不采用 findCarriedContext／insert／extract 或 EmptyingAction。样本仅提供副本，调用前后比对完整 ItemStack，返回键再次核对类型与正数量；附属资源可用性取决于实际策略登记，不声称全部容器已验证。共享编码验证处理缺失资源、稀疏槽和同键 long 合计；复用本项目材料账户 State 和 cursor 改写，不新增第三方类型引用、依赖或参考副本更新。

D18j4a4 继续读取同一固定 AE2 19.2.17 的 AEProcessingPattern.MAX_INPUT_SLOTS／MAX_OUTPUT_SLOTS、encode／构造、PatternDetailsHelper.encodeProcessingPattern，以及 ContainerItemStrategies.getContainedStack(ItemStack)。自由草稿采用 81 输入／27 输出和首个输出为主产物的原生合同；无类型重载按 AEKeyTypes 已注册顺序返回首个有内容策略，适用于尚无原资源类型的新增项，并在界面明确这一选择。只读样本副本，调用后复核不变及材料账户版本；不使用容器提取／插入或上游菜单资产槽。编码辅助先产生原生编码，再通过 transmuteCopy 保留真实空白样板的其它组件和张数，并重新构造详情；同侧合计仍由本项目精确检查。草稿的跨工作页保留和实际菜单关闭清理由本项目 MeTerminalSession 承担，AE2 键仅保留在兼容实现，没有新依赖、参考副本更新或运行证据。

D18j4a5 继续采用上述固定 AE2 19.2.17 的完整键、带类型 ContainerItemStrategies 样本读取及 AEProcessingPattern 稀疏编码合同，将已实现的单样板替换抽为可复用映射；每个背包候选仍单独验证原生上限、缺失内容和同键 long 合计。批次准备沿本项目 MeTerminalBudget 的每真实 tick 至多一次、每 20 tick 至多八次全服昂贵预算，每次仅处理一叠；列表投影不附带整个编码组件。最终背包写入采用本项目 TerminalNativeSlots／TerminalCursor 已有的玩家所有权与容器互斥边界，先全部检查和复制结果、再原位发布，最后同步；不调用上游菜单 slot 点击、外部库存或丢弃回退。原生背包负责保存实物，批次预览不增加持久化；没有新增依赖、参考副本更新或运行证据。

D18j4b3a 复用固定 AE2 19.2.17 已核对的处理样板稀疏编码、完整键与容器样本规则；本次重读本项目 AePatternReplacement.capture／supports／prepare／current、MePatternBatchSession、TerminalPatternSample.commit、TerminalPatternInventory.Replacement 及 TerminalPatternBuffer.Snapshot。将 a5 的资源映射和预算复用于第二个真实容器，读取九格不可变根，提交走私人缓冲的一次根替换；不借用背包数组写入或模拟供应器成功。关闭前捕获提交快照，样本重查后的代际和缓冲根仍需复核；未新增上游 API、依赖或运行证据。

D18j4b3b 继续采用固定 AE2 19.2.17 已核对的 PatternProviderLogicHost／InternalInventory 槽访问、插入余量及实际提取返回合同，并读取 IPatternDetails.getPrimaryOutput／getOutputs 的 List<GenericStack> 摘要入口。上传解析单件副本，取回只沿 isEncodedPattern 识别；不复制上游直接写回或补偿互换。复用本项目 AePatternProviderTarget 的原节点／BE／部件／库存身份、TerminalPatternBuffer.Snapshot 的九格根、TerminalCraftingPlan.insert 的完整组件及实际容量，以及 TerminalCursorExchange 的未决 Request／Observed 保管。将栈返回校验抽为包内共用函数，批量调用前先撤销本项可重放资格；部分结果停止余项，已完成项不回滚。没有新增上游依赖、参考副本更新或运行证据。

D18j4b4 读取同一固定 AE2 19.2.17 的 PatternProviderLogicHost.getPriority／setPriority／getConfigManager、PatternProviderLogic 构造／setPriority／configChanged／resetCraftingLock／pushPattern／readFromNBT／writeToNBT、PatternProviderMenu.broadcastChanges、IConfigManager、ConfigManager.putSetting 及 Settings／LockCraftingMode。优先级沿宿主 setter 保存并请求合成提供者更新；ConfigManager 先改值再调用监听器，异常不能推断未修改。原生锁定模式回调调用 resetCraftingLock，但未持有 unlockEvent 时不会标记保存，因此本包设置后显式保存；重复值不触发 setter。该步仅采用五种固定原生模式；锁状态与手动解锁后续按下述 b4a 增加实例版本保护。可见性沿既有目标权限，隐藏后释放目标，恢复显示留供应器本体。未更新参考副本、增加依赖或运行验证。

D18j4b4a／b3c（2026-10-09）继续读取固定目录 `../decompiled-reference/productive-bees-addon-1.21.1/ae2-19.2.17-decompiled` 的 PatternProviderLogic.resetCraftingLock／onPushPatternSuccess／getCraftingLockedReason／getUnlockStack／onStackReturnedToNetwork／updateRedstoneState／readFromNBT，以及 AppEngInternalInventory.setItemDirect／onContentsChanged／getHost、PatternProviderLogic.onChangeInventory／saveChangedInventory／updatePatterns。原生两类锁字段所有写入口纳入版本失效，包含读档与同值重建；getCraftingLockedReason 可能查询邻居红石，因此先检查已加载范围。resetCraftingLock 先清字段后保存，失败不能推断未解锁。Mixin 插件只作原生结构字节码资格检查，不加载缺失 AE2；附属逻辑与任意额外注入不由此视为已兼容。

原库存 setItemDirect 先替换槽，再通知逻辑保存和重建全供应器样板；回调失败没有自动回滚保障。原位替换因此仅支持精确原生逻辑／库存和一致 host，一次写入后核对结果，未知即停止；不采用取出再插入、补偿写回或 world-drop。复用项目 AePatternEditor 的完整键／稀疏编码和 TerminalPatternSample 的真实账户根、样本互斥，不为供应器另建资产账本。原生 updatePatterns 仍扫描该供应器所有槽，不能把本步单槽入口写成 O(1) 全流程。未更新参考副本或增加依赖；本次集中编译与现有回归范围见[10.148](bee-processing-network-evidence-current.md#s10-148)。完成提醒两类可选 Mixin 同步沿项目既有 require=0 约定，缺少方法体目标时跳过；安全聚合和锁版本依赖完整注入，仍先核对原生字节码结构再启用。

D18j4b3d（2026-10-09）复用上述固定 AE2 19.2.17 的 PatternProviderLogic／AppEngInternalInventory 原位写入与保存／重建合同；未添加新上游 API 或更新参考副本。重新核对项目 AePatternReplacement 的完整键映射、supports／prepare／current，MePatternBatchSession 的逐项准备、选择／明细及样本根，AeProviderBatch 的一次执行指针、部分完成与终态保留；外部范围不采用背包／私人缓冲的一次根替换。单槽写入提取为共用 AeProviderPatternWriter，保留写前身份／数量／准入与写后回读，未知无补偿。批量范围只来自已展示页或当前供应器，身份去重、槽预算和分步准备限制引用；没有全网缓存或后台世界访问。原生逐槽更新仍会重建该供应器全部样板，不据分批即宣称性能改善。

D18j4d1 读取本 worktree 的 `build/moddev/artifacts/neoforge-21.1.216-sources.jar` 中 Minecraft.pickBlock、Inventory.findSlotMatchingItem／pickSlot／getSuitableHotbarSlot、ServerGamePacketListenerImpl.handlePickItem、Player.blockInteractionRange、InputEvent.InteractionKeyMappingTriggered 和 IBlockStateExtension／IBlockExtension.getCloneItemStack。UTF-8 文件工具拒绝 JAR，改用只读 ZIP 入口带行号提取对应源码；未解包覆盖工作区或运行编译。采用原选块事件、完整组件匹配和真实库存换位，禁止使用 setPickedItem 的缺货创建分支；生存取样不附加创造模式方块实体 NBT。复核本项目 WirelessDeviceSession、WirelessTerminalAccess／WirelessMachineAccess、MeBridgeTarget、MeBridgeNode.materials、AeRecipeMaterials、TerminalCursorExchange 及 cursor 关闭保管。无线设备新增固定背包／副手位置会话，旧持手会话保持；精确键提取复用固定 AE2 19.2.17 StorageHelper.poweredExtraction，新增已知聚合故障拒绝，不枚举库存作选块查询。没有可核实 WCWT 源码，不把图片中的持续补货或缺料合成当成本步接口。

D18j4d2 重读同一固定 AE2 19.2.17 的 ICraftingService.isCraftable／canEmitFor／beginCraftingCalculation／submitJob 签名，复核本项目 AeMeTerminal 的 REPORT_MISSING_ITEMS、Future 轮询／超时、CPU 选择、提交前撤销与未知结果路径，以及 MeBridgeNode.terminal 的原节点／网格捕获。采用精确 AEItemKey 和已有官方计划服务，不沿 getFuzzyCraftable 替换组件；成功后由 AE2 作业和 ME 库存持有资产，不增加第二份在途余额或玩家自动领取。菜单封存原世界样本，客户端一次性入口仅请求开始计划；模式切换保留原持手或固定槽语义，原生槽锁增加实际背包设备的双端定位。复核 TerminalCursorExchange 的 NO_SPACE 同时覆盖零提取和无接收空间，因此本步另记录外部回调是否执行，避免将跳过、满载或未知结果当作缺货。未新增依赖、更新参考副本或运行游戏，WCWT 仍只作为功能参考。

D18j4d3 复用既有固定 AE2 19.2.17 精确 AEItemKey／StorageHelper.poweredExtraction，不新增外部 API 或假设 WCWT 的未核实实现。重读本项目 AeRecipeMaterials.valid／extract、MeBridgeTarget、WirelessDeviceSession、TerminalCursorExchange 的 Request／pending／unknown、TerminalCraftingPlan.insert，以及 MeTerminalBudget 的共享额度；客户端／服务端 tick 签名沿当前 NeoForge 21.1.216 已接入的 MachineActivityClient 与 NetworkTickService。采用每次服务端新读背包和单次短寿命来源，不保留外部库存／世界对象到队列。因原背包交接只合并到 min(64, 物品上限)，补货候选与缺额同步采用该界限，避免对更大堆叠持续补进空格；未知返回仍留原附件保管。选块和补货抽取同一设备选择／宿主校验，不增加 AE2 类型到常驻类；队列只公平轮转新库存检查，预算不足不当作缺货收费，取消保留原冷却。无新增依赖、参考副本更新、编译或运行证据。

D18j4d4（2026-10-10）复核同一 Minecraft 1.21.1／NeoForge 21.1.216 源码的 Inventory.items／armor／offhand、getItem 和 setChanged：原生分区为 36／4／1，副手槽号 40，setChanged 只增加变更计数。专用 UTF-8 入口拒绝大于 8 MiB 的 sources JAR，沿只读 ZIP 带行号读取，无解包覆盖。采用已验证的原生列表直接提交，先计划完整组件与目标缺额，再按实际外部量扣减 pending 并替换原槽；不调用 Inventory.add 的跨槽合并。重读项目 TerminalCursorExchange.complete／recover／depositInventory、TerminalCursor 序列化、WirelessDeviceSession 和 WirelessInventoryAccess.valid／capture；共用原 Request／pending 与未知隔离，仅分开原槽补货和既有鼠标／主背包交付策略。来源仍是项目已核对的 AE2 19.2.17 精确提取接口，无新增上游 API、运行依赖或世界引用缓存。

D18j4e1 从本 worktree 的固定 NeoForge 21.1.216 sources JAR 带行号只读提取 ItemEntityPickupEvent.Pre／Post 和 ItemEntity.playerTouch、getTarget、hasPickUpDelay。继续采用已说明的 ZIP 读取降级，未解包覆盖源码或调用编译。确认 Post 在真实 Inventory.add 成功后、实体丢弃／take／统计回调之前触发，originalStack 是副本，currentStack 是实体活引用；原 API 明确禁止在事件中 setItem，因此本包不接管实体，只捕获前后主背包净增量，并把 ME 交接延期到同一 tick 末尾重新核对。设备的 CustomData 收费实现只改原设备组件，另行比较其余背包。AE2 仍用固定 19.2.17 项目既有的 StorageHelper.poweredInsert／PlayerSource；复核 AeRecipeMaterials 的隔离、原节点／网格和实际量校验，把源库存扣款接入现有 TerminalCursorExchange Request／pending 结算，未新建第二套资产存档。完整组件数量按 long 汇总，先拒绝非正净增量再缩为有界 int；不依赖其它拾取插件遵守尚未验证的隐式时序，后续快照变化直接放弃自动入网。

D18j4e2 继续读取同一 NeoForge 21.1.216 sources JAR 的 Level.getEntities（带 AABB、谓词、输出列表和 limit）、EntityTypeTest、LevelEntityGetterAdapter、EntitySectionStorage／EntitySection、ItemEntity.playerTouch／getTarget／hasPickUpDelay、Entity.setDeltaMovement／getPersistentData 和 ServerEntity 的 hurtMarked 速度同步。文件工具再次拒绝二进制 JAR 后使用只读 ZIP 带行号读取，无源码解包或构建。查询在返回数到 limit 时 ABORT，但分区类型索引和相交过滤仍受局部密度影响，因此先限制 16 个返回候选、再过滤排序，不把结果上限描述成严格访问／耗时上限。采用标准速度及碰撞，保留真实拾取事件，不把原 Pre 事件当作磁力权限探针提前触发。项目主动识别实体 no_magnet 标签及 PreventRemoteMovement 布尔标记，仅作为明确退出约定，不声称已验证所有保护模组。重读 WirelessMachineAccess、MeBridgeTarget.live、WirelessDeviceSession、TerminalCraftingPlan 与共享预算；从 WirelessInventoryAccess 抽出同一宿主校验供无需 AE2 的磁力使用，原 ME 入口仍校验桥和来源。未新增依赖或引用 AE2 类型，WCWT 仍仅作用户功能参考，未复制上游素材或未知实现。

D18j4e3（2026-10-10）读取本机 Gradle 缓存的 NeoForge 21.1.216 sources JAR：ModConfigSpec.Builder.defineList／ListValueSpec／Range.of，以及 ConfigurationScreen.createStringValue、列表新增／删除／排序入口。专用 UTF-8 读取拒绝二进制 JAR，使用只读 ZIP 带行号读取，没有解包覆盖参考源码。确认旧三参数 defineListAllowEmpty 没有新增行供应者；本步采用带 newElementSupplier 与 Range.of(0,16) 的 defineList，原生编辑框至少允许 128 字符，因此单个完整 ID 限 128，不把整张列表拼入一个输入框。客户端只保存本地偏好，服务器保存有界不可变 ID 快照；复核项目两类无线意图、限流前失效、拾取 Pre／Post 及同 tick 结算、磁力 limit=16 查询与移动前校验。筛选仅决定已有物品是否参与原流程，不放宽完整组件交接／权限／保护或增加 AE2 类型；WCWT 仍仅作功能参考，无新依赖或参考副本更新。

D18j4b1 读取固定 AE2 19.2.17 的 PatternDetailsHelper.isEncodedPattern：按已注册 IPatternDetailsDecoder 的识别入口判断编码样板，区别于 decodePattern 的实际配方解析。缓冲准入只用空白定义或这一识别入口，传入单件副本并复核不变，取回不再次解析配方。玩家实物保管复用本项目 TerminalCursor.SERIALIZER、NetworkContent.TERMINAL_CURSOR 的 copyOnDeath 注册、TerminalCraftingPlan.copy／insert 和容器锁，schema 6 严格追加固定九格，不借用共享材料账户或第三方槽位保存。

D18j4b2 读取同一固定 AE2 19.2.17 的 PatternAccessTermMenu.broadcastChanges／visitPatternProviderHosts／doAction／quickMovePattern，PatternContainer、PatternProviderLogicHost、PatternProviderLogic.onChangeInventory／updatePatterns，IGrid 与 Grid.getNodes／getMachineClasses／getMachineNodes／getActiveMachines、IGridNode、IActionHost、AEBasePart.getHost／getSide 与 IPartHost.getPart，以及 InternalInventory.insertItem／extractItem 和 AppEngInternalInventory.onContentsChanged。Grid.getNodes 是机器节点集合视图，原 getActiveMachines 会构造集合；本步改用有预算的迭代器，只保留单页身份，变更失效后要求重查。原生终端按 PatternContainer 类型和可见性枚举，本步缩到可证明原 BE／部件与实际节点身份的 PatternProviderLogicHost；供应器写入先改变槽，再触发保存和配方重建，回调异常不证明零接收。采用原接口返回余量／实际栈的合同，调用前登记未决请求，错误返回样本与实际数量保留在 schema 7；不复制原生直接互换／补偿写回。quickMovePattern 使用 decodePattern，本步空槽单张存入也要求当前可解码，不从缓冲的 isEncodedPattern 准入推导可上传。Platform.hasPermissions 明确同世界与 mayInteract，本步沿这一范围并校验宿主权限，不臆造旧安全终端 API；PatternContainerGroup.fromMachine 可查询邻居能力，只在六邻区块已加载时取分组名。图标单独使用有最大容量的 512 字节 buffer，超限仅省略展示，原物品不改写。未更新参考副本、增加依赖或运行游戏。

D18j3c3 读取固定 AE2 19.2.17 的 CraftingJobStatusPacket.handleOnClient、PinnedKeys、PendingCraftingJobs 及 Repo 的置顶分区，并重查 CraftingCpuLogic.notifyJobOwner／finishJob、CraftingCPUCluster.getGrid；以本地 19.2.18 JAR 的 javap 核对 notifyJobOwner 中 ServerGamePacketListenerImpl.send(CustomPacketPayload) 调用点。原生客户端在 STARTED 时记录预期产物，本步用户要求完成产物，故观察同一服务器发送入口的 FINISHED，不把开始／取消当成完成。采用有限历史和完整 AEKey 匹配原则，保持原生发送与作业结算；不移植 Repo 的客户端全目录或上传客户端键。个人置顶顺序在 AeMeCatalogue 共享筛选快照之外派生，沿 MeTerminalBudget 的退出／停服入口和 MeBridgeIntegration.Loaded 隔离清理，不增加新的事件扫描器或后台线程。

D18j3c2 复用本项目 TerminalCraftingPlan.clear／insert、TerminalCraftingMenu.execute／current、TerminalCraftingAccount 的位置／设备账户与 owner，以及两类菜单既有校验和计费；NetworkTerminalScreen／MachineScreen.onClose 发送正式 TerminalRequest，Screen.removed 只维护原展示生命周期。沿 NetworkCoreMenu／MachineMenu 的 TerminalCraftingMenu.Host 比较真实账户引用，防止共享使用时自动抽走材料。不采用上游掉落补偿，不调用 ME 插入；最多九格的完整组件余量保留在原账户，未改存档 schema。无新增上游 API 或依赖版本。

D18j3c1 读取固定 AE2 19.2.17 的 CraftingCpuLogic.trySubmitJob／finishJob／notifyJobOwner、CraftingLink／CraftingLinkNexus、CraftingService.addLink、CraftingJobStatusPacket.handleOnClient 和 PendingCraftingJobs.jobStatus。独立玩家提交的 result.link() 可以为 null，standalone link 不接 nexus，不能用行消失或 link.isDone() 轮询猜测成功。采用 AE2 已有的在线所属玩家 STARTED／CANCELLED／FINISHED 事件和 FinishedJobToast；Mixin 只扩展已有两处提醒资格，并在同一 addToast 调用去重，不新增作业追踪服务或服务器协议。通过本地 19.2.18 JAR 的 javap -c 核对三个调用点保持一致；AppEngClient 的 LoggingIn 回调调用 clearPendingJobs，新增只存 UUID 的短历史随该入口清理。Mixin 仅列于 client，并纳入现有 AE2 加载检查，不复制原生材质或其它模组资产逻辑。

D18j3b 读取固定 AE2 19.2.17 的 MEStorageScreen.updateSearch：外部键盘焦点与本地输入焦点决定同步方向，不将缓存搜索反复覆盖另一侧。通过本地 JEI 19.36.0.360 JAR 的 javap 核对 IIngredientFilter.getFilterText／setFilterText、IIngredientListOverlay.hasKeyboardFocus 和 IModPlugin 的运行时可用／停用回调，运行版仍为 19.39.0.368。只采用公开字符串与焦点 API，通过既有 ProductiveBeesGenesisJEI 插件注册可撤销入口，不引用 ItemListMod／JEI 内部类实现生产同步，也不移植 AE2 的客户端全目录检索。NeoForge 21.1.216 ScreenEvent 的鼠标按下／释放、字符输入签名由本地 universal JAR 核对，客户端探针沿实际事件链获得 JEI 焦点并输入；Internal.getJeiRuntime 及 MouseHandler 反射仅留在 domainProbe。

D18j3a 沿本项目 ClientConfig、WindowPositionConfigSection 与 CustomConfigScreenFactory 的现有 NeoForge 客户端配置入口实现；读取与存储使用 ModConfigSpec 的有界值，原生 ConfigurationSectionScreen 展示分组，枚举使用 TranslatableEnum。偏好在屏幕 removed 时保存、下一屏首次 build 时读取，避免子屏构造早于父屏保存而复用旧值；只有实际改变的展示条件写入，搜索记忆关闭后清空查询。没有引用 AE2 客户端内部类、WCWT 配置包或第三方磁盘文件；JEI 搜索同步与 AE2 任务完成通知没有在本步模拟接入。

D18j2b2b2 从 Modrinth 官方项目元数据确认 Applied Mekanistics 1.6.3 支持 Minecraft 1.21.1／NeoForge，再读取[上游固定标签源码](https://github.com/AppliedEnergistics/Applied-Mekanistics/tree/137f24bb9a46775ddd5a620055270b5e8a540f5a)（标签 1.6.3，提交 137f24bb9a46775ddd5a620055270b5e8a540f5a）的 MekanismKey、MekanismKeyType、ChemicalContainerItemStrategy、gradle.properties 与 neoforge.mods.toml。其声明基线为 AE2 19.2.10、Mekanism 10.7.14.79；模组 ID 为 appmek，化学品键身份来自 Chemical 注册表，withAmount(long) 返回副本，插入能力返回拒收余量。采用真实键转换与 long 计量；不调用 MekanismKey.addDrops（会转交辐射倾倒），不把真实鼠标物品交给上游策略执行。新增固定非传递 compileOnly 坐标 maven.modrinth:applied-mekanistics:1.6.3 与可选加载声明，未安装时不加载其内部类型；本轮没有修改本地参考 Git 副本或安装运行模组。

化学品容器与存档依据本地固定 mekanism-10.7.19.85-sources 的 IChemicalHandler、ChemicalStack、Capabilities：使用 CHEMICAL.item()、SIMULATE／EXECUTE、严格 save／parse 往返和 isSameChemical；多罐合计可能超过 long，采用 BigInteger 仅核对净变化。沿现有 TerminalContainerItems／TerminalCursor 的有限交付与服务器线程，不增加新的世界引用或缓存。官方源码与版本元数据确认不代表 Maven 依赖已解析或运行兼容已验收，本包均未编译／运行。

D18j2b2b1 读取固定 Applied Flux 2.1.5 的 FluxKey／EnergyType、FEContainerItemStrategy、CarriedContext，以及 AE2 19.2.17 的 ContainerItemStrategy／ContainerItemStrategies／ContainerItemContext。FluxKey.of(EnergyType.FE) 与 GTEU 是不同键；真实玩家容器上下文只接受鼠标／背包中的所属物品，Applied Flux CarriedContext.addOverflow 调用 placeItemBackInInventory，故不用于本项目私有副本交付。采用 NeoForge 21.1.216 的 Capabilities.EnergyStorage.ITEM／IEnergyStorage，副本模拟与执行、净电量及复制后持久内容由本项目核对；外部 FE 沿 StorageHelper.poweredInsert／poweredExtraction 的实际返回量与未知结果语义。Applied Flux 类型仅在 AeMeEnergy.Loaded 引用，外层沿现有 AppliedFluxIntegrationLoader 守卫；核心保管不持 FluxKey，离线取回无需该模组。共享既有菜单、预算和容器交付边界，没有新增全服缓存、后台任务或探针。此包未进行编译、测试或运行验证，不把本地源码阅读写成兼容证据。

D18j2b2a 复核 AE2 19.2.17 的 ContainerItemStrategies、FluidContainerItemStrategy 及 StorageHelper.poweredExtraction／poweredInsert。采用左键装入、右键排出、模拟只读和实际返回量结算；不使用 AE2 CarriedContext.addOverflow 的 placeItemBackInInventory 回退，因为满载时可能产生掉落。NeoForge 21.1.216 的 FluidHandlerItemStack／SimpleFluidContent 签名从本地 universal JAR 核对，容器能力在单件副本上准备，全部罐的完整组件与净变化由本项目校验；每次最多检查 32 罐。逐玩家保管与未知请求沿现有 TerminalCursor 扩展，不在常驻代码引用 AEFluidKey，也不引入新的全服缓存。注册给 PAPER 的标准单罐和 probe_fluid 组件仅属于 domainProbe 隔离夹具，不进入产物或真实玩家世界。

D18j1 修改前的实际差距由源码确认：`NetworkCoreMenu`／`MachineMenu` 的 clicked 和 quickMoveStack 被关闭，背包槽禁止 pickup／place；`TerminalCraftingMenu.slot` 返回只读 SimpleContainer 投影；两种 Screen 把鼠标动作截获为选源及定量请求。有线材料账户由同一位置的授权查看者共享，鼠标携带资产不能使用一份公共 cursor 覆盖不同玩家。沿现有已付费制作、账户严格保存与无掉落原则建设原生槽位，不以开放副本拾取或单纯改边框替代。实现及验收按[主合同 9](bee-processing-network-design.md#s9)、[D18j](bee-processing-network-roadmap.md#s11-5)推进。

D18j1 读取本地 NeoForge 21.1.216／Minecraft 1.21.1 源码的 AbstractContainerMenu.doClick（PICKUP／QUICK_CRAFT／QUICK_MOVE／SWAP／PICKUP_ALL）、AbstractContainerScreen、SimpleContainer 及 NeoForge IAttachmentSerializer／AttachmentType。采用稳定槽索引、真实玩家 Inventory、独立材料与结果 Container、标准点击／预测／同步及玩家持久附件。原版 SWAP 在超大堆叠置换且背包满时会调用 player.drop，该分支和 THROW／窗口外丢弃明确拒绝；结果预览不开放普通 mayPickup。普通槽操作不套用旧管理命令每 tick 两次的频率限制，否则一次原生拖拽的头／选槽／结束包会中断。玩家 attachment 采用 Tag 泛型，非法顶层类型也能原样保留，材料账户 schema 不变。未添加对 AE2、WCWT 或整理插件的硬依赖；标准 Container 操作验证只证明兼容基础，具体整理插件仍需实际版本联合验证。

D18j2a 继续读取本地固定 AE2 19.2.17 的 `StorageHelper.poweredExtraction`、`KeyCounter.findFuzzy`、`VariantCounter`、`IActionSource.context` 与本项目 `MeBridgeStorage`。采用物品主键索引定位完整组件变体、玩家来源与原生耗能提取；KeyCounter 的变体集合是索引视图，无序变体不会为每次查询复制全库。poweredExtraction 在真实提取后还执行统计回调，因此异常不能证明零提取，接收账户在调用前保留未决请求。当前请求 context 只排除本次已经由内部事务使用的同一 NetworkSavedData，模拟与执行一致，不修改其它网络或长期自动化来源。未引入新运行依赖或后台世界访问；真实 API／部分返回／异常和恢复结果按[当前证据](bee-processing-network-evidence-current.md)登记。

D18j2b1 复用上述版本，补读 StorageHelper.poweredInsert 的模拟、能量扣除、实际存入和统计回调顺序，继续使用 KeyCounter 及 ICraftingService.getCraftables 合并存储／合成目录。模拟不代表保证接受，实际存入后的统计异常也不代表零接收，因此存入请求先从可操作鼠标物品中移出，已知拒收余量与未知请求分别保管；提取采用相同的逐玩家有限接收合同。库存／查询快照扩展既有 AeMeCatalogue 缓存，40 tick 共享、每代最多八个查询、弱网格键和停服清理，不在缓存值反向保存网格／世界。Name 使用服务器解析名称，排序以完整键作为稳定次序；客户端仍只接收八项与既有字节预算。没有采用第三方库存容器掉落回退或新增全表客户端下载。
