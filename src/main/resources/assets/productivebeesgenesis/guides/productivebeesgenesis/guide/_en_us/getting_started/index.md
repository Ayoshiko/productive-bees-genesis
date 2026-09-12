---
navigation:
  parent: index.md
  title: First Startup
  icon: "minecraft:crafting_table"
  position: 1
---
# First Startup

Your goal is simple: make one bee produce a comb in the apiary, then process that comb in the centrifuge. Leave factories, AE2, and extreme upgrades for later.

## Three useful terms

- **FE** is the power unit used by most technology mods.
- **Side configuration** decides which machine face accepts power or items and which face outputs products.
- Minecraft normally runs **20 game ticks per second**. A 200-tick operation takes about ten seconds.

## Prepare these items

<ItemGrid>
  <ItemIcon id="productivebeesgenesis:mek_apiary" />
  <ItemIcon id="productivebeesgenesis:mek_centrifuge" />
  <ItemIcon id="mekanism:basic_energy_cube" />
  <ItemIcon id="mekanism:basic_universal_cable" />
  <ItemIcon id="minecraft:barrel" />
  <ItemIcon id="minecraft:poppy" />
</ItemGrid>

You need an FE source, both machines, one Productive Bees bee, and that bee's flower or pollen. Use JEI to check the exact flower and recipes in your modpack.

These are the recipes currently loaded by the game. Hover an ingredient to identify it. If the modpack replaces either recipe, use the JEI result as the final reference.

<RecipeFor id="productivebeesgenesis:mek_apiary" fallbackText="This modpack replaced or disabled the apiary recipe. Search JEI for the Mekanism Apiary." />

<RecipeFor id="productivebeesgenesis:mek_centrifuge" fallbackText="This modpack replaced or disabled the centrifuge recipe. Search JEI for the Mekanism Centrifuge." />

## Build the first line

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true} padding="5">
  <ImportStructure src="../assets/assemblies/first_line.snbt" />
  <IsometricCamera yaw="30" pitch="28" />
</GameScene>

Rotate the scene to inspect it. From left to right it shows a Basic Energy Cube, a first Universal Cable, the apiary, a second Universal Cable, the centrifuge, and the output barrel. The first cable connects the Energy Cube to the apiary, the second connects the apiary to the centrifuge, and the barrel touches the centrifuge. This is a recommended layout, not an automatic side configuration: set each machine face in its GUI.

1. Power both machines and check that their energy bars contain FE.
2. Put a bee or a filled bee cage in an apiary bee slot.
3. Open the Feeder window and insert the required flower or pollen. It is a reusable work condition.
4. Wait for a comb to appear.
5. Move the comb into the centrifuge, manually or through the adjacent direct path.
6. Remove the resulting items and any fluid output.

A successful line has moving progress bars, combs leaving the apiary, and resources leaving the centrifuge. Full outputs pause safely instead of deleting inputs.

## If it does not run

Check power, redstone mode, bee and flower matching, output space, and side configuration—in that order. Continue with the [Apiary guide](../machines/apiary.md) or open [Troubleshooting](../troubleshooting.md).
