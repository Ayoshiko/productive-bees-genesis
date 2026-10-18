package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;

@Tag("minecraft")
class ResourceEmcBigIntegerMinecraftTest {
    private static final BigInteger LARGE = new BigInteger("47958864426752").multiply(BigInteger.valueOf(1_000_000));
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static ResourceAccount produce(ResourceAccounts accounts, BigInteger amount) {
        var account = account(accounts);
        var ticket = account.reserve(account.reference(), 1, new CompoundTag(), Map.of(ResourceTypes.EMC, amount));
        account.start(ticket);
        account.freeze(ticket, Map.of(ResourceTypes.EMC, amount));
        account.commit(ticket);
        return account;
    }

    @Test void decimalRecipeAndWireKeepAmountsBeyondLongAndOtherTypesRemainBounded() {
        var json = JsonParser.parseString("{\"bee\":\"example:emc\",\"outputs\":[{\"kind\":\"emc\",\"amount\":\"" + LARGE + "\"}]}");
        var recipe = ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(LARGE, recipe.outputs().getFirst().exactAmount());
        assertThrows(ArithmeticException.class, () -> recipe.outputs().getFirst().amount());
        var encoded = ResourceProductionRecipe.CODEC.codec().encodeStart(JsonOps.INSTANCE, recipe).getOrThrow();
        assertEquals(recipe, ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
        try {
            ResourceProductionRecipe.STREAM_CODEC.encode(buffer, recipe);
            assertEquals(recipe, ResourceProductionRecipe.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
        assertThrows(ArithmeticException.class, () -> new ResourceProductionRecipe.Output(ResourceTypes.FE, LARGE, 1));
    }

    @Test void exactRollsAndCombBlocksApplyAllMultipliersWithoutClipping() {
        var output = new ResourceProductionRecipe.Output(ResourceTypes.EMC, LARGE, 1);
        var plan = new ResourceRollPlan(List.of(output), 4, 3, 7);
        assertEquals(LARGE.multiply(BigInteger.valueOf(16L * 1024)), plan.sampleWithRolls(1024).exactAmounts().get(ResourceTypes.EMC));
        var recipe = new ResourceCentrifugeRecipe(ResourceLocation.parse("example:emc"), List.of(output), 20);
        var centrifuge = ResourceProductionApi.planCentrifuging(recipe, 2, 0, 7);
        assertEquals(LARGE.multiply(BigInteger.valueOf(8)), centrifuge.sampleWithRolls(4).exactAmounts().get(ResourceTypes.EMC));
        assertThrows(ArithmeticException.class, () -> plan.sample(1024));
    }

    @Test void partialLargeTransferAndOldLongSavePreserveExactBalance() {
        var accounts = new ResourceAccounts(() -> {});
        var account = produce(accounts, LARGE);
        BigInteger accepted = LARGE.divide(BigInteger.valueOf(3));
        assertEquals(accepted, account.transferExact(account.reference(), ResourceTypes.EMC, LARGE, ticket -> accepted));
        assertEquals(LARGE.subtract(accepted), account.availableExact().get(ResourceTypes.EMC));
        var loaded = ResourceAccounts.load(accounts.save(), () -> {}).find(account.reference().sourceId());
        assertEquals(account.availableExact(), loaded.availableExact());
        var old = produce(new ResourceAccounts(() -> {}), BigInteger.valueOf(37));
        assertEquals(37, old.available().get(ResourceTypes.EMC));
        var root = ResourceAccountData.save(old);
        assertEquals(net.minecraft.nbt.Tag.TAG_LONG, root.getList("available", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).get("amount").getId());
        assertEquals(BigInteger.valueOf(37), ResourceAccountData.load(new ResourceAccounts(() -> {}), root).availableExact().get(ResourceTypes.EMC));
    }

    @Test void unknownLargeAcceptanceSurvivesReloadWithoutReplayAndRejectsInvalidResolution() {
        var accounts = new ResourceAccounts(() -> {});
        var account = produce(accounts, LARGE);
        assertThrows(IllegalStateException.class, () -> account.transferExact(account.reference(), ResourceTypes.EMC, LARGE, ticket -> {
            throw new IllegalStateException("accepted then failed");
        }));
        var loaded = ResourceAccounts.load(accounts.save(), () -> {}).find(account.reference().sourceId());
        assertFalse(loaded.canExtract(loaded.reference(), ResourceTypes.EMC));
        assertTrue(loaded.canExtract(loaded.reference(), ResourceTypes.FE));
        assertTrue(loaded.availableExact().isEmpty());
        assertEquals(LARGE, loaded.uncertainTransferExact().get(ResourceTypes.EMC));
        assertThrows(IllegalArgumentException.class, () -> loaded.resolveTransferExact(OWNER, loaded.transferTicket(), LARGE.add(BigInteger.ONE)));
        loaded.resolveTransferExact(OWNER, loaded.transferTicket(), LARGE.divide(BigInteger.TWO));
        assertEquals(LARGE.subtract(LARGE.divide(BigInteger.TWO)), loaded.availableExact().get(ResourceTypes.EMC));
        assertTrue(loaded.canExtract(loaded.reference()));
    }

    @Test void exactMenuDeltaAndStatusRoundTripWithoutAuthorizingClientChanges() {
        var publisher = new ResourceInventoryDelta.Publisher();
        var delta = publisher.update(Map.of(ResourceTypes.EMC, LARGE), 0);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
        try {
            delta.encode(buffer);
            var rows = new ResourceInventoryDelta.Receiver().apply(ResourceInventoryDelta.decode(buffer));
            assertEquals(LARGE, rows.getFirst().exactAmount());
        } finally {
            buffer.release();
        }
        var account = produce(new ResourceAccounts(() -> {}), LARGE);
        var status = ResourceOutputStatus.summarize(account, true);
        assertEquals(LARGE, ResourceOutputStatus.load(status.save()).balances().getFirst().exactAmount());
    }
}
