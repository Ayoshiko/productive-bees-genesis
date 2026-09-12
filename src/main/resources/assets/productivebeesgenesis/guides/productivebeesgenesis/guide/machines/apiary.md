---
navigation:
  parent: index.md
  title: 机械蜂箱
  icon: "productivebeesgenesis:mek_apiary"
  position: 1
item_ids:
  - productivebeesgenesis:mek_apiary
  - productivebeesgenesis:basic_mek_apiary_factory
  - productivebeesgenesis:advanced_mek_apiary_factory
  - productivebeesgenesis:elite_mek_apiary_factory
  - productivebeesgenesis:ultimate_mek_apiary_factory
  - productivebeesgenesis:overclocked_mek_apiary_factory
  - productivebeesgenesis:quantum_mek_apiary_factory
  - productivebeesgenesis:dense_mek_apiary_factory
  - productivebeesgenesis:multiversal_mek_apiary_factory
  - productivebeesgenesis:creative_mek_apiary_factory
  - productivebeesgenesis:absolute_extra_mek_apiary_factory
  - productivebeesgenesis:supreme_extra_mek_apiary_factory
  - productivebeesgenesis:cosmic_extra_mek_apiary_factory
  - productivebeesgenesis:infinite_extra_mek_apiary_factory
  - productivebeesgenesis:absolute_overclocked_emextra_mek_apiary_factory
  - productivebeesgenesis:supreme_quantum_emextra_mek_apiary_factory
  - productivebeesgenesis:cosmic_dense_emextra_mek_apiary_factory
  - productivebeesgenesis:infinite_multiversal_emextra_mek_apiary_factory
---
# 机械蜂箱

<GameScene zoom="5" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_apiary" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:mek_apiary" x="2" y="0" z="0" p:facing="south" p:active="true" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

拖动场景可以检查机器其他面；初始视角朝向正面。机械蜂箱可以理解为“把蜜蜂和花朵放进机器里养”：不需要在世界里搭实体蜂巢，也不用等蜜蜂飞来飞去。

<RecipeFor id="productivebeesgenesis:mek_apiary" fallbackText="当前整合包替换或关闭了机械蜂箱配方，请在 JEI 中搜索机械蜂箱。" />

## 从放下到产出

1. 连接 FE，确认能量条有电。
2. 把蜜蜂或装有蜜蜂的笼子放进蜜蜂槽。
3. 打开**喂食器**，放入这只蜜蜂需要的花或花粉。需求可以在 JEI 或槽位提示中查看。
4. 等进度条走满，从输出区取走蜜脾和副产物。
5. 有流体产物时，从设置为流体输出的一面抽走。

普通蜂箱有 3 个工作位；基础、高级、精英、终极工厂分别有 5、10、15、20 个。每个工作位单独判断蜜蜂、花朵、配方、天气和行为条件，所以其中一只蜜蜂停工时，其他槽仍能继续。

## 工厂长什么样

<ItemGrid>
  <ItemIcon id="productivebeesgenesis:mek_apiary" />
  <ItemIcon id="productivebeesgenesis:basic_mek_apiary_factory" />
  <ItemIcon id="productivebeesgenesis:advanced_mek_apiary_factory" />
  <ItemIcon id="productivebeesgenesis:elite_mek_apiary_factory" />
  <ItemIcon id="productivebeesgenesis:ultimate_mek_apiary_factory" />
</ItemGrid>

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_apiary" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:basic_mek_apiary_factory" x="1" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:advanced_mek_apiary_factory" x="2" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:elite_mek_apiary_factory" x="3" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:ultimate_mek_apiary_factory" x="4" y="0" z="0" p:facing="south" p:active="false" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

每一级工厂都由前一级升级而来。不要只看机器速度：蜜脾堆得越快，后面的离心机、箱子和网络也要跟得上。

## 看懂界面

- **蜜蜂槽**：放蜜蜂或蜂笼；蜂笼转移会保留蜜蜂，不会凭空删除。
- **喂食器**：所有工作位共用的花朵缓存，物品不会每轮消耗。
- **输出分页箭头**：只切换你看到的那一页，不会隐藏真实槽位；管道和 AE2 仍能访问全部产物。
- **PB 升级**：安装生产力、时间、基因采样、蜜脾块等升级。
- **侧面、红石、安全、弹出**：行为与 Mekanism 机器一致，详见[界面与按钮](../gui.md)。

## 直连离心机

把蜂箱和离心机贴着放，再打开蜂箱的**离心机优先**。新蜜脾会先尝试直接进入离心机，少经过输出槽和管道；离心机堵住时，蜜脾会留在安全缓存里稍后重试。

## 停工时先看哪里

- 没进度：检查电、蜜蜂和花朵，再看时间/天气是否符合蜜蜂基因。
- 进度走到头不出货：输出槽或流体罐大概率满了。
- 只有部分蜜蜂不工作：逐个悬停槽位看缺少的条件，不要先拆机器。
