package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Set;
import java.util.UUID;

/** 升级失败只保管完整来源和已到达阶段；快照不是第二份可花费库存，不能自动重放。 */
public final class ApiaryUpgradeRecovery {
	static final String NBT_KEY = "productivebeesgenesis_upgrade_recovery";
	private static final Set<String> FIELDS = Set.of("version", "request", "source", "phase", "failure", "settled");
	private final TileEntityMekApiary tile;
	private Tag raw;
	private boolean executing;

	ApiaryUpgradeRecovery(TileEntityMekApiary tile) {
		this.tile = tile;
	}

	public boolean blocked() {
		return raw != null && (!readable() || !((CompoundTag) raw).getBoolean("settled"));
	}

	public UUID request() {
		return readable() ? ((CompoundTag) raw).getUUID("request") : null;
	}

	public String phase() {
		return readable() ? ((CompoundTag) raw).getString("phase") : "invalid";
	}

	public String failure() {
		return readable() ? ((CompoundTag) raw).getString("failure") : "Invalid retained upgrade data";
	}

	public Tag snapshot() {
		return raw == null ? new CompoundTag() : raw.copy();
	}

	void save(CompoundTag destination) {
		if (raw != null) {
			destination.put(NBT_KEY, raw.copy());
		}
	}

	void load(CompoundTag source) {
		Tag saved = source.get(NBT_KEY);
		raw = saved == null ? null : saved.copy();
		executing = false;
	}

	void begin(UUID request, CompoundTag source, String phase) {
		if (blocked()) {
			throw new IllegalStateException("Previous upgrade still requires settlement");
		}
		CompoundTag record = new CompoundTag();
		record.putInt("version", 1);
		record.putUUID("request", request);
		record.put("source", source.copy());
		record.putString("phase", phase);
		record.putString("failure", "");
		record.putBoolean("settled", false);
		raw = record;
		executing = true;
		tile.setChanged();
	}

	void stage(String phase) {
		if (!executing || !readable()) {
			throw new IllegalStateException("No upgrade is being applied");
		}
		((CompoundTag) raw).putString("phase", phase);
		tile.setChanged();
	}

	void sourceCleared() {
		stage("source_cleared");
		executing = false;
	}

	void complete() {
		raw = null;
		executing = false;
		tile.setChanged();
	}

	void transferred(UUID expected) {
		if (!expected.equals(request()) || !phase().equals("source_cleared")) {
			throw new IllegalStateException("Upgrade source was not completely released");
		}
		complete();
	}

	void fail(Throwable failure) {
		if (raw instanceof CompoundTag record && record.getString("failure").isEmpty()) {
			String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
			record.putString("failure", message.substring(0, Math.min(512, message.length())));
		}
		executing = false;
		tile.setChanged();
	}

	/** 仅管理员核验全部来源、目标和资源账户后解除暂停；不改库存，不认领账户，不执行快照。 */
	public void acknowledgeSettled(UUID expected) {
		if (tile.getLevel() != null && (tile.getLevel().isClientSide || tile.getLevel().getServer() == null
				|| !tile.getLevel().getServer().isSameThread() || tile.isRemoved()
				|| tile.getLevel().getBlockEntity(tile.getBlockPos()) != tile)) {
			throw new IllegalStateException("Upgrade recovery requires its current server host");
		}
		if (executing || !blocked() || expected == null || !expected.equals(request())) {
			throw new IllegalStateException("Stale, invalid or executing upgrade request");
		}
		((CompoundTag) raw).putBoolean("settled", true);
		tile.setChanged();
	}

	private boolean readable() {
		return raw instanceof CompoundTag record
				&& FIELDS.equals(record.getAllKeys())
				&& record.contains("version", Tag.TAG_INT) && record.getInt("version") == 1
				&& record.hasUUID("request")
				&& record.contains("source", Tag.TAG_COMPOUND)
				&& record.contains("phase", Tag.TAG_STRING)
				&& record.contains("failure", Tag.TAG_STRING)
				&& record.contains("settled", Tag.TAG_BYTE)
				&& (record.getByte("settled") == 0 || record.getByte("settled") == 1);
	}
}
