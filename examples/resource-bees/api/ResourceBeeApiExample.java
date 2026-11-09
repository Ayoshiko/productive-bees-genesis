package example.resourcebees;

import com.ayoshiko.productivebeesgenesis.api.resource.*;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import java.util.List;

/**
 * Minimal integration example, compiled with api-example.gradle.
 * The consuming mod registers its own block/entity type and calls the two registration methods from
 * the corresponding NeoForge game-bus listeners. Server input handling supplies credits.
 * Each completed operation consumes one credit and creates 100 mB hydrogen for example:hydrogen.
 */
public final class ResourceBeeApiExample {
	public static final ResourceLocation BEE = ResourceLocation.parse("example:hydrogen");
	private ResourceBeeApiExample() {}

	public static void registerRecipe(RegisterResourceRecipesEvent event) {
		event.register(ResourceLocation.parse("example:api_hydrogen"), BEE, List.of(
				new ResourceProductionRecipe.Output(ResourceLocation.parse("mekanism:hydrogen"), 100, 1)));
	}

	/**
	 * Register any installed mod's AEKey through the same output API, with no per-resource producer.
	 * type/id/data must come from that key's real AE2 codec; data is the full serialized identity.
	 * The runtime verifies round-trip identity and requires storage accepting this key. ME export only.
	 */
	public static void registerCustomAeKey(RegisterResourceRecipesEvent event, ResourceLocation type,
			ResourceLocation id, CompoundTag data) {
		var key = com.ayoshiko.productivebeesgenesis.resource.ResourceKey.aeKey(type, id, data);
		event.register(ResourceLocation.parse("example:api_custom_resource"),
				ResourceLocation.parse("example:ae_key"), List.of(new ResourceProductionRecipe.Output(key, 1000, 1)));
	}

	/** Opt-in Genesis centrifuging: the ordinary PB recipe must also exist for this input. */
	public static void registerCombinedCentrifuge(RegisterResourceRecipesEvent event) {
		event.registerCentrifuging(ResourceLocation.parse("example:combined_centrifuging"),
				new com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe(
						ResourceLocation.parse("example:combined"), List.of(new ResourceProductionRecipe.Output(
								com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.FE, 1000, 1)), 20, true));
	}

	public static void registerHost(RegisterResourceHostsEvent event, BlockEntityType<?> type) {
		event.register(type, new ResourceHostAdapter() {
			public boolean ready(BlockEntity host) { return host instanceof Producer; }
			public boolean canAccess(ServerPlayer player, BlockEntity host) {
				// Add the consuming mod's own security check here; the API also checks account ownership.
				return host instanceof Producer;
			}
			public boolean hasLiveWork(BlockEntity host) { return ((Producer) host).credits > 0; }
			public long capacity(BlockEntity host) { return 1000; }
		});
	}

	/**
	 * Optional synchronous output of hydrogen already owned by the Genesis account.
	 * Acquire the current adjacent capability on the server thread before calling this method.
	 * Do not catch a receiver failure and return zero: transfer retains the uncertain request for review.
	 */
	public static long exportHydrogen(Producer host, mekanism.api.chemical.IChemicalHandler receiver, long maximum) {
		java.util.Objects.requireNonNull(receiver);
		var id = ResourceLocation.parse("mekanism:hydrogen");
		var chemical = mekanism.api.MekanismAPI.CHEMICAL_REGISTRY.getHolder(id).orElseThrow();
		return ResourceHostApi.transfer(host, com.ayoshiko.productivebeesgenesis.resource.ResourceKey.chemical(id),
				maximum, offer -> {
			var stack = new mekanism.api.chemical.ChemicalStack(chemical, offer.amount());
			var remainder = java.util.Objects.requireNonNull(receiver.insertChemical(stack, mekanism.api.Action.EXECUTE));
			long left = remainder.getAmount();
			if (left < 0 || left > offer.amount() || (!remainder.isEmpty() && remainder.getChemical() != chemical.value())) {
				throw new IllegalStateException("Receiver returned an invalid chemical remainder");
			}
			return offer.amount() - left;
		});
	}

	/** A consuming mod can use the same pattern in its existing tile, without inheriting this class. */
	public static class Producer extends BlockEntity {
		private int credits;
		private long nextOperation;
		public Producer(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }

		/** Called only after the consuming mod validates its own server-side input transfer. */
		public void supply(int amount) {
			if (level == null || level.isClientSide || !level.getServer().isSameThread() || amount < 0) throw new IllegalStateException("Server input required");
			credits = Math.addExact(credits, amount);
			setChanged();
		}
		public int credits() { return credits; }
		public long nextOperation() { return nextOperation; }

		public boolean finishOneCycle() {
			if (credits == 0) return false;
			long operation = nextOperation;
			long next = Math.incrementExact(operation);
			int before = credits;
			var context = new CompoundTag();
			context.putLong("operation", operation);
			context.putInt("credits_before", before);
			try (var reservation = ResourceHostApi.reserve(this, BEE, 1, 1, 0, context).orElse(null)) {
				if (reservation == null) return false;
				reservation.execute(() -> {
					if (nextOperation != operation || credits != before) throw new IllegalStateException("Operation already committed or input changed");
					credits--;
					nextOperation = next;
				});
				return true;
			}
		}

		@Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
			super.saveAdditional(tag, provider);
			tag.putInt("credits", credits);
			tag.putLong("next_operation", nextOperation);
		}
		@Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
			super.loadAdditional(tag, provider);
			credits = tag.getInt("credits");
			nextOperation = tag.getLong("next_operation");
			if (credits < 0 || nextOperation < 0) throw new IllegalArgumentException("Invalid example producer state");
		}
	}
}
