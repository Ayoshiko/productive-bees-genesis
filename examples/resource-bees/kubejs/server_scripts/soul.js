// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "productivebeesgenesis:resource_production",
    "bee": "example:soul",
    "outputs": [
      {
        "kind": "soul",
        "amount": "1",
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "industrialforegoingsouls"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:soul"
      }
    ]
  }).id('example:soul_production')

  event.custom({
    "type": "productivebees:advanced_beehive",
    "ingredient": "example:soul_comb",
    "results": [
      {
        "item": {
          "item": "productivebees:configurable_honeycomb",
          "components": {
            "productivebees:bee_type": "example:soul_comb"
          }
        },
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "industrialforegoingsouls"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:soul_comb"
      }
    ]
  }).id('example:soul_honeycomb')

  event.custom({
    "type": "productivebeesgenesis:resource_centrifuging",
    "bee": "example:soul_comb",
    "outputs": [
      {
        "kind": "soul",
        "amount": "1",
        "chance": 1
      }
    ],
    "processing_time": 20,
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "industrialforegoingsouls"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:soul_comb"
      }
    ]
  }).id('example:soul_centrifuging')
})
