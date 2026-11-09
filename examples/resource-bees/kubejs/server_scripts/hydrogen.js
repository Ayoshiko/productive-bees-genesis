// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "productivebeesgenesis:resource_centrifuging",
    "bee": "example:hydrogen",
    "outputs": [
      {
        "kind": "chemical",
        "id": "mekanism:hydrogen",
        "amount": "100",
        "chance": 1
      }
    ],
    "processing_time": 20,
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:hydrogen"
      }
    ]
  }).id('example:hydrogen_centrifuging')

  event.custom({
    "type": "productivebees:advanced_beehive",
    "ingredient": "example:hydrogen",
    "results": [
      {
        "item": {
          "item": "productivebees:configurable_honeycomb",
          "components": {
            "productivebees:bee_type": "example:hydrogen"
          }
        },
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "productivebees:bee_exists",
        "bee": "example:hydrogen"
      }
    ]
  }).id('example:hydrogen_honeycomb')
})
