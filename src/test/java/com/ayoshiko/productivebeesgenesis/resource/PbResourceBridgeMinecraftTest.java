package com.ayoshiko.productivebeesgenesis.resource;

import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class PbResourceBridgeMinecraftTest {
	@Test void nativeHiveKeepsItsOriginalProductionAndCannotBindNewResources() {
		var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebees:advanced_oak_beehive"))
				.defaultBlockState();
		var hive = new AdvancedBeehiveBlockEntity(BlockPos.ZERO, state);
		assertFalse(hive instanceof PbResourceBridge);
		assertFalse(ResourceHiveHost.ready(hive));
		assertNull(ResourceHiveHost.existing(hive));
	}

	@Test void malformedAttachmentRootsRoundTripThroughNeoForge() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var list = new net.minecraft.nbt.ListTag();
		list.add(net.minecraft.nbt.StringTag.valueOf("retained"));
		for (var raw : List.<net.minecraft.nbt.Tag>of(net.minecraft.nbt.StringTag.valueOf("future"), list)) {
			var attachments = new CompoundTag();
			attachments.put("productivebeesgenesis:resource_host", raw);
			var holder = new HostAttachments();
			holder.read(provider, attachments);
			var host = holder.getData(ResourceHiveHost.TYPE);
			assertEquals("invalid_host_data", host.status());
			assertNull(host.reference());
			assertEquals(attachments, holder.serializeAttachments(provider));
		}
	}

	private static final class HostAttachments extends net.neoforged.neoforge.attachment.AttachmentHolder {
		void read(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
			deserializeAttachments(provider, tag);
		}
	}

	@Test void invalidHostReferenceRetainsRawDataInsteadOfBecomingNewEmptyHost() {
		var raw = new CompoundTag();
		raw.putInt("version", 7);
		raw.putString("opaque_reference", "retain");
		var state = new ResourceHiveHost();
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		state.deserializeNBT(provider, raw);
		assertEquals(raw, state.serializeNBT(provider));
		assertNull(state.reference());
		assertEquals("invalid_host_data", state.status());
	}
}
