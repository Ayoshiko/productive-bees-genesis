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

每次参考前先确认独立仓库边界、工作树和上游，再执行 `pull --ff-only`；若有本地改动或不能快进，保留现场并记录原因，不自动 stash／reset。PB 13.13.5、Mekanism 10.7.19.85 的版本源码、`.tmp_gtnh_src`／`.tmp_gtceu_src` 摘录与 Thunderbolt JAR 没有可拉取的独立 Git 元数据，不能报为已更新；固定依赖 API 继续以实际编译 JAR 为准。第 5.4 节保留存储算法原审查提交，最新工作副本与本轮新增核对范围以本节为准。

AE2LT 参考源码使用 NeoForge 21.1.220，EAEP 使用 21.1.238，ECO 使用 21.1.233、Useless 使用 21.1.249；这些是历史参考快照的 API 基线；本项目当前开发基线为 21.1.216、发布最低为 21.1.214，不因参考它们而自动升级依赖。涉及具体生命周期 API 时以本项目编译基线重新验证。

| 来源与本地位置 | 已检查内容 | 借鉴及边界 |
| --- | --- | --- |
| `../../闪电全版本/ae2lt-src-2.1.0-beta.5` | `InfiniteStorageCellItem`、`ModItems`、`LayeredReservedStockPolicy`、`ReservedStockRepository`、`InventoryMaintenanceDecision`、`TianshuInventoryMaintenanceService` | 索引存储定义、双层组保留、滞回和 requester；库存实现委托 Thunderbolt，不把外壳类当完整存储引擎 |
| `run/mods/thunderbolt-2.0.0-beta.3.jar` | javap 核对 `core.storage.cell.IndexedStorage`、`DualLong126`、`IndexedStorageCellInventory`、`IndexedCellStorageSavedData`、`core.crafting.pattern.CraftingStockPolicy` | 对齐上述 AE2LT 声明的依赖；确认 primitive 数组与有限双 long、存储生命周期及非原生的合成策略接口 |
| `../decompiled-reference/productive-bees-addon-1.21.1/extendedae-plus-1.21.1-source` | `util/storage/InfinityDataStorage`、`InfinityStorageManager`、`api/storage/InfinityBigIntegerCellInventory` | long／BigInteger 双层、降级、总数增量缓存、storageRevision、纯模拟、饱和上报；已替换旧 1.20.1 副本 |
| `.tmp_neoccoaeextension_src`（main／21.2.0，`70cea49f`） | `ECOInfiniteStorageData.save/add/subtract/replayJournal`、`ECOInfiniteStorage.neoecoae$visitExactAmounts` | 普通量变标脏、结构缓存 revision、原子快照、旧日志迁移和所有可见键的精确观察；早期 `neoecoaeextension-v21.1.2-source` 仅保留历史依据 |
| `../../闪电全版本/Thunderbolt-Core-Reborn`（`42e0e7d2`） | `IndexedStorage.insert/insertExact/setAmountExact`、`BigAmounts`、`BigStorageOps` | 按键数组、结构／数量脏分离和实际接受量桥；精确模式仍有 16,384 位上限及大数开销，不采用其容量限制 |
| `.tmp_useless_src`（2.3.8.3） | `AlloyFurnaceBigIntegerCpuAdapter.claimOutputs`、`AdvancedAlloyFurnaceAeManager`、`MultiblockRecoveryData` | 大数产物按键整批交付、只回网剩余量；接收意味着库存所有权转移。异常后按零接收继续普通插入不能用于结果未知的权威交接；新配置改动尚未作为实现依据 |
| `../decompiled-reference/productive-bees-addon-1.21.1/dataenergistics-1.21-source` | `TrinityDataCoreStorageSavedData`、`TrinityDataCoreStorageProfile`、`PersistentTrinityPatternCore`、`TrinityHostedActionTicket` | 宿主身份、BigInteger、分类总数、排序缓存、拆卸作业保管／认领和窗口代际；其存储读取中坏记录跳过与未知 schema 返回空对象不能用于本项目权威域 |
| `../decompiled-reference/productive-bees-addon-1.21.1/ae2-19.2.17-decompiled` | `appeng/api/storage/MEStorage`、`IStorageProvider`、`api/networking/storage/IStorageService`、`me/service/StorageService` | 稳定库存提供者、long 操作、挂载生命周期；缓存更新仍会枚举库存，不能假设免费增量 |
| `../decompiled-reference/productive-bees-addon-1.21.1/mekanism-10.7.19.85-sources` | `common/content/qio/QIOFrequency`、`common/inventory/container/QIOItemViewerContainer` | 库存键索引、updatedItems、只向查看者同步、避免同时保存的思路；QIO 有容量且其终端协议不等于本方案的服务端分页 |
| `../decompiled-reference/productive-bees-addon-1.21.1/productivebees-13.13.5-decompiled`；`libs/productivebees-1.21.1-13.13.5.jar` | `AdvancedBeehiveBlockEntity` 的模拟／蜂笼／释放入口、`ConfigurableBee` 花源；JAR 的两条 `anvil_repair*` 配方 | 保留身份与真实副作用边界；铁蜂只有在关闭转化的已审查路径中才属于当前静态适配，不直接托管原版蜂箱 |
| `.tmp_gtceu_src` | `MEStockingBusPartMachine`、`ExportOnlyAEItemSlot` | 库存视图与真实消耗分离、周期刷新及防重复匹配；本地摘录未核实发行版本，不照搬 API 或轮询频率 |
| `.tmp_gtnh_src` | KubaTech `MTEMegaIndustrialApiary`、`MTEIndustrialApiary` | 集中蜜蜂数据、花朵需求去重、缺失原因可见；属于旧 Forestry／Forge 技术栈，只借鉴职责，不套用 1.21.1 API |

相关项目：[Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)、[Mekanism](https://github.com/mekanism/Mekanism)、[GTCEu Modern](https://github.com/GregTechCEu/GregTech-Modern)、[KubaTech](https://github.com/GTNewHorizons/KubaTech)。这些仓库主页是定位入口；五个独立 Git 参考仓库均已在 D15 前联网 `pull --ff-only` 并固定上述提交；更新状态以拉取时刻为准，也没有将未经读取的网上介绍作为性能排名。

本地旧研究 `docs/2026-09-15-天枢库存保留研究与对齐方案.md` 是背景资料，其中旧 AE2LT 路径属于历史锚点；合成库存策略的实施结论以本文第 6.6 节及新版源码为准。无需为使用保留算法强行引入 Thunderbolt；只有其可选合成扩展需要独立兼容边界。

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
