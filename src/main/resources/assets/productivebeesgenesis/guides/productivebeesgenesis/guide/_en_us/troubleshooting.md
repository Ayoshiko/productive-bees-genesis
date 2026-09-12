---
navigation:
  parent: index.md
  title: Troubleshooting
  icon: "minecraft:redstone_torch"
  position: 8
---
# Troubleshooting

<ItemGrid>
  <ItemIcon id="productivebeesgenesis:mek_apiary" />
  <ItemIcon id="productivebeesgenesis:mek_centrifuge" />
  <ItemIcon id="mekanism:configurator" />
  <ItemIcon id="minecraft:redstone_torch" />
</ItemGrid>

Check power, redstone, input, and output before breaking a machine.

## Machine does not run

Confirm that the energy bar is not empty, the power face accepts input, redstone mode is Ignored, and security permits access.

For an apiary, verify the bee, flower or pollen, loaded recipe, output space, and any weather/behavior gene rules. For a centrifuge, confirm the comb has bee-type data and a JEI recipe. Smelting inputs also require both the server feature and the per-machine toggle.

## Progress completes but nothing appears

An item output or one fluid-specific tank is full. Open the Multi-fluid window and inspect every page. Inputs remain safe while outputs are blocked.

## Pipes do not move contents

Check the connected face, ejection toggle, pipe extraction/filter requirements, and destination space. A face set to Output may still require automatic ejection.

## AE2 does not work

Check network power, channels, storage capacity, machine AE2 switches, input mode, exact matching, and reserve amount. With Applied Flux, verify that the ME network contains FE rather than only native AE energy. Buffered items retry after reconnection.

## Upgrade or Myriad issues

Rejected upgrades may be unsupported, at their cap, or blocked by balance-profile mixing rules. Missing Myriad targets usually indicate an empty whitelist, blacklist exclusion, disabled production, or a bee without a loaded production recipe.

Use Jade for machine state and retain `latest.log` after reproducing an unresolved problem.
