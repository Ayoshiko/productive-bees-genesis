package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;
import static com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole.*;

/** 独立机器注册不依赖蜂业核心或可选模组；当前只开放结构，不提供产能。 */
public final class MachineContent {
	public interface RoleBlock { StructureRole role(); }
	private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("productivebeesgenesis");
	private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("productivebeesgenesis");
	private static final DeferredRegister<BlockEntityType<?>> TILES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "productivebeesgenesis");
	private static final Map<StructureRole, DeferredBlock<? extends Block>> PARTS = new ConcurrentHashMap<>();
	static {
		for (var role : new StructureRole[]{FRAME, CASING, GLASS, CONTROLLER, CORE, APIARY_UNIT, CENTRIFUGE_UNIT, INTERFACE, ENERGY_PORT, INPUT_PORT, OUTPUT_PORT}) {
			String name = "combined_apiary_" + role.name().toLowerCase(java.util.Locale.ROOT);
			var block = BLOCKS.register(name, () -> role == FRAME || role == CASING || role == GLASS ? new MachineShellBlock(role) : new MachinePartBlock(role));
			PARTS.put(role, block); ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
		}
	}
	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachineControllerEntity>> CONTROLLER_TILE = TILES.register("combined_apiary_controller",
			() -> BlockEntityType.Builder.of(MachineControllerEntity::new, block(CONTROLLER)).build(null));
	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MachinePartEntity>> PART_TILE = TILES.register("combined_apiary_part",
			() -> BlockEntityType.Builder.of(MachinePartEntity::new, block(CORE), block(APIARY_UNIT), block(CENTRIFUGE_UNIT), block(INTERFACE), block(ENERGY_PORT), block(INPUT_PORT), block(OUTPUT_PORT)).build(null));
	public static Block block(StructureRole role) { return PARTS.get(role).get(); }
	public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); TILES.register(bus); }
	private MachineContent() { }
}
