package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberWorkLayout;
import java.util.Objects;
import java.util.UUID;

/** 身份可去重的调度项；注册和两种生产任务不共享槽号命名空间。 */
sealed interface MemberWorkTask {
	UUID member();

	record Activate(UUID member) implements MemberWorkTask {
		public Activate { Objects.requireNonNull(member); }
	}
	record Register(UUID member) implements MemberWorkTask {
		public Register { Objects.requireNonNull(member); }
	}
	record Produce(UUID member, MemberWorkLayout.Slot slot) implements MemberWorkTask {
		public Produce { Objects.requireNonNull(member); Objects.requireNonNull(slot); }
	}
}
