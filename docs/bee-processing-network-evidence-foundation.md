# 蜂业网络基础阶段验收归档

[主设计入口与维护规则](bee-processing-network-design.md) · [实施路线与当前状态](bee-processing-network-roadmap.md#current)

范围：第 10.4–10.21 节：D01–D12／P2。 本归档只补必要勘误，不追加现行任务状态；后续结果写入[当前验收记录](bee-processing-network-evidence-current.md)。

<a id="s10-4"></a>
### 10.4 D01 行为冻结与实测记录（2026-09-17）

生产代码仍为 `f2ae0b8`，首个已推送设计提交为 `f76a917`。D01 只增加行为测试、显式启用的 `src/baseline/java` 夹具和构建校验，不改变独立机的运行行为。所有夹具可变状态由服务器线程拥有，不引入后台世界访问；Spark 自行管理其采样线程。

| 已追踪路径 | 当前事实与网络实现必须保留的边界 |
| --- | --- |
| 蜂箱及全部蜂箱工厂 | `TileEntityMekApiaryFactory`、Extras／EMExtras 蜂箱继承同一蜂箱生产入口；`ApiaryTickHandler` 的普通入口与 JDTE flush 共享 gameTick 门控。`runTick` 包含父类、蜂笼、产出、升级、缓冲分发，外层还执行直连／AE2 输出，不能只停止 `BeeSlotTickProcessor` |
| 蜜蜂计时／基因／喂食 | `ApiaryProgressAdvancer` 用基础 occupation period 计算调整周期，保留余数，按实际推进 tick 计费；`BeeSlotTickProcessor` 检查环境与真实饲养板，`BeeProduceProcessor` 使用基因、配方概率及升级快照生成产出。当前饲养板按蜂种共享匹配；网络一蜂位一格是显式新规则，不复制旧共享样本 |
| 升级能力 | 蜂箱 `ApiaryUpgradeHandler` 明确不支持 STACK：升级数为 0、周期产出次数倍率为 1。`BalanceConfig` 决定预设、PB 等级互斥与安装上限，必须读取实际安装数和生效规则，不能按请求安装数计算。蜂箱速度／PB 产量／创造升级各走现有公式；离心机 STACK 与 PB 生产力组合影响单通道并行，JDTE 改变虚拟时间，不能将二者重复乘算 |
| 基础离心机 | `MekCentrifugeTickHandler.runTick` 依次协调父类 SMELTING、PB 独立处理、AE2 拉取／推送和补电；查配方的优先规则与 PB pending 一起核对，接管后不能继续调父类以维持“活跃” |
| 标准离心工厂 | `AbstractMekCentrifugeFactory` → `AbstractMekCentrifugeFactoryJdteSupport` → `FactoryUpgradeStateHelper`；普通入口和 coalesced flush 都会进入 super／SMELTING、各 lane PB、AE2 与能量回填；另有多流体空槽回收 |
| Extras／EMExtras 离心工厂 | 各自 `onUpdateServer`／`productivebeesgenesis$runTick`／`flushAcceleratedTicks` 是独立入口，拥有自己的 Mekanism 附属父类；共享 PB 算法不能替代对这些入口的模式隔离 |
| Mekanism 父类 | `TileEntityMekanism.tickServer` 在子类前执行 frequency、upgrade、chunkloader；`TileEntityConfigurableMachine.onUpdateServer` 执行 ejector，electric machine／factory 父类继续填充能量槽、排序／处理 recipe monitor。D11 必须逐项保留生命周期或代理修改，不能假设子类提前返回覆盖所有副作用 |
| 能耗聚合 | `MekCentrifugeEnergyScaling` 在并行超过 16 后采用阶梯计费：基础成本 100 时，两条 17 并行通道合计 3400，而把它们错误合成一条 34 并行通道只计 1800。先逐 lane 计算再汇总，批处理必须保持此差异 |
| 离心 committed pending | `PbRecipeFlusher` 按实际接受量扣减输出；外部部分插入后只扣一次输入，剩余结果经 `PbRecipeCompleter.saveCommittedPending` 持久化，排空前不开始下一批。接管不能重新抽样或再次扣料；尚未消费输入的临时计划与它分开处理 |
| 蜂箱未生成结果的 pending | `BeeSlotTickProcessor.pendingProductions` 为私有内存数组，按批量 flush 结算；`ApiarySlotSerializer` 保存蜜蜂与进度，`ApiaryNbtSerializer` 保存已生成物品缓冲和 pending 流体，均未保存此数组。当前保存边界存在待核实／修复的产出损失风险；D01 记录该事实，不把它当成新网络可继承的保证。D12 必须排空或显式迁移，并覆盖重启测试 |

新增 `ApiaryProgressBaselineTest`：直接调用真实推进入口，用固定种子覆盖 250×20 批次、基础周期不反馈污染、速度变化、创造模式与默认周期；其中随机 stack 参数测试方法边界，不意味着蜂箱支持安装 STACK。新增 `PbProcessingBaselineTest`：2,000 组固定种子场景对照独立逐 tick 解释器，并检查并行阶梯、通道汇总和缺电暂停。既有基因／工作条件／升级公式／批次账本／虚拟 tick／AE pending 测试继续作为回归集。

专服夹具由 `gradle/d01-baseline.gradle` 管理，显式命令为 `./gradlew.bat -Pd01Baseline=true -Pd01CodeRevision=f76a917 runD01Server --no-daemon --no-configuration-cache`。仅使用已经接受的 `run/eula.txt` 与本地依赖副本，在 `build/d01-server` 建立独立世界；检测到已有世界就拒绝覆盖，重跑前将旧测试目录改名保留。默认构建不创建该源集，发布 JAR 校验禁止出现 baseline 包。启动返回码不足以证明成功，任务还校验两份结果的生产标志及 `D01_COMPLETE`。

场景为固定种子 17092026、超平坦、晴天正午、关闭自然刷怪与随机刻、无玩家／无 GUI、强制加载夹具区块，使用新世界默认 BASIC 平衡预设。普通场景是 16 对基础蜂箱＋相邻基础离心机，共 48 蜂位；IO 场景是 64 对 ultimate 工厂，共 1,280 蜂位，每对独立供电 ME 网、真实 256k 物品／流体元件、直接逻辑连接、蜂箱输出到 AE2、离心机主动拉取。各蜂位填充铁蜂，基础周期 1200，首个真实喂食槽放一个铁块；普通 productivity.normal，IO productivity.very_high。后者两种机器各装 SPEED 8、ENERGY 8，蜂箱调用批量安装请求 TIME 8／TIME_2 8，**实际 NBT 为 TIME 4、TIME_2 0**，符合 BASIC 的上限与互斥，生效 occupation period 为 75 tick。夹具在每 tick 前补满机器 FE，关闭机器 AE 补电以防回填掩盖消耗；ME 自身由创造能量元件供电。磁盘默认配置与运行时 AE 补电覆盖分别记录，不能仅用 TOML 中未选中的自定义参数推断实际能力。

每场景预热 1200 tick（另留 20 tick 初始化），采样 2400 tick，采样间隔 4 ms，切场景前留 200 tick 写盘。JSON 保存逐机完整 NBT、前后元件精确键库存、物品数／流体 mB 分开记账的 IO 计数、FE 消耗、依赖版本及事件计时分位数。`ServerTickEvent.Pre→Post` 计时排除夹具补电／快照，**不是完整 MSPT**；完整 tick 健康指标另由 SparkMCP 读取。启动环境中 EMExtras 缺少未安装的其他工厂附属对应配方，产生加载错误；保留原始日志，已运行的基础／ultimate 铁蜂场景与这些缺失配方分开核验，不据此声称附属组合全兼容。

最终夹具复测于北京时间 17:13–17:20 完成，采样期间未并行构建。Windows 10、i7-10875H、Java HotSpot 21.0.9、最大堆约 4074 MiB；使用 Spark 1.10.124 的 Java 采样器。场景 JSON 位于 `build/d01-server/results/`，原始 profile 位于 `build/d01-server/config/spark/`；SparkMCP 工具返回值与夹具源码／配置 SHA-256 也保存在 results 目录。前一轮夹具联调结果留在 `build/d01-server-pilot-20260917-1706`，不用于下表。

| 指标／证据 | 普通 16 对基础机 | IO 64 对 ultimate 工厂 |
| --- | --- | --- |
| 原始 JSON | `normal-16-base-pairs.json` | `io-64-ultimate-pairs.json` |
| Spark 文件 | `profile-2026-09-17_17.16.54.sparkprofile` | `profile-2026-09-17_17.20.05.sparkprofile` |
| SparkMCP profileId | `347b17ce4af6` | `51b1bfb34db9` |
| 服务器线程采样窗口 | 17:14:54–17:16:54，约 119.924 s | 17:18:05–17:20:05，约 119.972 s |
| 事件计时平均／p95／p99／最大（ms） | 0.367／0.512／1.603／27.733 | 1.416／1.901／2.752／13.984 |
| Spark 最近 1 分钟 TPS | 20 | 20 |
| Spark 最近 1 分钟 MSPT 平均／中位／p95／最大（ms） | 0.34／0.27／0.38／27.72 | 1.49／1.36／1.95／39.68 |
| 采样内机器实际耗能（FE） | 1,440,000 | 322,560,000 |
| 生产核验 | 离心机输出槽总物品从 0 增至 150；逐机 NBT 保存配方、输入、流体及进度 | 64 个元件均有执行存入／提取／流体写入；原铁净增 25,385、蜂蜡净增 46,080、蜂蜜净增 14,848,000 mB |
| 元件 insert／extract／枚举调用 | 未挂载测量元件 | 34,737／2,048／28,672 |
| 元件执行存入物品／取出物品 | 无 | 235,305／163,840（包含蜜脾中间流转，不等于最终产物数） |

两个 profile 均已调用 SparkMCP 的 `get_summary`、`get_health`、`get_sources_breakdown(excludeNative=true)`、`get_top_self_time`、调用树搜索与 `diagnose`。IO 调用树中 PB 处理累计采样约 828 ms、蜜蜂 tick 264 ms、AE2 主动拉取 168 ms；这些是含子调用的采样时间，不能相加当总 CPU。来源 self time 中本模组为 2124 ms、AE2 为 380 ms，但本模组类别还包含测试夹具补电的 576 ms。普通场景分别为 260 ms、372 ms，夹具补电占 180 ms。当前轻负载、4 ms 采样分辨率下不据这些数值宣称某条路径已成为瓶颈。

工具返回的 `excludeNative=true` 结果仍包含 `Unsafe.park`，因此其百分比／activeMs 不能当作排除空闲后的 CPU 占比。GC collector 信息缺失，不能把返回的零值解释为没有 GC。`diagnose` 的普通场景尖峰提示使用了含预热／初始化的 5 分钟最大值 110.47 ms，IO 的 5 分钟值也跨场景；上表明确使用最近 1 分钟统计，不混称为整段 2400 tick 的分位数。自动 JVM 参数建议缺少本次 GC 证据，不据此改服务器参数。

验证结果：新增 8 个行为测试通过；全量测试统计为 636 个，634 通过、2 个既有 Skyhive 条件用例跳过、0 失败／错误。`test build verifyReleaseArtifact` 通过，发布 JAR 未包含 baseline 包；最终 `runD01Server` 完成两个生产校验并正常停止，无遗留测试服务器进程。尚未测试的新网络、客户端与兼容组合仍按后续阶段验收，不能把 D01 完成标记为整个 P0 或网络功能完成。

这些是 D01 的短时现状基线，普通和 IO 两场景数量、升级与拓扑不同，不能互作性能 A/B。它不覆盖共享大型 ME 网络、电缆路由、多人终端、全部特殊蜂种、Extras／EMExtras 每级工厂、JDTE 64×／256×、保存故障或客户端；后续按 [10.2](bee-processing-network-design.md#s10-2)–[10.3](bee-processing-network-design.md#s10-3)、D26／D29／D30 补齐同吞吐的长时对照。当前没有新网络性能收益结论。

<a id="s10-5"></a>
### 10.5 D02 数量、AE2 与 SavedData 原型（2026-09-17）

本轮实现位于 `src/storagePrototype/java/com/ayoshiko/productivebeesgenesis/storageprototype`：`amount` 为无 Minecraft／AE2 依赖的候选数量表，`bench` 为独立 JVM 基准，`compat` 为真实游戏 API 探针。测试位于 `src/storagePrototypeTest`；`gradle/d02-prototype.gradle` 只在 `-Pd02Prototype=true` 时启用。默认构建不包含这些源集，发行包检查禁止 `storageprototype/`，原型不能接管正式世界。

**数量实验。** A `SparseAmountStore` 使用 primitive long 投影表与稀疏 BigInteger 实值表；B `IndexedAmountStore` 使用键→int 索引、4096 元素 long 分段和稀疏大数，零键内部槽位可复用；C `BigAmountStore` 全量使用 BigInteger。均独立编写，统一非负实值、零键删除、long 投影与任意精度语义。B 是数量布局探针，没有生产级稳定作业句柄／generation／编码缓存，不能将结果当成 Thunderbolt 完整索引引擎的测评。三者共用单线程所有权，正常 long 增减的 A／B 不分配 BigInteger。

`testD02` 的两个模型测试对三个后端分别执行 20,000 次固定种子增减／赋值，并与独立 BigInteger 账本核对真实量、投影、键数和最终枚举；另测 63／126／180／1024 位边界、回收复用及负数拒绝。实际 AE2 的纯模拟、物品／流体、组件区分和生命周期由专服断言补充，不以纯数量测试冒充接口验证。

`runD02Benchmarks` 顺序启动三个独立 JVM，轮换候选顺序。每个测 1千／1万／10万／100万键；long、99% long＋1% 180 位、全 180 位、63／126 位跨界；均匀与“90% 请求命中 1% 热键”；只读探测、增加 1 后提取 1、临时新键插入删除、枚举、精确数量快照排序。每个热路径案例预热 3 批、测量 5 批、每批 32,768 次，共 1,440 条记录。原始文件为 `build/reports/d02/amounts-{0,1,2}.json`，记录 ThreadMXBean 分配量、批次计时和 GC 后近似堆差值；`probe` 是纯只读数量查询，实际 MEStorage SIMULATE 另测。

下表为百万键时三个 JVM 的中位数。pair 是一组“增加＋提取”的耗时，不是单个方法；内存只计算数量表增量，排除预构造的合成 Key。最后一列是全 180 位实值场景，其余为 99% long＋1% 180 位。

| 候选 | 均匀 probe（ns） | 均匀 pair（ns） | 热键 pair（ns） | pair 分配（B） | 近似表堆（MiB） | 全大数 pair（ns） |
| --- | --- | --- | --- | --- | --- | --- |
| A 稀疏 long＋BigInteger | 76.5 | 186.8 | 58.2 | 约 1.5 | 28.89 | 389.1 |
| B 分段数组＋稀疏大数 | 141.7 | 290.8 | 97.2 | 约 1.5 | 28.56 | 495.5 |
| C 全 BigInteger | 158.0 | 362.0 | 200.4 | 约 128.3 | 81.33 | 329.5 |

普通 long 的 A／B pair 分配约 0 B；全 180 位三者约 160 B；反复跨越 long 边界的 A／B 约 192 B。全大数时 C 的结构更简单且更快，但 long 占主导时分配和内存代价明显。百万 mixed 键的枚举中位耗时 A／B／C 为 30.92／57.69／48.78 ms，精确快照排序为 730.03／773.46／734.87 ms。D21 必须使用有预算的查询与增量索引，不能在每次打开终端时整表排序。

**暂选 A，保留替换边界。** 当前证据不支持先上更复杂的数组索引；全大数虽显示 BigInteger 分配成本，但尚无真实热账本比例证明有必要实现可变分肢中间层。D04 先实现易审计的稀疏方案，D28 再按真实作业、缓存与保存分布决定。上述是探索性微基准，不是 JMH 置信区间；p95 为五个批次的分位数，非每次操作尾延迟，小规模结果还受 JIT 影响。合成键不能代表完整 DataComponents 的哈希／比较成本，不能据此宣布某参考模组整体最快或宣称 MSPT 改善。

**真实 AE2 19.2.17 验证。** `NetworkProbe` 在独立专服的已供电真实网格挂载 MEStorage，通过网格库存双向执行，验证精确金锭键及其组件变体、拒收石头、蜂蜜流体单位、SIMULATE 不改量／revision／dirty、跨 long 的两次实际存取、暂停／恢复、卸载／重挂、缓存刷新。准入为测试专用的有限精确键集合，完整蜂业资格发现仍是 D05。终端 GUI、合成取消、真实断电与通道重建不在本探针验收范围内。

探针还挂入真实 256k 原生元件，复现 Long.MAX_VALUE＋1 聚合为 Long.MIN_VALUE；交换挂载顺序后，由本桥最后执行安全累加可得到 Long.MAX_VALUE。底层精确库存未受影响。此事实明确了 D20 的兼容阻碍，详见 6.2；D02 没有偷偷更改 AE2 聚合器，也未以显示窗口充当库存容量。

**持久化与故障。** `PrototypeSavedData` 用真实 SavedData 保存 AEKey 的组件编码和 BigInteger 字节数组，再由新建的 DimensionDataStorage 实例读取，避开内存缓存假通过；校验类型数、精确总量与边界抽样。组件键由金锭＋不同 `d02_variant` 构造，1% 数量为 180 位，其余为 long。这是存储规模测试，不表示所有这些人为组件都已通过正式蜂业准入。保存语义是正常世界快照；微基准不包含逐次 fsync，不与 ECO 逐操作强制日志混比。

最终专服复测采用 Java 21.0.9、NeoForge 21.1.214、4 GiB 最大堆，数据见 `build/d02-server/results/d02-api.json`，测试域文件在 `build/d02-server/prototype-data/`。以下为单次最终复测，成本含该环境的 JIT／GC 与文件系统缓存，不能当作稳定 p95；此前成功复测也显示百万键主线程约 4–5 秒的同级成本。

| 精确组件键数 | 主线程编码＋深复制（ms） | 保存并等待后台完成（ms） | 新实例加载（ms） | 压缩文件（bytes） |
| --- | --- | --- | --- | --- |
| 1,000 | 36.71 | 45.17 | 70.65 | 6,234 |
| 10,000 | 145.34 | 188.46 | 188.61 | 68,899 |
| 100,000 | 435.36 | 697.13 | 1,070.59 | 813,194 |
| 1,000,000 | 4,983.00 | 7,437.03 | 9,729.03 | 9,139,158 |

百万键数量＋真实 AEKey 的近似常驻增量为 387 MiB，重新加载后约 495 MiB；它与上表合成 Key 被排除的微基准内存不是同一口径。整体保存的主线程快照、复制和一次性解码均已有明显成本，因此 D09 的分段快照与 LOADING 状态必须先验证，D28 负责进一步优化，而非届时才第一次处理大域保存。文件体积依赖键熵和压缩，不能由本例 9 MB 推断任意一百万组件都只占 9 MB。

故障断言覆盖：已绑定域缺文件、截断压缩流、未知 schema、负数、重复键、缺失注册内容／AE2 占位键、写入父目录被文件阻塞。严格读取拒绝失败域并逐字节验证原文件未被覆盖；另验证同文件第二次保存能恢复更新后的精确大数。原生 `computeIfAbsent` 的空域回退和异步写失败后 dirty 已清除均已实测，故障后原型显式重新标脏。它只是证明 D09 必须实现保存回执与自动重试，尚未实现生产级恢复状态机、并发 checkpoint 或跨模组原子保存。预期故障日志已保留，不能把这些错误日志当作整轮失败。

验证入口为 `./gradlew.bat -Pd02Prototype=true testD02`、`runD02Benchmarks`、`runD02Server`，长任务使用 `--no-daemon --no-configuration-cache`。独立服务器目录存在世界时拒绝覆盖，联调目录改名保留。D02 专服报告 `passed=true`；D02 模型测试、既有全量测试、构建与发行包检查均通过，启用原型构建时 JAR 也未混入原型类；维护 worktree 的独立测试／构建也通过。D02 没有采集机器 MSPT 对照，不复用 D01 的 Spark profile 冒充新网络收益。

<a id="s10-6"></a>
### 10.6 D03 能力快照与逐通道数学（2026-09-17）

新增正式领域包 `apiculture/capacity`，只依赖 Java：`MemberCapabilitySnapshot` 保存成员身份、能力版本、机器 ID、原位置、在线状态、蜂位／通道与替代作业能力；`WorkKey` 区分蜂种／配方、配方版本和上下文；`WorkCapacity` 保存 q、t、单操作价格、满载单通道能耗、产量倍率、稳定性及实际升级效果。所有嵌套列表／映射作不可变复制，不持有 Level、ItemStack 或可变升级组件。

`CapacityPoolIndex` 从显式给出的成员快照构建，拒绝重复 memberId；仅 ONLINE 成员计入工作能力，OFFLINE／REDSTONE_PAUSED 仍保留拥有的蜂位与等量喂食槽。索引按完整 WorkCapacity 分组，并保存逐成员的版本和通道贡献，不创建一台平均机器。`ExactRate` 用约分后的非负 BigInteger 分数精确汇总吞吐，能耗总计也使用 BigInteger；不同配方的汇总分开查询，不提供误导性的“所有配方吞吐总和”。构建由后续拓扑／能力变更边界驱动，D03 没有增加每 tick 全网扫描。

`VirtualLaneState` 保存成员、能力版本、通道号、完整能力与周期余数，提供 `advanceFunded` 的纯数学结果。调用前必须已获得此通道满载所需的物料和能量预算；本步仅验证整数周期与计费，不实际预约或扣料。大时间预算用除法／余数处理，不按虚拟 tick 循环。离线不推进、不记录补产信用；能力版本、内容或通道身份不同则拒绝套用。D12／D15 再实现老工作段排空或迁移，不能把旧在制作业直接重算成新速度／电价；部分供料、供电不足、输出受阻的调度仍使用后续独立路径。

`apiculture/compat/MachineCapacityReader` 是显式主线程读取适配器，仅接受本模组已加载且仍位于原位置的真实 BE；拒绝客户端、后台线程、移除成员和外部未适配方块。蜂箱读取实际蜂位、`ApiaryUpgradeHandler` 的时间／产量／创造状态、能源容器以及完整 PB／MEK 升级数；喂食转化开关和实际平衡预设一并进入效果签名，避免不同生产行为误入同池。按原有 occupation 舍入公式得到周期，默认查询用配置周期回退。离心机通过 `PbRecipeContext` 读取真实进程数、operationsPerTick、PB 并行、配方时间、产量与稳定性，先应用既有 maxOps 限制，再乘 PB 并行，最后调用现有 `MekCentrifugeEnergyScaling` 按单通道计费。JDTE 不在此处再次乘并行。

适配器只查询显式请求的蜂周期／普通 PB 配方，不每次枚举全部配方。蜂周期查询表示同一上下文下的潜在能力，尚不证明每个蜂位已放蜂或满足花朵／基因／天气；普通 PB 适配也不冒充 Myriad 动态战利品、SMELTING 或全部特殊路径。新能力版本读取当前配置，旧独立机内部短时缓存可能稍后刷新；网络接管与配置变更必须在统一调度边界协调，不能跨版本混用。缓冲容量通过 `factoryBufferLimits` 从现有不可变配置服务单独读取，不影响吞吐。

新增 8 个 JUnit 行为用例：1.7 份／tick 异构实例、在线／离线蜂位及喂食槽、配方替代用途和版本、重复身份、不可变快照与不同产物效果分池、超 long 汇总、500 组固定种子批次与逐 tick 参考、升级／离线／极大预算。两条 17 并行通道的 3400 FE 计费反例继续保留，未改为一条 34 并行的新优惠。既有 D01／生产测试共同验证被复用的原始公式。

实际专服探针位于独立 `src/capacityProbe`，由 `gradle/d03-capacity.gradle` 显式启用。分别用 `./gradlew.bat -Pd03Probe=true runD03Server --no-daemon --no-configuration-cache` 和追加 `-Pd03WithAe=true` 验证；原始报告分别保存到 `build/d03-server-no-ae/results/d03-capacity.json` 与 `build/d03-server-ae/results/d03-capacity.json`。不接触 run 世界，已有探针世界拒绝覆盖。对基础／ultimate 蜂箱与离心机采集前后的完整保存 NBT 相等，证明快照读取不改机器权威数据；安装升级后旧快照仍保持原值，新快照反映真实安装数。

| 真实机器 | 蜂位／通道 | 升级前周期 | 升级后周期 | 每通道周期操作数 | 满载每通道 FE/t |
| --- | --- | --- | --- | --- | --- |
| 基础蜂箱 | 3 蜂位 | 1200 | 75 | 1 个蜂周期 | 100 |
| ultimate 蜂箱工厂 | 20 蜂位 | 1200 | 75 | 1 个蜂周期 | 60 |
| 基础离心机 | 1 通道 | 300 | 30 | 128 份铁蜜脾 | 1900 |
| ultimate 离心工厂 | 9 通道 | 300 | 30 | 128 份铁蜜脾 | 1900 |

以上为默认 BASIC，四台均安装 SPEED 8／ENERGY 8；蜂箱请求 TIME 8、实际安装 4，离心机额外 STACK 3＋PRODUCTIVITY 4。铁蜂周期查询基数为 1200；离心机使用实际铁蜜脾配方。验证还将 maxOps 临时置 1，确认只约束 MEK 并行分量、保留 PB 并行，并在 finally 恢复；喂食转化开关变化会产生不同的能力签名；移除 BE 后禁止继续读取。两个专服运行仍包含 D01／D02 已记录的可选工厂缺配方／未安装 Generators 的诊断，不将其解释为整个附属矩阵已兼容。

验证结果：D03 8 个新用例通过；全量 644 个用例中 642 通过、2 个既有 Skyhive 条件用例跳过、0 失败／错误。`test build verifyReleaseArtifact` 通过；最终有／无 AE2 专服报告均为 passed=true，正常停止。两份报告和源码摘要保存在 build 中，主要结果固定于本文，临时世界和日志不提交。

领域类与只读适配器进入 main 源集，**没有挂到注册、生产 tick、菜单、网络桥或所有权服务**；独立机仍走现有路径。探针类被发行包校验排除。D03 未测客户端 UI、每级 Extras／EMExtras、所有特殊蜂种、JDTE 时序或游戏性能收益；它们分别留在 D13–D16、D25–D26、D30。D03 之后的产物键和精确数量已由 D04 完成（见 [10.7](#s10-7)），仍不能因已有能力数学便提前启用接管。

<a id="s10-7"></a>
### 10.7 D04 完整组件键与精确数量（2026-09-18）

前置 D02 已有候选测量与通过的 API／保存报告。D03 在核对 10 份源码和两份专服报告 SHA-256 后提交为 `8d2e4f0`，随后以 `a5c9e96` merge 维护提交 `62d646b`，没有重复 cherry-pick 或改动维护目录。D03 提交前 `test build verifyReleaseArtifact` 通过；644 个测试中 2 个既有条件用例跳过。

D04 实现 `ProductKey`、`ProductKeyCodec`、`ProductAmount` 和 `SparseProductAmounts`。键以物品／流体类型、注册 ID 和完整组件编码组成，对嵌套 NBT 做输入与输出防御复制，哈希只用于分桶；数量不属于身份。恢复时保留默认组件的显式移除。NeoForge 21.1.214 的 `DataComponentMap.CODEC` 会跳过 transient 组件，因此适配器提前拒绝无法无损持久化的组件，不静默删除组件后合并变体。未知注册项解码失败由调用方隔离，不能按空物品处理。

常见数量走 primitive long 表，只有超界键进入稀疏 BigInteger 表；严格减法拒绝负余额，外部提取返回实际可取量，饱和投影从不回写。回落 long 后降级，归零回收，不设置类型／字节配额。D04 不暴露数组槽号；稳定键引用在归零和哈希槽复用后仍区分原产品，D06 预约使用独立事务身份，不能缓存内部哈希槽位。

本轮实际读取本地 EAEP `InfinityDataStorage` 的升降级／模拟路径、AE2 `AEItemKey/AEFluidKey` 的身份实现及本项目 NeoForge 源码。实现独立编写，没有引入 AE2 依赖，也不采用参考代码中坏记录直接跳过的持久化行为。6 个 JUnit 用例包含 30,000 次固定种子随机操作、256／512 位数量、负数／零／long 边界、同哈希不同组件、嵌套数组隔离和归零后引用。D04 专服验证真实金锭组件、改名、移除默认组件、PB 两种蜂型蜜脾及带组件流体的往返；无 AE2，`build/network-probe-d04-v1/results/domain.json` 为 passed=true，正常停服。

D04 提交前全量测试 658 个，失败 0、错误 0、既有条件跳过 2；`test build verifyReleaseArtifact` 通过。

共享开发源集 `domainProbe` 仅由 `-PnetworkDomainProbe=true` 启用；后续步骤复用 `runNetworkDomainServer`，通过新 `-PnetworkProbeRun=<步骤与轮次>` 隔离结果，已有目录拒绝覆盖。发布校验排除探针包。以上是领域守恒和实际 API 验证，不表示网络生产、事务持久化或性能收益已完成。

<a id="s10-8"></a>
### 10.8 D05 版本化产物资格（2026-09-18）

前置 D04 `e795381` 已通过完整构建和真实组件往返。新增 `AllowedProductDescriptor`、`ProductPolicySnapshot/Registry`、`DynamicProductRule` 与 `PbProductPolicyCompiler`。静态资格按去除明确外观字段后的完整组件索引，原始库存键仍保留全部组件。只允许 custom_name／item_name／lore 外观变体，蜂型、未知附加组件必须匹配模板；容器、bundle、实体／方块实体内容、弹药及蜂内容默认需要专用适配。正负缓存有界，随 policyRevision 原子清空；动态发现绑定适配器、精确键和版本，纯谓词异常／重入不能修改策略。没有全局静态世界缓存。

本轮核对 PB `AdvancedBeehiveRecipe` 的蜂型注入、ProductiveLib `TagOutputRecipe` 的真实优先输出和 `BeeProduceQueries` 的首配方选择。编译器仅展开有效蜂种的实际直接产物、已支持的蜜脾块转换和一次离心；复用 `CentrifugeRecipeIndex` 的原生／派生蜜脾块语义，不递归普通合成、不把整个输出标签签成资格。没有成功解析的来源返回诊断，未来接管须用诊断决定支持范围；本轮默认探针诊断为空。

4 个新增 JUnit 用例覆盖外部金锭、外观变体、错误蜂型、未知组件与容器、正负缓存重载、精确动态发现与旧版本拒绝、适配器异常／重入。真实专服 `build/network-probe-d05-v1/results/domain.json` 编译 356 个描述符，铁／金／钻石实际蜜脾及离心物品／流体均通过，改名外部产物可存、嵌套命令方块和未知蜂型被拒绝；替换空策略后旧资格失效，passed=true，正常停服，无 AE2。

D05 提交前全量测试 662 个，0 失败／错误、2 个既有条件跳过。

领域策略尚未挂到运行时重载事件或外部存取入口；D06 统一使用它，D27 再接真实重载屏障。特殊动态蜂种只提供适配／发现契约与测试，不宣称 D25 的 WannaBee、万象动态战利品或基因升级全部支持。旧库存提取不受新准入资格约束，由 D06 验证；D09 必须持久化发现表和旧付费计划的资格证据。

<a id="s10-9"></a>
### 10.9 D06 预约、提交与外部转移（2026-09-18）

前置 D04／D05 已提交并通过验收。`ProductLedger` 独占精确余额和 `ReservationBook`，绑定创建线程；每个操作及策略／外部回调有重入守卫。`LedgerTransaction` 用不可伪造的所属账本身份和独立 UUID 标识，输入／输出为不可变精确量；提交先算完全部变化再应用。重复提交不重复扣料／出货，终态从活跃表回收，仍持有的句柄保留结果。可同时待办事务数是工作预算，不是产物容量。

SIMULATE／canPrepare 不分配事务身份，不改变账本 revision、数量或预约。预留输入不可再被提取。未付费旧策略计划取消并归还预约；已确认付费的计划保留原核验输出，重载后仍可结算一次。`markPaid` 只记录已确认事实，不代替真实 FE 扣除。历史库存可以提取，普通新存入仍须通过当前准入。

`TransferStaging` 以有限记录数和单次操作量限制未决外部工作。出口先将资产移入暂存，再调用一次目标；按返回的实际量归还剩余。入口只入账实际返回数量，若期间策略改变则保管已取得物品。异常或越界返回进入 UNKNOWN，出口资产不退回、入口不虚构余额，也不自动重试；仅可靠的原调用收据可 resolve。尚未接入实体端口、掉落、AE2 或正式世界。未知状态可能包含已经在外部的物品，隔离量不是可用库存，不能用它宣称跨模组原子性。

D06 提交前全量测试 672 个，0 失败／错误、2 个既有条件跳过。

本轮参考本地 AE2 `MEStorage` 的实际量和非负契约、现有 `PbRecipeFlusher` 的剩余量归属；独立实现不依赖 AE2 类型。10 个新增 JUnit 用例含 5,000 次固定种子账户／预约模型操作及 160 位余额，覆盖双重消费、幂等、纯模拟、旧付费计划、线程、部分接受、未知进出口、策略瞬变、越界返回和有限暂存。D09 必须持久化活跃事务、付费状态、暂存及确认凭证；D12／D23 才能接入真实机器／端口和管理取回流程，不能把本步内存事务当作断电恢复保证。

<a id="s10-10"></a>
### 10.10 D07 三作用域和联合保留（2026-09-18）

前置 D06 `6d021a7` 已通过 672 个测试（2 个既有条件跳过）。新增 `ProductMatcher`、`ReserveLimit`、`ReservePolicy` 与 `GroupAllowanceAllocator`。加工、对外、外部来源为不同 scope；全局和规则层分别计算再取交集。每层 EXACT 例外从该层所有组回退中排除；未命中显式 EXACT／组规则的键才用该层 fallback。ALL 为枚举，数量不使用负值哨兵。

分配从 S−Q 出发，在同一快照上联合分配所有变体，组额度只扣一次。BASE_ITEM 包含同类型变体；BEE_TYPE 同时保留基础物品／流体 ID、蜂型和单位，蜜脾与块不会合组。返回 Plan 带 ledgerRevision 和 scope，仅为额度计算，不是实际预约；D08 必须在相同账本 revision 才能使用，不能缓存额度后分别给多位消费者重复花费。

实际读取本地 AE2LT `LayeredReservedStockPolicy`，借鉴分层约束和 EXACT 例外；其 stableKey 最后使用 hashCode，不能直接满足本项目碰撞下可重放的要求。核对本项目 Minecraft 1.21.1 `StringTagVisitor.visitCompound` 会递归排序复合键，本实现用完整类型／ID／带类型组件 SNBT 缓存排序键，不依赖哈希值排序。没有复制参考源码。

D07 提交前全量测试 678 个，0 失败／错误、2 个既有条件跳过。

6 个新增 JUnit 用例验证文档 1000−100−max(200,300)=600、两变体各 100／组保留 150 合计最多 50、同哈希稳定分配、EXACT 跨层例外、不同蜂型／蜜脾单位、ALL、200 位数量、多组交集、重复查询纯读及损坏预约拒绝。外部来源大于 long 的可验证性由 D21 的实际来源适配器决定；纯算法支持大数不意味着原生 AE2 能上报真实大数。

<a id="s10-11"></a>
### 10.11 D08 规则选择、原子预约与 P1 退出（2026-09-18）

前置 D03、D07 及其领域依赖均已提交。新增 `ProcessingRecipe`、`ProcessingRule/Index/Scheduler` 和 `WatermarkState`。按基础类型预编译配方与输入键／蜂型／标签／目标产物的候选，实际选择还必须匹配具体配方版本、上下文及在线能力。默认按优先级起始顺序做带权轮转，严格优先模式每次选最高可运行规则；缺料、无能力或保留阻塞不会让不可运行的高优先级永久挡住其他规则。水位低于 lower 才开始、达到 upper 停止，中间维持状态，可计入调用方提供的在途量。

选择器绑定所属账本；Selection 带账本／策略版本、规则 epoch 和不可变能力索引。`prepareAtRevision` 在同一临界区校验快照并预约，另一个选择不能复用已花费的旧组额度。重复 claim 返回同一事务；规则／能力替换后未预约选择失效。成功预约才消耗公平轮转份额，策略回调不能重入或替换调度器。结果键还须属于该配方声明的可能输出并通过当前产物策略；D15 仍要核验实际采样、数量、FE 和唯一 lane，D08 不执行生产。

P1 联调修正了一个组分配边界：组库存含当前规则不能处理的变体时，这些变体仍计入保留总量，但不预占本次可用额度；只为真实候选联合分配。另在最终审查中将 TransferStaging 重入守卫扩展到策略检查与结果归还整个操作，防止策略回调在结算时改变另一笔未知转移。都有行为回归测试。

本轮读取本地天枢 `InventoryMaintenanceDecision` 的滞回规则；使用精确 ProductAmount 并独立实现规则候选／轮转，不照搬轮询频率。D08 新增测试覆盖 2:1 公平、高优先缺料／ALL／配方版本不符、规则重叠及旧快照、标签和目标索引、在途水位、仅可加工变体分配、规则／能力失效、错误产物、调度及暂存重入、128 位水位和大数批次整除。

共享专服报告 `build/network-probe-p1-final-v1/results/domain.json` 为 passed=true：真实铁蜜脾、PB 配方与基础离心机能力能连接 D04–D08 的准入→保留→规则→预约→提交；1000 份测试账本输入准确扣除 8，预制物品／流体结果只入账一次，机器完整 NBT 不变。该夹具用预制结果测试领域契约，报告明确 `realProductionExecuted=false`；不代表实际生产、游戏产率、FE 或 MSPT 验证。无 AE2 启动已通过，既有附属模组缺配方与升级窗口位置不持久化诊断仍保留，不扩张兼容声明。

P1 最终验收：全量测试 687 个，685 通过、2 个既有 Skyhive 条件用例跳过、0 失败／错误；显式启用 domainProbe 的 `test build verifyReleaseArtifact` 通过，发行 JAR 不包含探针。本轮源码／报告／产物摘要保存在 `build/reports/p1/evidence-manifest.json`。D03 `8d2e4f0`、hotfix merge `a5c9e96`、D04 `e795381`、D05 `5d718c8`、D06 `6d021a7`、D07 `85cf6dd` 均为独立本地提交；本轮未推送或发布。

P1 验收时停在 D08；2026-09-18 已按用户要求将全部分支历史补入 Keep a Changelog 的 Unreleased 分类，并推送到 `095fd01`，随后开始 D09a。P1 验收时的“未推送”是历史状态。已付费标志由受信服务确认，不是客户端可写字段；外部结果未知必须依赖可靠原调用收据才能解除隔离。D09 必须为这些状态提供严格读取和恢复入口，不能只保存余额而丢弃预约或未知转移。

<a id="s10-12"></a>
### 10.12 P2／D09a 当前领域状态的持久化边界（2026-09-18）

本轮从已推送的 P1 开始，完成 D09a 的 P1 状态持久化实现。`LedgerCheckpoint` 与恢复入口保存活跃事务 UUID、输入／输出、RESERVED／PAID 和策略版本，加载时重建预约汇总并校验不超余额；旧账本签发的句柄不能操作新账本。`TransferStaging` 恢复完整方向、offer、held 和原调用身份，CALLING 统一转为 UNKNOWN，不能自动重试或退款。发现记录按策略版本和当前适配器重新验证，调度器保留禁用规则、滞回、轮转游标与权重进度。

`apiculture/persistence` 新增不可变 `NetworkCheckpoint`、分职责 codecs、`NetworkDirectory` 和 `NetworkSavedData`。checkpoint 同时包含网络／核心／所有者／代际、精确余额、交易明细、转移暂存、动态发现、成员能力来源、虚拟通道进度与规则状态。严格读取拒绝缺字段、错误 NBT 类型、未知 schema、未知注册内容／组件、非规范大数、重复身份、超额预约、孤立通道和未来策略版本；任一失败隔离整个域，保留原文件，不跳过坏余额后继续运行。目录另拒绝相同核心身份或权威位置重复建网。

世界目录只保存身份，每网一个独立 SavedData。首次创建要求新 UUID、新核心和空闲权威位置，先落完整空域，再发布目录；读取既有绑定不使用 `computeIfAbsent`。域损坏或缺失返回没有可写空账本的 RECOVERY 对象；手动修复文件后需显式 `reloadRecovered` 才重新解码，普通查询保持隔离。目录本身损坏则拒绝创建。未引用的异常创建残留不能通过再次 create 覆盖。

D09a 当时的 `AcknowledgedSavedData` 覆盖文件保存入口，在所属服务端线程捕获 revision 并编码，后台只接收字节并完成压缩、临时文件写入和原子替换；D09b2b 已进一步调整为不可变域的后台流式编码，见 [10.15](#s10-15)。成功回执推进 persistedRevision；旧保存完成不清除较新状态。失败保留 dirty、记录原因并有界退避；执行器队列有界，满载拒绝的任务继续保持待保存状态。正常世界保存发起请求，tick 只收回执及重试，不把每次产物变化映射为一次 fsync。`NetworkPersistence` 按需建立世界会话，正常停服 flush 等待并检查结果后关闭写线程。无 AE2 类型、客户端类型或新的玩家写入口。

新增 18 个行为测试，覆盖完整字段往返、不可变性、坏数据、重复身份、跨策略 RESERVED／PAID 恢复、UNKNOWN 隔离、动态发现恢复、调度进度、旧回执／新 revision、失败重试、显式创建、缺文件及损坏后原文件保留。自审补上了普通 tick 不主动保存所有新改动、native dirty 标志不能伪造回执、同核心／同位置建网冲突、通道能力与版本一致性等边界。

全量 `-PnetworkDomainProbe=true test build verifyReleaseArtifact`：705 项测试，703 通过、2 项既有条件用例跳过、0 失败／错误。独立专服 `-PnetworkDomainProbe=true -PnetworkProbeRun=p2-d09a-shutdown runNetworkDomainServer --no-daemon --no-configuration-cache` 覆盖真实注册表／未知组件、256 位以上数量、原生 DimensionDataStorage 保存、新目录实例读取、已付费结果单次结算及 UNKNOWN 不自动解锁；停服后额外读取正式世界 data 路径核对最后 checkpoint。证据位于 `build/network-probe-p2-d09a-shutdown/results/domain.json`、`build/p2-final-verification.log` 和 `build/p2-shutdown-probe.log`。探针不进入发行 JAR，未修改用户 run 世界。

**D09a 交付时的边界（P2 最终验收见 [10.21](#s10-21)）：D09 整体和 P2 当时尚未完成。** 目前持久化的是已经存在的 P1 领域状态；蜜蜂／有限供给／升级实物／真实能量账户和机器交接记录在 D12–D16 形成时必须加入同一权威 schema 和验证链，不能只保存余额。D09a 交付时 `NetworkCheckpoint` 的整表不可变复制及 NBT 编码都在主线程，读取也尚未预算化；后续余额冻结改进见 [10.13](#s10-13)，仍没有声称解决 D02 百万键完整保存停顿。D09b 必须完成预算化捕获／编码／加载和一致发布后才能进入 D10；D10–D12 的核心、拓扑、父类隔离与接管均未启用。专服验证不代表客户端、生产守恒全矩阵或网络性能 A/B 已通过。

<a id="s10-13"></a>
### 10.13 D09b1 不可变余额页与捕获成本（2026-09-18）

前置 D09a 为 `bd0f262`，工作区干净，继续在网络 worktree 开发。将 D09b 按可独立验收的责任拆为：b1 余额冻结／变更隔离，b2 全域预算化捕获／编码／写入，b3 预算化加载／恢复及整体验收。**本节只完成 b1，D10 仍依赖整个 D09b。** 不能因余额冻结已完成而宣称百万键文件保存／加载已预算化。

实际复读 DataEnergistics `4a33f128` 的 `TrinityDataCoreStorageSavedData.storageView/orderedEntries/save`、ECO `f26aab47` 的 `ECOInfiniteStorageData.save`，并用 javap 核对 Thunderbolt `2.0.0-beta.3` 的 `IndexedStorage`。DataEnergistics 的不可变 UI frame 建立在整表排序缓存上，ECO 原子替换仍先整表编码；Thunderbolt 使用键索引、primitive 数组、dirtyQueue 和 modCount。这些提供职责与数据布局参考，但都不能直接证明本项目的一致快照预算；没有复制第三方代码或引入 AE2 类型。

新增 `PagedProductAmounts`、`ProductAmountTrie`、`ProductAmountPages`。普通数量仍存 primitive long，只有超 long 的页项持有 BigInteger，降级及归零回收。普通键按每次 5 位哈希直接定位，每页最多 32 项；完整哈希碰撞转入同样有界的有序页树，以完整组件身份消解碰撞，不能复制一个无限增长的碰撞桶。冻结只保存根引用／数量并切换写令牌；后续写入复制受影响路径与页。零键不留墓碑，空分支和大数引用回收；对象没有全局快照缓存，旧页生命周期由实际持有的快照决定。

`ProductLedger.checkpoint()` 在原有线程／重入守卫内同时冻结余额与预约明细。`LedgerCheckpoint` 只对后端私有、已验证为正数的不可变快照免去重复复制；任意外部 Map 仍防御复制，重复事务和超额预约校验保留。恢复已有冻结余额可直接分叉，不共享写令牌；从文件解码得到的普通 Map 仍需逐项建立索引，属于 b3 的工作。查询 `Snapshot`、动态发现、规则、成员、暂存等捕获成本也不能从 b2 预算中排除。NBT schema 仍为 1，存档字段、严格拒绝和写入回执协议没有改变。

三次独立 JVM 微基准使用 1 万／100 万个合成组件键、1% 的 256 位余额；冻结后持续执行 10 万次固定种子变更，再逐键核对旧快照和新余额。`networkDomainProbe` 开关下新增 `runNetworkSnapshotBenchmark`，复用开发源集，结果文件拒绝覆盖。定稿实现的报告为 `build/reports/network-snapshot/d09b1-trie-0.json`、`d09b1-final-1.json`、`d09b1-final-2.json`，两种后端交替先运行；基准单独执行，不与 JUnit／专服并行。

| 百万键、空预约明细 | 原稀疏表整表捕获 | 不可变页捕获 |
| --- | --- | --- |
| `LedgerCheckpoint` 捕获时间 | 511–605 ms | 0.023–0.044 ms |
| 捕获线程分配 | 约 130 MB | 424 字节 |
| 冻结后每 100 次变更 P50 | 0.032–0.041 ms | 0.069–0.076 ms |
| 冻结后每 100 次变更 P99 | 0.072–0.087 ms | 0.114–0.190 ms |
| 10 万次变更线程分配 | 约 7.3 MB | 约 24.2–24.4 MB |

页方案消除了余额冻结时的整表暂停，但保留约 1.7–2.4 倍的该组变更中位成本和额外写时复制分配，不能称为所有操作都更快。测试期间曾采用完整有序页树，变更明显更慢，因而改为哈希定位；这项取舍由同数据测量支持。上表不包含 NBT 编码、压缩、磁盘、规则／预约捕获、真实配方负载或 GC 长稳，也不构成 MSPT／整体吞吐收益。小样本首次捕获含类加载／JIT，不能与预热后百万键数值直接做规模比值。旧 `SparseProductAmounts` 保留为数量模型和比较基线。

新增 6 项行为测试覆盖 60,000 次模型变更、多代冻结、1,024 个完整哈希碰撞、分裂／合并／删空重用、512 位降级、后台只读遍历、两份恢复实例隔离、普通 Map 防御复制、超额预约和 RESERVED／PAID 状态边界。自审与碰撞回归修复了删除时过早折叠哈希层级的问题，避免后续分裂把碰撞树当作普通叶页。领域 67 项测试全部通过；全量 `-PnetworkDomainProbe=true test build verifyReleaseArtifact` 共 711 项，709 通过、2 项既有条件跳过、0 失败／错误，日志为 `build/d09b1-final-verification.log`。

独立专服 `-PnetworkDomainProbe=true -PnetworkProbeRun=p2-d09b1-checkpoint-1 runNetworkDomainServer --no-daemon --no-configuration-cache` 通过，未安装 AE2。冻结后立即结算原账本，再保存并恢复冻结前 PAID 记录，余额／预约不混入后续结算；原生保存、严格组件验证、UNKNOWN 隔离及正常停服最终文件均通过。报告为 `build/network-probe-p2-d09b1-checkpoint-1/results/domain.json`，`passed`、`frozenCheckpointIsolatedFromLiveSettlement` 和 `normalShutdownCheckpointSaved` 均为 true；日志为 `build/d09b1-server.log`。探针未进入发行包，未运行网络真实生产、客户端或性能 A/B。

后续闸门保持严格：b2 必须让余额、预约、成员、规则、发现、暂存共享一次 checkpoint，分别记录主线程捕获、编码步长、队列积压、后台写入和回执；b3 才处理文件读取、注册表验证、跨记录校验及索引恢复。未全部通过前不进入 D10，不把加载普通 Map 的耗时藏在 READY 发布前的一次回调中。

<a id="s10-14"></a>
### 10.14 D09b2a 全域不可变捕获与前置核验（2026-09-19）

本轮保留并核验接入时已有的 D09b1／b2a 未提交实现，没有回退其他工作。`SnapshotRecords` 为元数据提供持久树根；预约、动态发现、转移暂存、成员／通道和规则滞回在变更时维护约束。`NetworkCheckpointSource` 校验同一账本、策略与所属线程后统一冻结全部 P1 域，公开构造／文件恢复仍逐项验证，不开放任意 trusted 入口。

补齐上一轮缺少的 [10.14](#s10-14) 记录，并复读 DataEnergistics `4a33f128` 的 `HostState.insert/extract/orderedEntries/invalidateView`。采用更新时维护索引、查询视图失效的职责划分，不照搬其整表排序缓存。ECO `f26aab47` 与本项目 NeoForge 21.1.214 的保存源码再次确认：后台写盘前整表构造／深复制仍会阻塞主线程，不能据此关闭后续闸门。

既有三次独立 JVM 报告 `build/reports/network-capture/p2-d09b2a-final-{0,1,2}.json` 均通过：百万余额键与每域 10 万条元数据，统一捕获 P50 0.0014–0.0022 ms、P99 0.0026–0.0046 ms，捕获分配 560 B；每 100 次跨域变更并捕获 P50 1.306–1.426 ms，1 万次变更累计分配约 65.7–65.9 MB。这些是已有记录的本轮核验，不冒充重新运行的基准。`build/network-probe-p2-d09b2a-capture-1/results/domain.json` 的全域冻结隔离、正常停服保存及 passed 均为 true；本轮进入 b2b 前重新运行领域测试通过。

<a id="s10-15"></a>
### 10.15 D09b2b 单任务流式保存与背压（2026-09-19）

前置 b2a 的领域测试和全域快照证据核验通过。实际实现调整见第 8 节：既然整个 checkpoint 不可变，纯编码可由后台读取，不必在主线程切割编码后再传字节。`CheckpointPayload` 封闭后台输入类型；`NetworkCheckpointStream`／`NbtStream` 负责标准 schema 1 NBT，`CheckpointFiles` 负责固定缓冲压缩与原子替换，`CheckpointSaveQueue` 负责名额、请求合并、轮转和回执。没有新增玩家设置或可选依赖。

原生 `save(File, registries)` 只登记请求和捕获引用；显式 `save(CompoundTag, registries)` 仍是同步物化的兼容接口，正式文件保存不调用它。显式 `create`／`flush` 仍等待实际 IO，仅用于当前开发夹具及需要耐久回执的边界；D09b3 须同时提供非阻塞的打开／创建发布过程，D10 的正常 tick 不得调用同步 `create`／`flush`，避免新建空域被队列中的大保存阻塞。所有维度共享一个未完成快照和 32 KiB Java 流缓冲；等待请求不携带旧快照，成功写完后才取得下一域的最新冻结值。tick 至多检查 32 个待办域，不遍历所有已加载网络。失败保留 dirty 并退避，旧回执只推进实际保存版本；停服等待现有任务并尝试保存最新版本，失败返回错误。工作线程不捕获目录／SavedData／注册表服务。

余额、事务输入输出、成员能力／效果、规则保留项、滞回和目录都逐项写入。modified UTF 使用固定流缓冲，超出 NBT 字符串格式边界时整次保存失败，不能静默写空。单个 ProductKey 的组件仍防御复制，大数仍按标准字节数组编码，这些成本发生在后台并与流缓冲额度分开计算。初次百万键样本显示后台累计分配约 1.2 GB，自审去掉每个余额键外层临时 CompoundTag 后，三次定稿运行约为 0.60–0.88 GB；没有新增全键组件缓存。

新增行为验证覆盖 schema 全字段与空域、4,096 项交易／保留及能力效果、1,025 项能力列表、1 MiB 组件与百万位大数、目录冻结、慢写请求合并与公平、执行器拒绝、部分编码失败保留旧文件、失败自动重试、真实后台读快照以及最新版本停服收尾。专服夹具改为跨真实 tick 等待原生保存回执，再留下新的未请求版本验证正常停服。

本步的规模基准只核对保存端：使用 1 万／100 万个合成组件键、每域 1 千／10 万条元数据，写入前持续变更 1,000 次，分别比较旧冻结源与最终冻结源的文件解压 SHA-256。文件摘要不能替代 b3 的注册表验证／索引恢复。三次定稿报告为 `build/reports/network-write/p2-d09b2b-final-{0,1,2}.json`，均 passed=true；初轮对照保留为 `p2-d09b2b-stream-0.json`。百万键样本首次主线程请求 0.087–0.250 ms、分配 1,736 B；实际写入期间队列 tick 的 P99 均约 0.0036 ms，最大 0.046–0.451 ms；每次后台编码＋压缩＋落盘约 6.39–7.40 秒，累计分配约 0.60–0.88 GB。三次峰值均为一个快照和 32 KiB 流缓冲。final-2 另测 1 MiB 随机组件＋1,000,001 位数量：单记录完整写入 5 次，最大约 35.98 ms、单次后台分配最大约 1.21 MB，摘要一致。它说明单记录仍有成本，不能将固定流缓冲等同于固定总内存。上述是合成存储夹具，不包含实际生产、客户端、注册表冷加载、完整 MSPT 或长稳 GC 结论。

最终验收：`-PnetworkDomainProbe=true test build verifyReleaseArtifact` 共 730 项，728 通过、2 项既有条件跳过、0 失败／错误；日志 `build/d09b2b-final-verification.log`。隔离专服 `p2-d09b2b-stream-server-1` 未安装 AE2，原生保存请求后 2 个真实 tick 收到最新回执；随后仅修改到 revision 3，正常停服最终文件仍正确。`build/network-probe-p2-d09b2b-stream-server-1/results/domain.json` 的 `tickDrivenStreamSave`、`normalShutdownCheckpointSaved`、`passed` 均为 true，日志为 `build/d09b2b-server.log`。发行包不含开发探针，未使用用户 run 世界、未推送或发布。源码／报告／JAR 摘要登记在 `build/reports/p2-d09b2b/evidence-manifest.json`。已有附属模组缺配方及升级窗口位置诊断仍保留，不扩张兼容声明。

**D09b2b 交付时的边界（P2 最终验收见 [10.21](#s10-21)）：该子步骤完成不等于 D09 或 P2 完成。** D09b3 的流式读取、注册表校验、恢复索引和非阻塞创建发布尚未实现；D10–D12 仍未启用。先验收这些依赖，再进入拓扑与接管。

<a id="s10-16"></a>
### 10.16 D09b3a 有界读取传输与完整文件检查（2026-09-19）

前置 D09b1／b2 已核验并提交推送为 `ec9f108`；接入时的无关 HTML 文件保留本地。提交前实际重跑全量 730 项测试，728 通过、2 项既有条件跳过，构建／产物核验通过；此前基准、专服和源码的 evidence-manifest 摘要逐项一致。

本步复读 DataEnergistics `4a33f128` 的 `readEntries/readAmount`、ECO `f26aab47` 的 `ECOInfiniteStorageDomains` 文件读取／隔离入口，并核对本项目 NeoForge 21.1.214 合并源码中的 `NbtIo`、`NbtAccounter`、`ListTag`、`StreamTagVisitor` 及三种数组 Tag。学习逐条装载、显式失败和所有者线程边界；不采用坏键／坏数量跳过、全域 readCompressed 或数组整块分配。原生 visitor 的数组回调仍先分配完整数组，HALT 也不是可恢复的解析游标；因此独立实现迭代容器栈和分块事件传输，未复制第三方实现。

`persistence.read` 中读取服务、会话、邮箱、NBT 语法解析和不可变事件分别承担职责。请求不在消费线程打开文件；后台独占文件及解析栈，邮箱满时等待消费，poll 不等待读盘。取消会丢弃待消费批次并中断背压等待，后台退出关闭文件；新请求只在前一任务退出且邮箱清空后准入。失效会话不能收到后续文件数据。数组长度先校验，再用 long 计算字节数并固定分片，负长度、未知类型、坏 UTF、过深嵌套、截断／CRC 错误及根后额外解压数据均失败，原文件不改写。完整语法通过后仍须继续 b3b／b3c，正式 `NetworkDirectory` 暂不切换到只完成传输的路径。

新增 12 项行为测试覆盖全字段 checkpoint／目录、全部 NBT 类型、嵌套列表、大数组、最大 UTF、重复字段保留、负长度、极大声明长度、坏类型／UTF、512 层边界、GZIP 截断／CRC、消费后才发现坏尾部、背压取消、显式修复重读、不可变事件、线程归属和停服交接。自审修复停服时从执行器队列移出的任务没有完成终态的问题，并增加 50 次交接关闭回归。全量 `-PnetworkDomainProbe=true test build verifyReleaseArtifact` 为 742 项：740 通过、2 项既有条件跳过、0 失败／错误；日志为 `build/p2-d09b3a-final-verification.log`。

三次独立 JVM 使用 `runNetworkCheckpointReadBenchmark`，512 MiB 堆，只读复用 `build/reports/network-write/p2-d09b2b-final-2.json` 对应已验收文件，结果为 `build/reports/network-read/p2-d09b3a-final-{0,1,2}.json`，均 passed=true。初次夹具选用了不含大记录的 final-0 报告，前两项摘要已通过但任务因缺文件失败；该日志保留为 `build/p2-d09b3a-benchmark-0.log`，不计入定稿三次。final-2 在停服交接修复后运行。

| 百万余额键、每域 10 万级元数据 | 三次读取测量 |
| --- | --- |
| 原文件解压字节／传输事件 | 257,367,256 B／25,081,048 个 |
| 请求耗时／所属线程分配 | 0.243–0.358 ms／1,512 B |
| poll P99／最大耗时 | 0.0053–0.0074 ms／0.474–0.681 ms |
| 后台读取与夹具事件复编码摘要总历时 | 7.64–11.00 秒 |
| 后台累计分配 | 约 2.154 GB（不是同时存活堆） |
| 队列峰值 | 8 批／261,280 B 估算事件占用 |

每次读取得到的原始解压摘要、消费端逐事件复编码摘要和原保存基准摘要一致，覆盖全部嵌套元数据，未先构造整域 NBT／Map。独立的 1 MiB 随机组件＋百万位数量样本也一致，后台累计分配约 1.23 MB。每个 JVM 首个 1 万键样本的请求包含类加载／建线程，耗时 3.59–6.23 ms，不能隐去这项首次成本；上述百万键请求已经预热。poll 仅为取批，**不包含后续注册表校验／索引恢复**；总历时还包含夹具事件复编码摘要，不能当成纯磁盘耗时。这些是新读取实例访问已存在文件，没有清除操作系统文件缓存，不是冷磁盘或完整冷加载实测。累计事件／字符串分配仍较大，b3b 须计入 GC 与组件 codec 成本，再决定是否需要紧凑事件或有界名称复用，不能先缓存全部组件。

隔离专服 `p2-d09b3a-read-server-2` 的 tick 读取／摘要核对、原生保存和正常停服最终 checkpoint 验证通过，未安装 AE2；报告为 `build/network-probe-p2-d09b3a-read-server-2/results/domain.json`，日志为 `build/p2-d09b3a-server-final.log`。自审将保存回执和读取完成的计时分开，首轮 server-1 只保留作回归记录。未运行客户端或网络实际生产；现有附属模组缺依赖／配方及升级窗口诊断没有据此扩大兼容声明。产物不含 domainProbe 类，源码／报告／JAR 摘要登记于 `build/reports/p2-d09b3a/evidence-manifest.json`。

**D09b3a 只完成文件传输；不是百万键冷加载、注册表验证、领域恢复或 D09 整体验收。D10 仍不得开始。**

<a id="s10-17"></a>
### 10.17 D09b3b／c 分段恢复与发布（2026-09-19）

`CheckpointDecoder` 在所属线程按事件数与软时间预算推进。`CheckpointSchema` 将领域列表逐条送入恢复构建器，余额、交易的输入／输出、动态发现、暂存、成员／通道、能力替代项、规则／保留和滞回均参与预算；只有单个产品组件或大数实体化为原生 NBT。跨记录验证逐步汇总预约和检查引用，结束时复用不可变根，不在最终回调重建整表。未知字段、重复字段／身份、未来版本、坏注册内容、孤立通道、超额预约等使候选整体作废。

`NetworkDirectory` 的打开和新建返回 `NetworkOpenHandle`。目录及域共用唯一读取器；加载期间 `ready()` 拒绝访问。新建依次等待域文件和目录文件的实际成功回执，只有两者都成功才发布。慢盘不会让普通 tick 等待 future；写失败沿用保存队列的退避重试。读取／配方重载代际变化会丢弃候选，显式重新加载可以重试修复后的文件；服务器关闭撤销句柄。尚未开始、且没有发布的空域创建可以取消，不视为已接管机器。

本地参考继续核对 DataEnergistics `HostState.putLoaded/readEntries` 和 ECO `ECOInfiniteStorageDomains`：采用逐条建立索引及会话级生命周期，不沿用坏记录跳过、未知域自动创建或同步整域读取。原生组件 codec 的执行、复制、哈希和大数操作不可抢占，单记录成本必须独立披露。实际注册表／配方运行时索引由后续作业适配层按当前版本建立，不可把已恢复 checkpoint 当作机器生产授权。

验证证据：`build/network-probe-p2-d09b3-server-1/results/domain.json` 在 NeoForge 21.1.214 独立专服（AE2 未加载）通过真实 tick 创建、保存、全域解码及正常停服落盘；丰富领域夹具在 10 个消费 tick 内恢复，单步最大约 0.405 ms。`build/reports/network-restore/p2-d09b3/restore-1000000.json` 在新的 NeoForge JVM 恢复一百万个金锭 custom_data 变体，每 64 个键包含一个超 long 数量；逐键精确比较、组件验证及原文件 SHA-256 全部通过。初次通过记录恢复耗时 10.508 s，工作段 p95 2.005 ms／p99 2.013 ms／最大 44.640 ms，单步最大 43.958 ms；所属线程累计分配 12,534,407,784 B，结束采样堆使用 1,410,451,072 B（不是峰值或强制 GC 后驻留量）。最终消除逐字段邮箱统计调用后，`restore-1000000-p2-d09b3-final.json` 记录 10.596 s、p99 2.017 ms、最大 42.040 ms、累计分配 12,532,503,568 B；差异不足以证明性能提升。全量 test／build／专服命令通过，结果在 `build/p2-d09b3-final.log` 与 `build/network-probe-p2-d09b3-final/results/domain.json`。

**验收口径修订：** 原文件摘要证明传输未改动字节；跨 JVM 恢复用逐键完整组件和精确数量核对语义，不要求重新保存后的字节摘要相同。当前 trie 使用包含枚举哈希的进程内键哈希，跨 JVM 的合法记录顺序可以不同；这不改变账户身份与数量，也不值得为了字节排序增加整域排序。上述百万键测量紧密推进预算工作段，未清空 OS 文件缓存，不是百万键实际 tick 延迟或 Spark/MSPT 证据。1 MiB 单组件、百万位数量、取消、重复原始字段、坏引用、双回执门控均有聚焦行为用例。D09b3 交付时 P2 仍需完成 D10–D12；最终验收见 [10.21](#s10-21)。

<a id="s10-18"></a>
### 10.18 D10 核心与只读拓扑（2026-09-19）

新增控制核心、只读结构菜单、核心连接面、服务端开关与预算项（同步原生 NeoForge 配置界面及中英文）。核心默认不接管机器。`TopologyScan` 使用单区块六面 BFS，`NetworkTopologyService` 为全服提供唯一 FIFO 扫描队列和节点／软时间预算；区块内多核心共用事件 epoch，放拆、邻居事件与区块变化立即使旧视图不可用。仅记录含核心的区块，停服清理会话，200 tick 的按区块巡检补偿遗漏事件，不强制加载成员区块。

本地复核 ECO `NEClusterCalculator` 和 Mekanism `TileEntityMekanism`：借鉴形成／失效生命周期，任意邻接图仍用有预算的 BFS，不使用定形包围盒扫描。修复了关闭面的节点首次被碰到就标记 visited 的陷阱：关闭入边不阻止其通过其他开放路径连接。只有同所有者成员贡献蜂位或进程；不同所有者不被自动接管，双核心显示冲突。菜单命令校验当前菜单、核心实例、距离和所有者，计数分成原生短字段传输后还原 long，避免 32767 截断。

`TopologyScanTest` 覆盖预算步长、单区块边界、斜角、异主成员、关闭面的替代路径、拆分和过期 epoch。`build/network-probe-p2-d10-1/results/domain.json` 在真实专服以全服每 tick 一个候选节点验证跨 tick 重建、真实蜂箱／离心机计数、双核心冲突、断开核心连接面以及拆机撤销能力；同轮 `test build` 通过。尚未检查客户端实际界面观感。托管隔离与交接由 D11／D12 继续实现。

<a id="s10-19"></a>
### 10.19 D11 托管入口与原生副作用隔离（2026-09-19）

`MemberBinding` 统一约束独立 ticker、JDTE 直接累计／flush、缓存能力代理、成员菜单及逐机 AE2 生命周期。拦截点位于 Mekanism `TileEntityMekanism.tickServer` 的父组件执行之前，绑定或目录占用存在时不执行 frequency、upgrade、chunkloader、熔炼、PB 生产和 ejector。网络目录尚在加载时暂停候选机器，避免旧 BE 没有绑定字段就先生产一 tick；返回必须先释放权威占用，再恢复独立能力和 AE2 节点。

`build/network-probe-p2-d11-4/results/domain.json` 与 `build/network-probe-p2-d12-3/results/domain.json` 覆盖 36 类蜂箱／离心机变体、256 次父 ticker 与 JDTE 委托、冻结前取得的 item／fluid／FE／strict energy 代理，确认状态不变且独立 ticker 能恢复。此处的工厂隔离不代表其资产迁移已获准，逐级迁移和升级矩阵仍归 D24。

<a id="s10-20"></a>
### 10.20 D12 真实资产与回执交接（2026-09-19，已验收）

`OwnershipTransferService` 以目录占用、域记录及当前 BE 引用组成身份链，目录新增位置／member／transfer 索引，域的 `OwnedMachines` 使用不可变记录与位置／transfer 索引。阶段顺序为目录 claim 落盘 → PREPARE → SEALED 域落盘 → 清空源并绑定 MANAGED → OWNED 域落盘。未通过预检的机器保持独立；外部 AE2 已接受但尚未扣减的欠账必须先在独立路径结清，不能冻结后永远等待普通 ticker。

逆向顺序为 RETURNING 意图落盘 → 检查空目标及容量 → 恢复实物并写 BE RETURNED 标记 → 等待原生区块写完成 → 域 RETURNED 删除资产并落盘 → 释放目录占用并落盘 → 删除 BE 绑定。RETURNED 域只保留指纹收据，不能再发放资产。BE 的 RETURNED 是恢复完成标记，不等同于 LEAVING；源／目标／代际无法核实时保留权威资产并隔离，不能因为核心缺失而恢复生产。

P2 的基础蜂箱／基础离心机使用有限迁移保管映像，记录原生物品槽、真实蜂笼与完整蜂位、原有喂食实物、PB／Mekanism 升级、有限储能、流体、输出缓冲和待产出状态。原有共享喂食板只保存一份真实物品及原布局；不复制成 H 份。D14 再把这些实物事务化分配到 H 个喂食槽，D16 建立统一有限供给／储能账户；P2 保管数据不执行生产，不提前把所有物品当合法无限产物。特殊蜂种和工厂即便已有隔离守卫，也必须等对应生产／资产适配验收；核心明确拒绝未实现的工厂资产适配器。

蜂箱新增 schema 1 的 pending 周期快照，保存逐位已付费周期、刷新计数与轮转游标，普通保存、物品保存和等级升级同步携带；无法解析或对应蜂位消失时保留原字段并暂停。离心只保存已经扣料的 committed pending 剩余物品／流体和已有进度，丢弃未扣料的暂存计划，不重新抽样或重复扣料。预检在未加入世界的同型 BE 上恢复并重新捕获，逐字段比较完整映像，拒绝原生 codec 静默裁剪、未知资产及容量不足。

本地参考了 Mekanism `TileComponentUpgrade.deserialize` 的升级重算及槽位恢复顺序、NeoForge 21.1.214 `ChunkMap.save` → `ChunkStorage.write` → `IOWorker.store` 的真实 future，以及 ECO `ECOInfiniteStorageDomains` 的 acquire／release 生命周期。交还复用原生区块写队列及 `ChunkDataEvent.Save`，不另开会覆盖新版区块的文件写入器；正常 tick 不 join 未完成 IO。单机 NBT 捕获、注册表 round-trip 和整个区块序列化仍是不可抢占工作：全服每 tick 至多 4 步、2 ms 软预算不是硬时长保证。收据属于正常保存完成语义，不宣称整世界断电原子事务。

核心已提供接管／整批交还菜单命令、身份引用保存和重建后的阶段恢复。所有者、距离、当前菜单和核心实例在服务端重查；复制核心坐标或代际不符时拒绝。结构改变会停止尚未开始的接管，已完成成员仍由原权威域持有；先交还再使用扳手、等级安装器或拆机，强制破坏的掉落路径也禁止再次实体化旧源库存。被强制移除或状态不明的机器保留资产进入恢复检查，自动补发与管理取回界面归 D23。

实测证据：`build/network-probe-p2-d12-3/results/domain.json` 完成真实蜂笼、已安装升级、喂食、储能、流体、两种 pending、BE 重建、核心命令调度／核心重建及复制核心拒绝。`build/network-probe-p2-d12-restart-write-1` 在 PREPARE、SEAL、OWNED、RETURN_INTENT、RETURN_WRITE、RETURN_RECEIPT、RELEASE_CLAIM、RELEASE_BINDING 各保存两类机器后正常退出；`build/network-probe-p2-d12-restart-read-4/results/domain.json` 在独立新 JVM 读取复制的保存世界，16 台机器完整交还且权威副本清空。该测试发现并修复两类基础机流体被原生字段与自定义 NBT 重复恢复的问题，改为幂等替换，未放宽指纹校验。

故障注入、AE2 组合与客户端检查现已完成，见 [10.21](#s10-21)；网络生产和整体性能收益仍属于 P3 及后续 A/B 验收。

<a id="s10-21"></a>
### 10.21 P2 退出验收与本轮修正（2026-09-19）

本轮自审修复了**同进程重建核心可能绕过目录回执**的漏洞：恢复 CLAIM 和 RELEASE_BINDING 时必须记住当前目录 revision，等该版本实际落盘，不能拿旧 persistedRevision 当作新操作的回执。`OwnershipTransferTest` 用可控延迟执行器验证未完成 claim 写入时不封存、未完成释放写入时不解锁；正常跨进程恢复保留同样的门控。`OwnedMachines.activeCount` 复用现有位置索引，整批交还后恢复“独立模式”并拒绝重复空交还，不遍历历史收据计算界面状态。中英文结构提示统一为“结构可用”，托管状态单列。

| 验收 | 本地可复核证据 | 结果与边界 |
| --- | --- | --- |
| 全量回归与产物 | `build/p2-exit-build.stdout.log`；`build/test-results/test`；`build/reports/release-artifact.txt` | `test build verifyReleaseArtifact --no-daemon` 通过；764 项中 762 通过、2 项原有跳过，0 失败；正常 JAR 已核对无开发探针类 |
| 无 AE2 专服 | `build/network-probe-p2-exit-noae2-final/results/domain.json` | 36 类真实机器的父 ticker／加速直接入口与缓存能力隔离，基础机往返，核心重建、拓扑、8 类故障及正常停服保存全部通过 |
| AE2＋Applied Flux 专服 | `build/network-probe-p2-exit-ae2-final/results/domain.json` | AE2 节点在独立模式创建、接管时销毁、交还后重建；上述同一矩阵全部通过。测试副本补齐本地 GuideME／Glodium，未替换用户运行依赖 |
| 跨进程恢复 | `build/network-probe-p2-exit-restart-write/results/domain.json`；`build/network-probe-p2-exit-restart-read/results/domain.json` | 两类基础机 × 八个阶段，共 16 台，写进程正常退出后复制测试世界给独立新 JVM，完整交还并清除可执行域副本 |
| 客户端与菜单 | `build/network-probe-p2-exit-client-final/results/client.json`；同目录 `before.png`、`managed.png`、`returned.png` | 实际客户端同步 2 成员／3 蜂位／1 离心进程，点击真实按钮经原版菜单包完成接管与交还；越权、超距、未知命令、非当前菜单拒绝；中文布局与状态截图已检查，正常退世界保存并退出通过 |

8 类故障为两类基础机分别注入旧无绑定 BE 回现、绑定代际篡改、强制破坏、所有者变更；所有案例保留目录占用和权威资产进入 RECOVERY，并检查旧源掉落被阻止。容量预检拒绝不可还原能量映像且不修改源机。客户端探针自身的强制渲染 tick 重入和缺少 `ClientLevel.disconnect` 的退出问题已修正，最终报告只引用修正后完整通过的运行；失败运行保留供诊断。

**P2 已满足“结构与唯一所有权”的阶段目标。** 正常世界接管开关仍默认关闭；当前只有基础蜂箱／基础离心机具有已验收的资产适配器，托管状态不生产。工厂生产／资产适配、特殊蜂种、逐位喂食迁移、统一供给与能量账户、管理取回界面、长稳断电故障和性能 A/B 按原路线进入 P3–P7，不把专服或菜单通过解释为这些后续功能已完成。未实施线上发布或修改正式测试世界。
