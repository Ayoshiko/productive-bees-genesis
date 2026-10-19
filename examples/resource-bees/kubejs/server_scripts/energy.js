// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "productivebeesgenesis:resource_centrifuging",
    "bee": "example:energy_comb",
    "outputs": [
      {
        "kind": "energy",
        "energy_type": "FE",
        "amount": "1000",
        "chance": 1
      }
    ],
    "processing_time": 20,
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:energy_comb"
      }
    ]
  }).id('example:energy_centrifuging')

  event.custom({
    "type": "productivebeesgenesis:resource_production",
    "bee": "example:copper_energy",
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
        "bee": "example:copper_energy"
      }
    ]
  }).id('example:copper_energy_production')

  event.custom({
    "type": "productivebeesgenesis:resource_centrifuging",
    "bee": "example:copper_energy_comb",
    "outputs": [
      {
        "kind": "energy",
        "energy_type": "FE",
        "amount": "1000",
        "chance": 1
      }
    ],
    "processing_time": 20,
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:copper_energy_comb"
      }
    ]
  }).id('example:copper_energy_comb_centrifuging')

  event.custom({
    "type": "productivebees:advanced_beehive",
    "ingredient": "example:copper_energy_comb",
    "results": [
      {
        "item": {
          "item": "productivebees:configurable_honeycomb",
          "components": {
            "productivebees:bee_type": "example:copper_energy_comb"
          }
        },
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:copper_energy_comb"
      }
    ]
  }).id('example:copper_energy_comb_honeycomb')

  event.custom({
    "type": "productivebees:advanced_beehive",
    "ingredient": "example:energy_comb",
    "results": [
      {
        "item": {
          "item": "productivebees:configurable_honeycomb",
          "components": {
            "productivebees:bee_type": "example:energy_comb"
          }
        },
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:energy_comb"
      }
    ]
  }).id('example:energy_honeycomb')

  event.custom({
    "type": "productivebeesgenesis:resource_production",
    "bee": "example:energy",
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
        "bee": "example:energy"
      }
    ]
  }).id('example:energy_production')
})
