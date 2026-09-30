package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 由首次种子推导预期余量，独立于客户端步骤、回执和交换服务。 */
final class GuestExchangeAssertions {
    private final List<ItemStack> initial;
    private final NetworkCheckpoint before;
    GuestExchangeAssertions(NetworkCoreBlockEntity core, ServerPlayer player) {
        initial = player.getInventory().items.stream().map(ItemStack::copy).toList();
        before = core.ownership().readyAuthority().checkpoint();
    }
    void verify(NetworkCoreBlockEntity core, ServerPlayer player) {
        var after = core.ownership().readyAuthority().checkpoint();
        var registries = player.registryAccess();
        var iron = ProductKeyCodec.item(new ItemStack(Items.IRON_INGOT), registries);
        var red = before.ledger().balances().keySet().stream().filter(k -> k.componentPreview().contains("variant-red")).findFirst().orElseThrow();
        var water = before.ledger().balances().keySet().stream().filter(k -> k.kind() == ProductKey.Kind.FLUID).findFirst().orElseThrow();
        var expected = new HashMap<>(before.ledger().balances());
        expected.put(iron, expected.get(iron).subtract(ProductAmount.of(1)));
        expected.remove(red);
        expected.put(water, expected.get(water).subtract(ProductAmount.of(1000)));
        require(after.ledger().balances().equals(expected), "Guest changed another product or lost exact remainder");
        for (int slot = 0; slot < 36; slot++) {
            ItemStack wanted = switch (slot) {
                case 0 -> initial.get(0).copyWithCount(63);
                case 1 -> VerifiedCageProjection.afterRelease(initial.get(1));
                case 2 -> new ItemStack(Items.WATER_BUCKET);
                case 4 -> initial.get(4).copyWithCount(64);
                case 5 -> ProductKeyCodec.item(red, 2, registries);
                default -> initial.get(slot);
            };
            require(ItemStack.matches(wanted, player.getInventory().getItem(slot)), "Guest inventory differs at " + slot);
        }
        var records = java.util.stream.StreamSupport.stream(after.ownedMachines().activeValues().spliterator(), false).filter(r -> r.bees() != null).toList();
        require(records.size() == 1, "Expected one owned apiary");
        var bees = records.getFirst().bees();
        require(bees.bees().size() == 1 && bees.bees().getFirst().slot() == 0
                && bees.bees().getFirst().originalSlot().copy().getCompound("entity_data").equals(VerifiedCageProjection.contents(initial.get(1))),
                "Guest bee duplicated or full entity data changed");
        var food = bees.feeding().slots();
        require(food.getFirst().count() == 1 && ItemStack.matches(initial.get(0).copyWithCount(1),
                ItemStack.parseOptional(registries, food.getFirst().item().stack(1))), "Guest feeding remainder differs");
        require(food.stream().skip(1).allMatch(s -> s.count() == 0), "Unexpected food in another slot");
        require(after.energy().equals(before.energy()) && after.scheduler().equals(before.scheduler())
                && after.transfers().equals(before.transfers()) && after.ledger().transactions().equals(before.ledger().transactions()),
                "Guest exchange changed paid work, energy or transfers");
        require(!player.getInventory().getItem(1).has(DataComponents.CUSTOM_DATA), "Bee also retained in cage");
    }
}
