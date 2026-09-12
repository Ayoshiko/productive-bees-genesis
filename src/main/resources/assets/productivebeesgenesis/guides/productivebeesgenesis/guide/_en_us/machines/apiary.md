---
navigation:
  parent: index.md
  title: Mekanism Apiary
  icon: "productivebeesgenesis:mek_apiary"
  position: 1
item_ids:
  - productivebeesgenesis:mek_apiary
  - productivebeesgenesis:basic_mek_apiary_factory
  - productivebeesgenesis:advanced_mek_apiary_factory
  - productivebeesgenesis:elite_mek_apiary_factory
  - productivebeesgenesis:ultimate_mek_apiary_factory
  - productivebeesgenesis:overclocked_mek_apiary_factory
  - productivebeesgenesis:quantum_mek_apiary_factory
  - productivebeesgenesis:dense_mek_apiary_factory
  - productivebeesgenesis:multiversal_mek_apiary_factory
  - productivebeesgenesis:creative_mek_apiary_factory
  - productivebeesgenesis:absolute_extra_mek_apiary_factory
  - productivebeesgenesis:supreme_extra_mek_apiary_factory
  - productivebeesgenesis:cosmic_extra_mek_apiary_factory
  - productivebeesgenesis:infinite_extra_mek_apiary_factory
  - productivebeesgenesis:absolute_overclocked_emextra_mek_apiary_factory
  - productivebeesgenesis:supreme_quantum_emextra_mek_apiary_factory
  - productivebeesgenesis:cosmic_dense_emextra_mek_apiary_factory
  - productivebeesgenesis:infinite_multiversal_emextra_mek_apiary_factory
---
# Mekanism Apiary

<GameScene zoom="5" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_apiary" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:mek_apiary" x="2" y="0" z="0" p:facing="south" p:active="true" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

Drag the scene to inspect other sides. Its initial camera faces the machine fronts. The apiary is a powered bee box that works without a physical hive structure in the world.

<RecipeFor id="productivebeesgenesis:mek_apiary" fallbackText="This modpack replaced or disabled the apiary recipe. Search JEI for the Mekanism Apiary." />

## Basic operation

1. Connect FE.
2. Insert bees or filled cages in the bee slots.
3. Put the required flower or pollen in the shared Feeder window. It is not consumed every operation.
4. Wait for combs and byproducts in the paged output area.
5. Extract fluid from a configured fluid output side when a recipe creates it.

The normal apiary has 3 processes. Basic, Advanced, Elite, and Ultimate factories have 5, 10, 15, and 20. Every process checks its own bee, recipe, flower, weather, and behavior conditions.

<ItemGrid>
  <ItemIcon id="productivebeesgenesis:mek_apiary" />
  <ItemIcon id="productivebeesgenesis:basic_mek_apiary_factory" />
  <ItemIcon id="productivebeesgenesis:advanced_mek_apiary_factory" />
  <ItemIcon id="productivebeesgenesis:elite_mek_apiary_factory" />
  <ItemIcon id="productivebeesgenesis:ultimate_mek_apiary_factory" />
</ItemGrid>

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_apiary" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:basic_mek_apiary_factory" x="1" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:advanced_mek_apiary_factory" x="2" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:elite_mek_apiary_factory" x="3" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:ultimate_mek_apiary_factory" x="4" y="0" z="0" p:facing="south" p:active="false" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

The Feeder, PB Upgrade, output paging, AE2 output, and Centrifuge Priority controls are explained in [Screens and Controls](../gui.md). Output arrows change only the visible page; pipes and AE2 still see every slot.

Place a centrifuge beside the apiary and enable **Centrifuge Priority** to use direct comb transfer. A blocked centrifuge causes a safe retry instead of item loss.
