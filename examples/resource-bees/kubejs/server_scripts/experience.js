// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "productivebees:advanced_beehive",
    "ingredient": "example:experience_comb",
    "results": [
      {
        "item": {
          "item": "productivebees:configurable_honeycomb",
          "components": {
            "productivebees:bee_type": "example:experience_comb"
          }
        },
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "experiencelib"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:experience_comb"
      }
    ]
  }).id('example:experience_honeycomb')

  event.custom({
    "type": "productivebeesgenesis:resource_centrifuging",
    "bee": "example:experience_comb",
    "outputs": [
      {
        "kind": "experience",
        "amount": "10",
        "chance": 1
      }
    ],
    "processing_time": 20,
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "experiencelib"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:experience_comb"
      }
    ]
  }).id('example:experience_centrifuging')

  event.custom({
    "type": "productivebeesgenesis:resource_production",
    "bee": "example:experience",
    "outputs": [
      {
        "kind": "experience",
        "amount": "10",
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "experiencelib"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:experience"
      }
    ]
  }).id('example:experience_production')
})
