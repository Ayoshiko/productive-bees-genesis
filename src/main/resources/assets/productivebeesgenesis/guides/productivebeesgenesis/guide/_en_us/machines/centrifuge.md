---
navigation:
  parent: index.md
  title: Mekanism Centrifuge
  icon: "productivebeesgenesis:mek_centrifuge"
  position: 2
item_ids:
  - productivebeesgenesis:mek_centrifuge
  - productivebeesgenesis:basic_mek_centrifuge_factory
  - productivebeesgenesis:advanced_mek_centrifuge_factory
  - productivebeesgenesis:elite_mek_centrifuge_factory
  - productivebeesgenesis:ultimate_mek_centrifuge_factory
  - productivebeesgenesis:overclocked_mek_centrifuge_factory
  - productivebeesgenesis:quantum_mek_centrifuge_factory
  - productivebeesgenesis:dense_mek_centrifuge_factory
  - productivebeesgenesis:multiversal_mek_centrifuge_factory
  - productivebeesgenesis:creative_mek_centrifuge_factory
  - productivebeesgenesis:absolute_extra_mek_centrifuge_factory
  - productivebeesgenesis:supreme_extra_mek_centrifuge_factory
  - productivebeesgenesis:cosmic_extra_mek_centrifuge_factory
  - productivebeesgenesis:infinite_extra_mek_centrifuge_factory
  - productivebeesgenesis:absolute_overclocked_emextra_mek_centrifuge_factory
  - productivebeesgenesis:supreme_quantum_emextra_mek_centrifuge_factory
  - productivebeesgenesis:cosmic_dense_emextra_mek_centrifuge_factory
  - productivebeesgenesis:infinite_multiversal_emextra_mek_centrifuge_factory
---
# Mekanism Centrifuge

<GameScene zoom="5" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_centrifuge" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:mek_centrifuge" x="2" y="0" z="0" p:facing="south" p:active="true" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

Drag the scene to inspect other sides. Its initial camera faces the machine fronts. The centrifuge reads the bee type stored on a comb and uses its Productive Bees centrifuge recipe.

<RecipeFor id="productivebeesgenesis:mek_centrifuge" fallbackText="This modpack replaced or disabled the centrifuge recipe. Search JEI for the Mekanism Centrifuge." />

## Basic operation

1. Connect FE and configure an energy input side.
2. Insert a typed comb or comb block.
3. Remove item products and any fluid output.
4. Enable fluid ejection in side configuration if the machine should push fluids automatically.

The normal machine has 1 process. Basic, Advanced, Elite, and Ultimate factories have 3, 5, 7, and 9 parallel processes. A blocked item or fluid output pauses processing safely.

<ItemGrid>
  <ItemIcon id="productivebeesgenesis:mek_centrifuge" />
  <ItemIcon id="productivebeesgenesis:basic_mek_centrifuge_factory" />
  <ItemIcon id="productivebeesgenesis:advanced_mek_centrifuge_factory" />
  <ItemIcon id="productivebeesgenesis:elite_mek_centrifuge_factory" />
  <ItemIcon id="productivebeesgenesis:ultimate_mek_centrifuge_factory" />
</ItemGrid>

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true}>
  <Block id="productivebeesgenesis:mek_centrifuge" x="0" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:basic_mek_centrifuge_factory" x="1" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:advanced_mek_centrifuge_factory" x="2" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:elite_mek_centrifuge_factory" x="3" y="0" z="0" p:facing="south" p:active="false" />
  <Block id="productivebeesgenesis:ultimate_mek_centrifuge_factory" x="4" y="0" z="0" p:facing="south" p:active="false" />
  <IsometricCamera yaw="0" pitch="25" />
</GameScene>

PB Recipe opens JEI, Input Return revisits the previous input, Multi-fluid pages through fluid-specific storage, and Smelting Compatibility enables valid smelting inputs per machine. A blank configurable honeycomb without bee-type data has no recipe identity.
