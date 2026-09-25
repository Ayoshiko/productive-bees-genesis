package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

/** 每个客户端 BE 持有一个收件槽；不保留世界或历史帧，也不写回服务端身份。 */
public final class MachineVisualInbox {
	private long acceptedRevision;
	private MachineVisualSnapshot current;
	public boolean accept(CompoundTag tag) {
		var decoded = MachineVisualSnapshot.decode(tag);
		if (decoded.isEmpty()) { clear(); return false; }
		var next = decoded.get();
		if (next.revision() <= acceptedRevision) return false;
		acceptedRevision = next.revision(); current = next; return true;
	}
	public Optional<MachineVisualSnapshot> current() { return Optional.ofNullable(current); }
	/** 清除画面但保留版本水位，旧帧不能在失效后重新出现。 */
	public void clear() { current = null; }
}
