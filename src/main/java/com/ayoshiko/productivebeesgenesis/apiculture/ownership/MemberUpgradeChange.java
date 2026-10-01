package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeWorkState;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.NativeUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbCentrifugeUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import mekanism.api.Upgrade;
import net.minecraft.nbt.Tag;

/** 单成员升级交换凭据；不开放任意替换封存资产或重写在制作业的入口。 */
public final class MemberUpgradeChange {
	private final OwnedMachineRecord source, candidate;
	private final int delta, installed;
	private MemberUpgradeChange(OwnedMachineRecord source, OwnedMachineRecord candidate, int delta, int installed) {
		this.source = source; this.candidate = candidate; this.delta = delta; this.installed = installed;
	}
	public static MemberUpgradeChange speed(OwnedMachineRecord source, int delta) {
		return prepare(source, Upgrade.SPEED, delta, 0);
	}
	/** 容量由服务器适配器按本机基础容量和变更后数量预检；拒绝裁掉仍归成员所有的 FE。 */
	public static MemberUpgradeChange energy(OwnedMachineRecord source, int delta, long capacity) {
		if (capacity <= 0) throw new IllegalArgumentException("Positive energy capacity required");
		return prepare(source, Upgrade.ENERGY, delta, capacity);
	}
	/** 新安装受当前上限约束；旧超限数量允许逐次取回，不能在加载或拆除时裁掉。 */
	public static MemberUpgradeChange pb(OwnedMachineRecord source, PbUpgradeType upgrade, int delta, int installLimit) {
		validateSource(source, delta);
		if (!PbCentrifugeUpgradeCounts.supported(upgrade)) throw new IllegalArgumentException("Unsupported PB upgrade");
		var image = source.assets().copy(); var extra = image.getCompound("extra");
		int count = Math.addExact(PbCentrifugeUpgradeCounts.read(extra).getOrDefault(upgrade, 0), delta);
		if (delta > 0 && (installLimit < 1 || count > installLimit)) throw new IllegalArgumentException("PB install limit exceeded");
		image.put("extra", PbCentrifugeUpgradeCounts.withCount(extra, upgrade, count));
		return finish(source, image, source.centrifuge().energyCapacity(), delta, count);
	}
	private static void validateSource(OwnedMachineRecord source, int delta) {
		if (source == null || source.phase() != OwnedMachineRecord.Phase.OWNED || source.centrifuge() == null
				|| !source.claim().machine().equals("productivebeesgenesis:mek_centrifuge") || delta == 0 || delta < -64 || delta > 64)
			throw new IllegalArgumentException("Upgrade exchange requires an activated basic centrifuge");
	}
	private static MemberUpgradeChange prepare(OwnedMachineRecord source, Upgrade upgrade, int delta, long capacity) {
		validateSource(source, delta);
		var image = source.assets().copy();
		if (!image.contains("upgrades", Tag.TAG_COMPOUND)) throw new IllegalArgumentException("Missing native upgrade component");
		var component = image.getCompound("upgrades");
		int old = NativeUpgradeCounts.read(component).getOrDefault(upgrade, 0);
		if (old > upgrade.getMax()) throw new IllegalArgumentException("Existing upgrade count exceeds current limit");
		int count = Math.addExact(old, delta);
		image.put("upgrades", NativeUpgradeCounts.withCount(component, upgrade, count));
		var work = source.centrifuge();
		if (upgrade == Upgrade.SPEED) capacity = work.energyCapacity();
		if (capacity < work.energy()) throw new IllegalArgumentException("Member energy exceeds the new capacity");
		image.putLong("energyCapacity", capacity);
		return finish(source, image, capacity, delta, count);
	}
	private static MemberUpgradeChange finish(OwnedMachineRecord source, net.minecraft.nbt.CompoundTag image, long capacity, int delta, int count) {
		var assets = new AssetImage(image); var work = source.centrifuge();
		// 作业对象及其投入、种子、费用、进度保持不变；revision 使所有旧候选失效。
		var nextWork = new CentrifugeWorkState(work.member(), Math.incrementExact(work.revision()), work.laneCount(),
				work.energy(), capacity, work.jobs(), work.networkPowered());
		var next = new OwnedMachineRecord(source.claim(), source.phase(), assets, assets.fingerprint(), "", null, nextWork);
		return new MemberUpgradeChange(source, next, delta, count);
	}
	public boolean matches(OwnedMachineRecord record) { return record == source; }
	public OwnedMachineRecord candidate() { return candidate; }
	public int delta() { return delta; }
	public int installed() { return installed; }
}
