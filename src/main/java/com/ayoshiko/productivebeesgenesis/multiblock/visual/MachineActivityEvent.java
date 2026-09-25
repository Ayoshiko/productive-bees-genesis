package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** 不含资产的有限展示描述；尚无网络编码入口，真实工作触发须另验收共享发包预算。 */
public record MachineActivityEvent(MachineVisualSnapshot structure, long sequence, long gameTick,
                                   long seed, Activity activity, List<Appearance> resources) {
	public static final int MAX_RESOURCES = 8;
	public static final int MAX_ICONS = 24;
	public enum Activity {
		APIARY(0, 55), CENTRIFUGE(55, 160), COMBINED(0, 160);
		private final int start, end;
		Activity(int start, int end) { this.start = start; this.end = end; }
		public int start() { return start; }
		public int duration() { return end - start; }
	}
	public enum ResourceKind { ITEM, FLUID }
	/** 仅资源注册名；不携带数量、组件或自定义渲染器数据。 */
	public record Appearance(ResourceKind kind, ResourceLocation id) {
		public Appearance {
			Objects.requireNonNull(kind); Objects.requireNonNull(id);
			if (id.toString().length() > 128) throw new IllegalArgumentException("Appearance id too long");
		}
	}
	public MachineActivityEvent {
		Objects.requireNonNull(structure); Objects.requireNonNull(activity); Objects.requireNonNull(resources);
		if (structure.variant() < 0 || sequence < 1 || gameTick < 0 || resources.size() > MAX_RESOURCES) {
			throw new IllegalArgumentException("Invalid activity description");
		}
		resources = List.copyOf(resources);
		if (new HashSet<>(resources).size() != resources.size()) throw new IllegalArgumentException("Duplicate appearance");
	}
}
