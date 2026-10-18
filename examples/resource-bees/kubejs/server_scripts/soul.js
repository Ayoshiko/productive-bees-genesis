// soul.js：先保留 datapack 中的蜂种定义，再将本文件放入 kubejs/server_scripts。
// 此脚本替换下面同 ID 的配方 JSON；删除被替换的 JSON 后执行 /reload。
// 蜂种 ID 必须与 bee / ingredient / bee_type 一致；amount 保持十进制字符串，chance 是 0..1 的数字。
// 机械宿主的资源从本机 ME 连接输出；对应存储桥接模组仍须安装。
// Keep the bee definitions, replace the matching recipe JSON files, and reload. Amounts are strings.
ServerEvents.recipes(event => {
  event.custom({
    type: 'productivebeesgenesis:resource_production',
    bee: 'example:soul',
    outputs: [{ kind: 'soul', amount: '1', chance: 1.0 }]
  }).id('example:soul_production')

  event.custom({
    type: 'productivebees:advanced_beehive',
    ingredient: 'example:soul_comb',
    results: [{
      item: {
        item: 'productivebees:configurable_honeycomb',
        components: { 'productivebees:bee_type': 'example:soul_comb' }
      },
      chance: 1.0
    }]
  }).id('example:soul_honeycomb')

  event.custom({
    type: 'productivebeesgenesis:resource_centrifuging',
    bee: 'example:soul_comb',
    outputs: [{ kind: 'soul', amount: '1', chance: 1.0 }],
    processing_time: 20
  }).id('example:soul_centrifuging')
})
