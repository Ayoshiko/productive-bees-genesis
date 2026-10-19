// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "productivebees:advanced_beehive",
    "ingredient": "example:emc_comb",
    "results": [
      {
        "item": {
          "item": "productivebees:configurable_honeycomb",
          "components": {
            "productivebees:bee_type": "example:emc_comb"
          }
        },
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "projecte"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:emc_comb"
      }
    ]
  }).id('example:emc_honeycomb')

  event.custom({
    "type": "productivebeesgenesis:resource_production",
    "bee": "example:emc",
    "outputs": [
      {
        "kind": "emc",
        "amount": "1000",
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "projecte"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:emc"
      }
    ]
  }).id('example:emc_production')

  event.custom({
    "type": "productivebeesgenesis:resource_centrifuging",
    "bee": "example:emc_comb",
    "outputs": [
      {
        "kind": "emc",
        "amount": "1000",
        "chance": 1
      }
    ],
    "processing_time": 20,
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "projecte"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:emc_comb"
      }
    ]
  }).id('example:emc_centrifuging')
})
