package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.resource.PbResourceMixinContract;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceBridge;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceProduction;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract;
import cy.jdkdigital.productivelib.event.BeeReleaseEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** 字节码预检后整组应用；每个钩子必须成功注入，禁止部分生产桥继续运行。 */
@Mixin(value = AdvancedBeehiveBlockEntityAbstract.class, priority = 900)
public abstract class PbResourceProductionMixin implements PbResourceBridge {
	@Unique
	@Override
	public boolean productivebeesgenesis$resourceBridgeReady() {
		return true;
	}

	@WrapMethod(method = PbResourceMixinContract.TICK, require = 1)
	private static void productivebeesgenesis$resourceTickRoot(ServerLevel level, BlockPos pos, BlockState state,
			AdvancedBeehiveBlockEntityAbstract hive, Operation<Void> original) {
		try (var scope = PbResourceProduction.openRoot(hive)) {
			try {
				original.call(level, pos, state, hive);
				scope.complete();
			} catch (RuntimeException | Error failure) {
				scope.failed(failure);
				throw failure;
			}
		}
	}

	@WrapMethod(method = PbResourceMixinContract.RELEASE_ALL, require = 1)
	private List<Entity> productivebeesgenesis$resourceReleaseAllRoot(BlockState state,
			BeehiveBlockEntity.BeeReleaseStatus releaseStatus, Operation<List<Entity>> original) {
		try (var scope = PbResourceProduction.openRoot((AdvancedBeehiveBlockEntityAbstract) (Object) this)) {
			try {
				var result = original.call(state, releaseStatus);
				scope.complete();
				return result;
			} catch (RuntimeException | Error failure) {
				scope.failed(failure);
				throw failure;
			}
		}
	}

	@WrapMethod(method = PbResourceMixinContract.SIMULATE, require = 1)
	private static Entity productivebeesgenesis$resourceSimulationRoot(ServerLevel level, BlockPos pos, BlockState state,
			AdvancedBeehiveBlockEntityAbstract hive, BeehiveBlockEntity.Occupant occupant, Operation<Entity> original) {
		try (var scope = PbResourceProduction.openRoot(hive)) {
			try {
				var result = original.call(level, pos, state, hive, occupant);
				scope.complete();
				return result;
			} catch (RuntimeException | Error failure) {
				scope.failed(failure);
				throw failure;
			}
		}
	}

	@WrapMethod(method = PbResourceMixinContract.RELEASE, require = 1)
	private static boolean productivebeesgenesis$resourceReleaseRoot(Level level, BlockPos pos, BlockState state,
			BeehiveBlockEntity.Occupant occupant, AdvancedBeehiveBlockEntityAbstract hive, List<Entity> released,
			BeehiveBlockEntity.BeeReleaseStatus releaseStatus, Operation<Boolean> original) {
		try (var scope = PbResourceProduction.openRoot(hive)) {
			try {
				boolean result = original.call(level, pos, state, occupant, hive, released, releaseStatus);
				scope.complete();
				return result;
			} catch (RuntimeException | Error failure) {
				scope.failed(failure);
				throw failure;
			}
		}
	}

	@WrapOperation(method = PbResourceMixinContract.TICK, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$BeeData;tick()Z"), require = 1)
	private static boolean productivebeesgenesis$resourcePauseClock(@org.spongepowered.asm.mixin.injection.Coerce Object data,
			Operation<Boolean> original, ServerLevel level, BlockPos pos, BlockState state,
			AdvancedBeehiveBlockEntityAbstract hive) {
		return PbResourceProduction.shouldTick(hive, ((com.ayoshiko.productivebeesgenesis.mixin.accessor.BeeDataAccessor) data).productivebeesgenesis$toOccupant()) && original.call(data);
	}

	@WrapOperation(method = PbResourceMixinContract.SIMULATE, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$Occupant;createEntity(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/entity/Entity;"), require = 1)
	private static Entity productivebeesgenesis$resourceSimPreflight(BeehiveBlockEntity.Occupant occupant,
			Level entityLevel, BlockPos entityPos, Operation<Entity> original,
			ServerLevel level, BlockPos pos, BlockState state, AdvancedBeehiveBlockEntityAbstract hive,
			BeehiveBlockEntity.Occupant methodOccupant) {
		if (!PbResourceProduction.beforeCreate(hive, occupant, true, BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED)) {
			return null;
		}
		var entity = original.call(occupant, entityLevel, entityPos);
		PbResourceProduction.entityCreated(hive, entity);
		return entity;
	}

	@WrapOperation(method = PbResourceMixinContract.RELEASE, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$Occupant;createEntity(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/entity/Entity;"), require = 1)
	private static Entity productivebeesgenesis$resourceReleasePreflight(BeehiveBlockEntity.Occupant occupant,
			Level entityLevel, BlockPos entityPos, Operation<Entity> original,
			Level level, BlockPos pos, BlockState state, BeehiveBlockEntity.Occupant methodOccupant,
			AdvancedBeehiveBlockEntityAbstract hive, List<Entity> released, BeehiveBlockEntity.BeeReleaseStatus releaseStatus) {
		if (!PbResourceProduction.beforeCreate(hive, occupant, false, releaseStatus)) return null;
		var entity = original.call(occupant, entityLevel, entityPos);
		PbResourceProduction.entityCreated(hive, entity);
		return entity;
	}

	@WrapOperation(method = PbResourceMixinContract.EVENT, at = @At(value = "INVOKE",
			target = "Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"), require = 1)
	private Event productivebeesgenesis$resourceObserveFinalEvent(IEventBus bus, Event event, Operation<Event> original) {
		boolean converted = event instanceof BeeReleaseEvent release && PbResourceProduction.converted(release.getBee());
		var result = original.call(bus, event);
		if (result instanceof BeeReleaseEvent release) {
			PbResourceProduction.afterEvent((AdvancedBeehiveBlockEntityAbstract) (Object) this, release, converted);
		}
		return result;
	}
}
