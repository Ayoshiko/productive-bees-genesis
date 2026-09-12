---
navigation:
  parent: index.md
  title: 通用机械离心机
  icon: "productivebeesgenesis:mek_centrifuge"
  position: 2
item_ids:
  - productivebeesgenesis:mek_centrifuge
  - productivebeesgenesis:basic_mek_centrifuge_factory
  - productivebeesgenesis:advanced_mek_centrifuge_factory
  - productivebeesgenesis:elite_mek_centrifuge_factory
  - productivebeesgenesis:ultimate_mek_centrifuge_factory
  - productivebeesgenesis:overclocked_mek_centrifuge_factory
  - productivebeesgenesis:quantum_mek_centrifuge_factory
  - productivebeesgenesis:dense_mek_centrifuge_factory
  - productivebeesgenesis:multiversal_mek_centrifuge_factory
  - productivebeesgenesis:creative_mek_centrifuge_factory
  - productivebeesgenesis:absolute_extra_mek_centrifuge_factory
  - productivebeesgenesis:supreme_extra_mek_centrifuge_factory
  - productivebeesgenesis:cosmic_extra_mek_centrifuge_factory
  - productivebeesgenesis:infinite_extra_mek_centrifuge_factory
  - productivebeesgenesis:absolute_overclocked_emextra_mek_centrifuge_factory
  - productivebeesgenesis:supreme_quantum_emextra_mek_centrifuge_factory
  - productivebeesgenesis:cosmic_dense_emextra_mek_centrifuge_factory
  - productivebeesgenesis:infinite_multiversal_emextra_mek_centrifuge_factory
---
# 通用机械离心机

<GameScene zoom="5" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_centrifuge" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:mek_centrifuge" x="2" y="0" z="0" p:facing="south" p:active="true" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

拖动场景可以检查机器其他面；初始视角朝向正面。离心机就是“蜜脾拆解机”：它读取蜜脾记住的蜜蜂类型，然后按 Productive Bees 的配方产出矿物、材料和蜂蜜等流体。

<RecipeFor id="productivebeesgenesis:mek_centrifuge" fallbackText="当前整合包替换或关闭了离心机配方，请在 JEI 中搜索通用机械离心机。" />

## 第一次使用

1. 接入 FE，并把接电的那一面设成能量输入。
2. 放入带有正确蜜蜂类型的蜜脾或蜜脾块。
3. 等待进度完成，从物品输出槽和流体罐取走产物。
4. 若要自动输出流体，请在侧面配置中打开弹出；只设置“输出面”还不会自动推送。

普通版一次处理 1 份；基础、高级、精英、终极工厂分别可并行处理 3、5、7、9 份。机器会先确认所有产物有地方放才完成配方，因此箱子满时会安全暂停。

## 工厂等级

<ItemGrid>
  <ItemIcon id="productivebeesgenesis:mek_centrifuge" />
  <ItemIcon id="productivebeesgenesis:basic_mek_centrifuge_factory" />
  <ItemIcon id="productivebeesgenesis:advanced_mek_centrifuge_factory" />
  <ItemIcon id="productivebeesgenesis:elite_mek_centrifuge_factory" />
  <ItemIcon id="productivebeesgenesis:ultimate_mek_centrifuge_factory" />
</ItemGrid>

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_centrifuge" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:basic_mek_centrifuge_factory" x="1" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:advanced_mek_centrifuge_factory" x="2" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:elite_mek_centrifuge_factory" x="3" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:ultimate_mek_centrifuge_factory" x="4" y="0" z="0" p:facing="south" p:active="false" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

## 特有按钮

- **PB 配方**：装有 JEI 时，直接查看当前蜜脾可能得到什么。
- **输入返回**：回到上一次查看的输入，翻很多配方时很方便。
- **多流体槽**：不同流体分开存放；打开窗口后可翻页查看每种流体占了多少空间。
- **熔炼兼容**：允许这台离心机顺手处理可熔炼输入。服务器允许后，仍需在每台机器上单独打开。
- **PB 升级**：安装生产力、时间、稳定性和本模组的功能升级。

## 关于蜜脾类型

万象创世蜜脾和 PB 的可配置蜜脾都靠内部的“蜜蜂类型”信息找配方。正常生产和机器搬运会保留它；如果命令、脚本或其他模组生成了一个没有类型信息的空白蜜脾，离心机就不知道该按哪只蜜蜂处理。

## 多流体为什么会堵

不同蜜蜂可能产出不同流体。多流体模式会为新流体分配槽位，避免混装；但所有可用槽位都满后，新流体仍无法进入。此时打开多流体窗口，找出占满的那一页并抽走流体，而不是反复塞入更多蜜脾。
