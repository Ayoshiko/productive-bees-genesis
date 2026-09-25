package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.runtime.MachineDirectory;
import net.minecraft.util.StringRepresentable;

/** 仅表达结构状态；READY 不代表已投入生产。 */
public enum MachineVisualState implements StringRepresentable {
	UNFORMED("unformed"), CHECKING("checking"), READY("ready"), WAITING("waiting"), FAULT("fault");
	private final String name;
	MachineVisualState(String name) { this.name = name; }
	@Override public String getSerializedName() { return name; }
	public static MachineVisualState from(MachineDirectory.State state) {
		return switch (state) {
			case UNFORMED, REMOVED -> UNFORMED;
			case VALIDATING, REBUILDING -> CHECKING;
			case FORMED -> READY;
			case SUSPENDED -> WAITING;
			case RECOVERY -> FAULT;
		};
	}
}
