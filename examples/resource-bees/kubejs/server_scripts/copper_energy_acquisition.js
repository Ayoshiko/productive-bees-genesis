// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "minecraft:crafting_shapeless",
    "ingredients": [
      {
        "item": "productivebees:bee_cage"
      },
      {
        "item": "productivebees:honey_treat"
      },
      {
        "item": "minecraft:copper_ingot"
      }
    ],
    "result": {
      "id": "productivebees:spawn_egg_configurable_bee",
      "count": 1,
      "components": {
        "minecraft:entity_data": {
          "id": "productivebees:configurable_bee",
          "type": "example:copper_energy"
        }
      }
    },
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:copper_energy"
      }
    ]
  }).id('example:acquisition/copper_energy')

  event.custom({
    "type": "minecraft:crafting_shapeless",
    "ingredients": [
      {
        "item": "productivebees:bee_cage"
      },
      {
        "item": "productivebees:honey_treat"
      },
      {
        "item": "minecraft:copper_ingot"
      },
      {
        "item": "productivebees:wax"
      }
    ],
    "result": {
      "id": "productivebees:spawn_egg_configurable_bee",
      "count": 1,
      "components": {
        "minecraft:entity_data": {
          "id": "productivebees:configurable_bee",
          "type": "example:copper_energy_comb"
        }
      }
    },
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:copper_energy_comb"
      }
    ]
  }).id('example:acquisition/copper_energy_comb')
})
