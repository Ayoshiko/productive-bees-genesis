// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "productivebees:advanced_beehive",
    "ingredient": "example:combined",
    "results": [
      {
        "item": {
          "item": "productivebees:configurable_honeycomb",
          "components": {
            "productivebees:bee_type": "example:combined"
          }
        },
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:combined"
      }
    ]
  }).id('example:combined_honeycomb')

  event.custom({
    "type": "productivebees:centrifuge",
    "ingredient": {
      "type": "productivebees:component",
      "components": {
        "productivebees:bee_type": "example:combined"
      },
      "items": "productivebees:configurable_honeycomb"
    },
    "outputs": [
      {
        "item": {
          "item": "minecraft:iron_nugget"
        },
        "min": 10,
        "max": 10,
        "chance": 1
      },
      {
        "item": {
          "item": "minecraft:raw_iron"
        },
        "min": 2,
        "max": 2,
        "chance": 1
      },
      {
        "item": {
          "item": "minecraft:copper_ingot"
        },
        "min": 1,
        "max": 1,
        "chance": 1
      },
      {
        "item": {
          "item": "minecraft:gold_nugget"
        },
        "min": 1,
        "max": 1,
        "chance": 1
      }
    ],
    "processingTime": 200,
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:combined"
      }
    ]
  }).id('example:combined_items')

  event.custom({
    "type": "productivebeesgenesis:resource_centrifuging",
    "bee": "example:combined",
    "include_pb_outputs": true,
    "processing_time": 20,
    "outputs": [
      {
        "kind": "energy",
        "energy_type": "FE",
        "amount": "1000",
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:combined"
      }
    ]
  }).id('example:combined_centrifuging')
})
