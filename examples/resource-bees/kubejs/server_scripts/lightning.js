// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "productivebeesgenesis:resource_centrifuging",
    "bee": "example:lightning_comb",
    "outputs": [
      {
        "kind": "lightning",
        "tier": "high_voltage",
        "amount": "1000",
        "chance": 1
      }
    ],
    "processing_time": 20,
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "ae2lt"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:lightning_comb"
      }
    ]
  }).id('example:lightning_centrifuging')

  event.custom({
    "type": "productivebees:advanced_beehive",
    "ingredient": "example:lightning_comb",
    "results": [
      {
        "item": {
          "item": "productivebees:configurable_honeycomb",
          "components": {
            "productivebees:bee_type": "example:lightning_comb"
          }
        },
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "ae2lt"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:lightning_comb"
      }
    ]
  }).id('example:lightning_honeycomb')

  event.custom({
    "type": "productivebeesgenesis:resource_production",
    "bee": "example:lightning",
    "outputs": [
      {
        "kind": "lightning",
        "tier": "high_voltage",
        "amount": "1000",
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "ae2lt"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:lightning"
      }
    ]
  }).id('example:lightning_production')
})
