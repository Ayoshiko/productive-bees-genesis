---
navigation:
  parent: index.md
  title: 万象创世蜜蜂
  icon: "productivebees:spawn_egg_configurable_bee"
  icon_components:
    "minecraft:entity_data":
      id: "productivebees:configurable_bee"
      type: "productivebees:myriadcreations"
  position: 2
item_ids:
  - productivebees:spawn_egg_configurable_bee
  - productivebees:configurable_honeycomb
  - productivebees:configurable_comb
---
# 万象创世蜜蜂

<ItemImage id="productivebees:spawn_egg_configurable_bee" components="minecraft:entity_data={id:'productivebees:configurable_bee',type:'productivebees:myriadcreations'}" scale="2" />

万象创世蜜蜂可以理解为资源生产线里的“随机选手”：每次产出时，它会从当前整合包已经注册的资源蜜蜂中挑一个允许的类型，并把这个类型记在蜜脾上。离心机看到这条记录后，就知道该使用哪份配方。

## 三样相关物品

<ItemGrid>
  <ItemIcon id="productivebees:configurable_honeycomb" components="productivebees:bee_type='productivebees:myriadcreations'" />
  <ItemIcon id="productivebees:configurable_comb" components="productivebees:bee_type='productivebees:myriadcreations'" />
  <ItemIcon id="productivebees:spawn_egg_configurable_bee" components="minecraft:entity_data={id:'productivebees:configurable_bee',type:'productivebees:myriadcreations'}" />
</ItemGrid>

- **刷怪蛋**：主要给创造模式和测试使用，不是正常生存流程的必需品。
- **带类型蜜脾**：Productive Bees 的可配置蜜脾，带有万象创世类型信息，可直接交给离心机。
- **带类型蜜脾块**：方块形态的可配置蜜脾，适合高产线节省槽位。

## 生存模式怎么获得

默认规则下有两条路：

1. **物品转化**：拿配置中指定的转化物品右键目标蜜蜂。默认示例是用木棍转化普通蜜蜂，整合包作者可以改成更合适的物品。
2. **繁殖**：两只符合条件的亲本繁殖出后代。默认亲本规则偏向自繁殖，所以第一次获得通常先走物品转化。

钓鱼获得和蜂巢自然生成默认关闭。是否开启、概率和生物群系都可以在模组配置的“蜜蜂获得方式”页面查看，不需要记英文键名。

<ItemGrid>
  <ItemIcon id="minecraft:bee_spawn_egg" />
  <ItemIcon id="minecraft:stick" />
  <ItemIcon id="productivebees:honey_treat" />
</ItemGrid>

## 过滤想要的资源

在模组配置中打开**万象创世过滤**：

- **不过滤**：所有符合条件的资源蜜蜂都可能被选中。
- **黑名单**：名单里的不会出现，其他照常出现。
- **白名单**：只会出现名单里的；空白名单等于没有可选资源。

编辑器会检查蜜蜂 ID 是否存在。普通玩家建议直接搜索本地化名称再添加，不要手敲一长串英文；整合包作者批量导入时才需要使用 `模组名:蜜蜂名` 格式。

## 蜜脾为什么不能随便替换

<ItemImage id="productivebees:configurable_honeycomb" components="productivebees:bee_type='productivebees:myriadcreations'" scale="2" />

蜜脾内部记录的类型决定了外观、过滤和离心配方。正常堆叠、管道和 AE2 搬运都会保留这条记录；没有记录的空白可配置蜜脾看起来相似，却没有可用配方。

安装 PB 的**蜜脾块升级**后，蜂箱会优先输出块状产物。离心机可以直接处理，具体产量倍率以当前服务器配置为准，不必先手工拆成普通蜜脾。

## 外观设置不会改产量

彩虹、粒子、光晕和颜色属于客户端视觉效果，只影响你自己看到的样子。生产概率、过滤名单、属性和获得方式由世界或服务器决定；多人游戏中以服务器设置为准。
