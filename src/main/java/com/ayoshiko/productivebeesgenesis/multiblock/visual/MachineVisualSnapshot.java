package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureTemplate;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineVisualState;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 有限结构展示帧；不含所有者、库存、工作数据或任意尺寸。revision 仅在本次服务器连接内排序。 */
public record MachineVisualSnapshot(long revision, UUID machine, long generation, int variant,
                                    Direction facing, MachineVisualState state) {
	public static final int SCHEMA = 1;
	public static final int MAX_TAG_BYTES = 256;
	private static final Set<String> KEYS = Set.of("schema", "layout", "revision", "machine", "generation", "variant", "facing", "state");
	public MachineVisualSnapshot {
		Objects.requireNonNull(machine); Objects.requireNonNull(facing); Objects.requireNonNull(state);
		if (revision < 1 || generation < 1 || !facing.getAxis().isHorizontal()
				|| variant < -1 || variant >= CombinedApiaryDefinition.DEFINITION.candidates().size()
				|| (variant >= 0) != (state == MachineVisualState.READY)) {
			throw new IllegalArgumentException("Invalid machine visual snapshot");
		}
	}
	public boolean describes(UUID id, long generation, int variant, Direction facing, MachineVisualState state) {
		return machine.equals(id) && this.generation == generation && this.variant == variant && this.facing == facing && this.state == state;
	}
	public Optional<StructureTemplate> template() {
		return variant < 0 ? Optional.empty() : Optional.of(CombinedApiaryDefinition.DEFINITION.candidates().get(variant));
	}
	public CompoundTag encode() {
		var tag = new CompoundTag();
		tag.putInt("schema", SCHEMA); tag.putInt("layout", CombinedApiaryDefinition.DEFINITION.layoutVersion());
		tag.putLong("revision", revision); tag.putUUID("machine", machine); tag.putLong("generation", generation);
		tag.putInt("variant", variant); tag.putByte("facing", (byte) facing.get3DDataValue()); tag.putByte("state", (byte) state.ordinal());
		return tag;
	}
	public static Optional<MachineVisualSnapshot> decode(CompoundTag tag) {
		if (!tag.getAllKeys().equals(KEYS)
				|| !tag.contains("schema", Tag.TAG_INT) || tag.getInt("schema") != SCHEMA
				|| !tag.contains("layout", Tag.TAG_INT) || tag.getInt("layout") != CombinedApiaryDefinition.DEFINITION.layoutVersion()
				|| !tag.contains("revision", Tag.TAG_LONG) || !tag.hasUUID("machine")
				|| !tag.contains("generation", Tag.TAG_LONG) || !tag.contains("variant", Tag.TAG_INT)
				|| !tag.contains("facing", Tag.TAG_BYTE) || !tag.contains("state", Tag.TAG_BYTE)) return Optional.empty();
		int direction = tag.getByte("facing"), status = tag.getByte("state");
		if (direction < 0 || direction >= Direction.values().length || status < 0 || status >= MachineVisualState.values().length) return Optional.empty();
		try {
			return Optional.of(new MachineVisualSnapshot(tag.getLong("revision"), tag.getUUID("machine"), tag.getLong("generation"),
					tag.getInt("variant"), Direction.from3DDataValue(direction), MachineVisualState.values()[status]));
		} catch (IllegalArgumentException invalid) { return Optional.empty(); }
	}
}
