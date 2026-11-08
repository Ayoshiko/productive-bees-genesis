---
navigation:
  parent: reference-index.md
  title: Troubleshooting
  icon: "minecraft:redstone_torch"
  position: 2
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

Check network power, channels, storage capacity, machine AE2 switches, input mode, exact matching, and reserve amount. With Applied Flux, verify that the ME network contains FE rather than only native AE energy. Ordinary disconnection or full storage preserves contents already in the machine and known pending returns; reconnecting or freeing space allows them to continue.

If a transfer, input return or charge fails after starting and its actual result cannot be confirmed, the request is saved and the affected operation pauses. Keep the machine and logs, and ask an operator to reconcile both endpoints' inventory and energy before resolving it.

## Failed upgrades or recovery pending

Normal machine upgrades preserve complete fluid data and paid progress. If restoration fails partway through, the apiary retains the source data and failure stage, then pauses production, active output and further upgrades. Pipes may still remove inventory that was already restored to real slots; include those later transfers in reconciliation.

In the machine's dimension, an operator can use `/pbgoutput x y z status` to inspect its current request and `/pbgoutput x y z upgrade-data requestUUID page` to read the retained upgrade source. Only after checking the original machine, target machine, resource accounts and later transfers should the operator run `/pbgoutput x y z confirm-upgrade-settled requestUUID` to resume it. **Recovery records are evidence, not a second inventory; confirmation does not reissue or replay their assets.**

## Bee editor import or application failed

Malformed imported fields retain their original values. Click the red value to open **JSON**, repair it, then check again. An unfinished numeric entry can be saved as a draft but must be completed with a valid value before applying to the world. Offline-instance imports read only script content they can identify; conditional or statically unresolved parts need author review. The importer does not execute the other modpack's scripts.

If an application or batch import has no result yet, keep the drafts and choose **Check application result** for the original operation. Reopening the editor or reconnecting to the original server within the same client process keeps pending operations available for querying. A complete client restart does not restore the pending list automatically; the server retains operation records, so keep your drafts and operation identifier.

## Upgrade or Myriad issues

Rejected upgrades may be unsupported, at their cap, or blocked by balance-profile mixing rules. Missing Myriad targets usually indicate an empty whitelist, blacklist exclusion, disabled production, or a bee without a loaded production recipe.

Use Jade for machine state and retain `latest.log` after reproducing an unresolved problem.
