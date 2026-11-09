// 保留蜂种 JSON；此脚本替换同 ID 的配方。数据包与脚本选择一种维护方式。
// Keep bee definitions. Use this script instead of recipe JSON files with the same IDs.
ServerEvents.recipes(event => {
  event.custom({
    "type": "productivebeesgenesis:resource_production",
    "bee": "example:ae_key",
    "outputs": [
      {
        "kind": "ae_key",
        "type": "ae2:i",
        "id": "minecraft:diamond",
        "data": {
          "id": "minecraft:diamond"
        },
        "amount": "1",
        "chance": 1
      }
    ],
    "neoforge:conditions": [
      {
        "type": "neoforge:mod_loaded",
        "modid": "ae2"
      },
      {
        "type": "productivebees:bee_exists",
        "bee": "example:ae_key"
      }
    ]
  }).id('example:ae_key_production')
})
