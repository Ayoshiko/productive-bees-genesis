// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "productivebeesgenesis:resource_production",
    "bee": "example:source",
    "outputs": [
      {
        "kind": "source",
        "amount": "100",
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "ars_nouveau"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:source"
      }
    ]
  }).id('example:source_production')

  event.custom({
    "type": "productivebees:advanced_beehive",
    "ingredient": "example:source_comb",
    "results": [
      {
        "item": {
          "item": "productivebees:configurable_honeycomb",
          "components": {
            "productivebees:bee_type": "example:source_comb"
          }
        },
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "ars_nouveau"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:source_comb"
      }
    ]
  }).id('example:source_honeycomb')

  event.custom({
    "type": "productivebeesgenesis:resource_centrifuging",
    "bee": "example:source_comb",
    "outputs": [
      {
        "kind": "source",
        "amount": "100",
        "chance": 1
      }
    ],
    "processing_time": 20,
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "ars_nouveau"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:source_comb"
      }
    ]
  }).id('example:source_centrifuging')
})
