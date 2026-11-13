package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.api.resource.RegisterResourceRecipesEvent;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.common.NeoForge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceProductionApiMinecraftTest {
	private static final ResourceLocation BEE = ResourceLocation.parse("api_test:hydrogen");
	private static final ResourceLocation ID = ResourceLocation.parse("api_test:production");
	private static ResourceProductionRecipe recipe(long amount) {
		return new ResourceProductionRecipe(BEE,
				List.of(new ResourceProductionRecipe.Output(ResourceLocation.parse("mekanism:hydrogen"), amount, 1)));
	}

	@Test void registrationRejectsDuplicatesAndRetainedOrOffThreadMutation() {
		var event = new RegisterResourceRecipesEvent(1);
		event.register(ID, recipe(100));
		assertThrows(IllegalArgumentException.class, () -> event.register(ID, recipe(200)));
		CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class,
				() -> event.register(ResourceLocation.parse("api_test:background"), recipe(200)))).join();
		var registered = event.finish();
		assertEquals(100, registered.getFirst().value().outputs().getFirst().amount());
		assertThrows(UnsupportedOperationException.class, () -> registered.clear());
		assertThrows(IllegalStateException.class, () -> event.register(ID, recipe(100)));
	}

	@Test void actualEventDefaultsYieldToDatapackWithoutDuplicatingTheRecipe() {
		Consumer<RegisterResourceRecipesEvent> listener = event -> event.register(ID, recipe(100));
		NeoForge.EVENT_BUS.addListener(listener);
		try {
			var defaults = ResourceProductionApi.collect(1, List.of());
			assertEquals(100, defaults.forBee(BEE).getFirst().value().outputs().getFirst().amount());
			var overridden = ResourceProductionApi.collect(2, List.of(new RecipeHolder<>(ID, recipe(250))));
			assertEquals(1, overridden.forBee(BEE).size());
			assertEquals(250, overridden.forBee(BEE).getFirst().value().outputs().getFirst().amount());
			assertEquals(100, defaults.forBee(BEE).getFirst().value().outputs().getFirst().amount());
		} finally { NeoForge.EVENT_BUS.unregister(listener); }
	}

	@Test void throwingListenerCannotPublishOrContinueEditingPartialRegistration() {
		RegisterResourceRecipesEvent[] captured = new RegisterResourceRecipesEvent[1];
		Consumer<RegisterResourceRecipesEvent> listener = event -> {
			captured[0] = event;
			event.register(ID, recipe(100));
			throw new IllegalStateException("Injected registration failure");
		};
		NeoForge.EVENT_BUS.addListener(listener);
		try {
			assertThrows(RuntimeException.class, () -> ResourceProductionApi.collect(1, List.of()));
			assertThrows(IllegalStateException.class, () -> captured[0].register(ResourceLocation.parse("api_test:late"), recipe(9)));
		} finally { NeoForge.EVENT_BUS.unregister(listener); }
	}

}
