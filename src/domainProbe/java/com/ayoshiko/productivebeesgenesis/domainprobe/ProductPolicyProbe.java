package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbProductPolicyCompiler;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicySnapshot;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class ProductPolicyProbe {
	static ProductPolicySnapshot verify(ServerLevel level, JsonObject report) {
		var compiled = PbProductPolicyCompiler.compile(level, 1);
		var registry = new ProductPolicyRegistry(compiled.snapshot());
		report.addProperty("staticDescriptorCount", compiled.snapshot().descriptorCount());
		report.addProperty("policyDiagnostics", String.join("; ", compiled.diagnostics()));
		require(compiled.snapshot().descriptorCount() > 100, "Static catalog did not compile");
		for (String beeName : List.of("iron", "gold", "diamond")) {
			var bee = ResourceLocation.parse("productivebees:" + beeName);
			var recipe = CentrifugeRecipeIndex.get(bee);
			require(recipe != null, "Missing centrifuge reference " + beeName);
			var comb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			comb.set(ModDataComponents.BEE_TYPE.get(), bee);
			require(registry.evaluate(ProductKeyCodec.item(comb, level.registryAccess())).allowed(), "Valid comb refused " + beeName);
			for (var output : recipe.value().getRecipeOutputs().keySet()) {
				var key = ProductKeyCodec.item(output, level.registryAccess());
				require(registry.evaluate(key).allowed(), "Actual centrifuge output refused " + output);
				var renamed = output.copy(); renamed.set(DataComponents.CUSTOM_NAME, Component.literal("External product"));
				require(registry.evaluate(ProductKeyCodec.item(renamed, level.registryAccess())).allowed(), "Named external product refused");
				renamed.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.COMMAND_BLOCK))));
				require(!registry.evaluate(ProductKeyCodec.item(renamed, level.registryAccess())).allowed(), "Nested arbitrary contents accepted");
			}
			var fluid = recipe.value().getFluidOutputs();
			if (!fluid.isEmpty()) require(registry.evaluate(ProductKeyCodec.fluid(fluid, level.registryAccess())).allowed(), "Actual fluid refused");
		}
		var wrong = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		wrong.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("test:missing_bee"));
		require(!registry.evaluate(ProductKeyCodec.item(wrong, level.registryAccess())).allowed(), "Unknown bee accepted");
		require(!registry.evaluate(ProductKeyCodec.item(new ItemStack(Items.COMMAND_BLOCK), level.registryAccess())).allowed(), "Non-product accepted");
		registry.replace(new ProductPolicySnapshot(2, List.of(), List.of()));
		require(!registry.evaluate(ProductKeyCodec.item(new ItemStack(Items.DIAMOND), level.registryAccess())).allowed(), "Old policy survived replacement");
		report.addProperty("productPolicyAdmission", true);
		return compiled.snapshot();
	}
}
