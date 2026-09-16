---
navigation:
  parent: upgrades/upgrades-index.md
  title: Upgrade and Automation Details
  icon: "productivebeesgenesis:essence_conversion_upgrade"
  position: 1
item_ids:
  - productivebeesgenesis:raw_ore_smelting_upgrade
  - productivebeesgenesis:essence_conversion_upgrade
  - productivebeesgenesis:byproduct_destruction_upgrade
  - productivebeesgenesis:gene_type_only_upgrade
  - productivebeesgenesis:gene_full_purity_upgrade
---
# Upgrade and Automation Details

## Productive Bees upgrades

<ItemGrid>
  <ItemIcon id="productivelib:upgrade_productivity" />
  <ItemIcon id="productivelib:upgrade_time" />
  <ItemIcon id="productivelib:upgrade_gene_sampler" />
  <ItemIcon id="productivelib:upgrade_block" />
  <ItemIcon id="productivelib:upgrade_stability" />
</ItemGrid>

Productivity increases output, Time shortens operations, Gene Sampler and Comb Block are apiary features, and Stability improves non-guaranteed centrifuge products. Balance profiles may restrict mixing different Productivity or Time tiers.

## Addon upgrades

<ItemImage id="productivebeesgenesis:raw_ore_smelting_upgrade" scale="2" />

**Raw Ore Smelting** converts eligible raw-ore outputs into matching ingots through a safe smelting lookup.

<RecipeFor id="productivebeesgenesis:raw_ore_smelting_upgrade" fallbackText="This modpack replaced or disabled the Raw Ore Smelting Upgrade recipe. Search JEI." />

<ItemImage id="productivebeesgenesis:essence_conversion_upgrade" scale="2" />

**Essence Conversion** compresses outputs only when a clear, safe single-material crafting conversion exists.

<RecipeFor id="productivebeesgenesis:essence_conversion_upgrade" fallbackText="This modpack replaced or disabled the Essence Conversion Upgrade recipe. Search JEI." />

<ItemImage id="productivebeesgenesis:byproduct_destruction_upgrade" scale="2" />

**Byproduct Destruction** removes selected honey, wax, or pollen byproducts. Install it only after confirming that your pack no longer needs those materials.

<RecipeFor id="productivebeesgenesis:byproduct_destruction_upgrade" fallbackText="This modpack replaced or disabled the Byproduct Destruction Upgrade recipe. Search JEI." />

<ItemImage id="productivebeesgenesis:gene_type_only_upgrade" scale="2" />

**Gene Type Filter** makes an installed Gene Sampler output only bee type (TYPE) genes. It is limited to one per apiary and has no effect without a Gene Sampler.

<RecipeFor id="productivebeesgenesis:gene_type_only_upgrade" fallbackText="This modpack replaced or disabled the Gene Type Filter recipe. Search JEI." />

<ItemImage id="productivebeesgenesis:gene_full_purity_upgrade" scale="2" />

**Full Purity Gene** makes every sampled gene 100% pure. Install it with Gene Type Filter to output only 100%-pure bee type genes.

<RecipeFor id="productivebeesgenesis:gene_full_purity_upgrade" fallbackText="This modpack replaced or disabled the Full Purity Gene recipe. Search JEI." />

## Direct apiary-to-centrifuge transfer

<GameScene zoom="4" interactive={true} background="transparent" fullWidth={true} padding="5">
  <ImportStructure src="../assets/assemblies/first_line.snbt" />
  <IsometricCamera yaw="30" pitch="28" />
</GameScene>

The scene shows an Energy Cube, a first cable, the apiary, a second cable, the centrifuge, and a barrel. The first cable connects the Energy Cube to the apiary, and the second connects the apiary to the centrifuge. Enable **Centrifuge Priority** for direct comb transfer. Blocked transfers remain buffered and retry safely. The barrel accepts items only, so route honey and other fluids to a tank or ME storage separately.

## Minimal AE2 setup

<ItemGrid>
  <ItemIcon id="ae2:controller" />
  <ItemIcon id="ae2:fluix_smart_cable" />
  <ItemIcon id="ae2:drive" />
  <ItemIcon id="ae2:item_storage_cell_1k" />
</ItemGrid>

Connect the machine to a powered network with a channel and available storage. Enable item output first, fluid output if needed, then test centrifuge input pulling with one exact comb entry and a small amount. Network reserve keeps a chosen quantity in storage.

### AE2 Input window (quick version)

![AE2 input pulling configuration](../assets/images/gui_ae2_input.png)

Open this window with the **`I` (AE2 input config)** button in the machine's side configuration:

1. Leave **Filter mode** at its default and mark **one** comb in a marker cell.
2. Choose **Precise mode** deliberately: on, combs and comb blocks match separately; off, they share one quota.
3. **Set a small pull amount** (1–4) so the input slots are not flooded.
4. Set a **network reserve** so the last emergency stack or crafting template is never drained.
5. Only after a few clean cycles, add more entries and consider **unlimited pull** or **tag expressions**.

> This window has more than twenty clickable elements — the pull toggle, NBT, filter mode, precise mode, global gear, stock, tag filter, four different click combinations on each per-cell gear, and three take/insert actions on each network output slot.
> The complete reference, click table, expression syntax, every info-panel line, and all troubleshooting live on their own page: [AE2 Input Window](ae2-input.md).

With Applied Flux, machines can draw FE stored in the ME network. Keep **Prefer Applied Flux** enabled for that behavior; disable **Native AE Energy Input** only when the machines must use Applied Flux FE exclusively.

Large installations should increase parallelism before extreme speed, separate power and storage into understandable sections, and monitor server MSPT. Stop and empty loaded machines before moving them.
