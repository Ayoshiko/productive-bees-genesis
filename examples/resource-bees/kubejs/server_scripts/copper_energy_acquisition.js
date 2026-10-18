ServerEvents.recipes(event => {
  event.shapeless(
    Item.of('productivebees:spawn_egg_configurable_bee[entity_data={id:"productivebees:configurable_bee",type:"example:copper_energy"}]'),
    ['productivebees:bee_cage', 'productivebees:honey_treat', 'minecraft:copper_ingot']
  ).id('example:copper_energy_acquisition')
  event.shapeless(
    Item.of('productivebees:spawn_egg_configurable_bee[entity_data={id:"productivebees:configurable_bee",type:"example:copper_energy_comb"}]'),
    ['productivebees:bee_cage', 'productivebees:honey_treat', 'minecraft:copper_ingot', 'productivebees:wax']
  ).id('example:copper_energy_comb_acquisition')
})
