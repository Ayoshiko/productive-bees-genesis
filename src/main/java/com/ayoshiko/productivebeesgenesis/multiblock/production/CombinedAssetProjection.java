package com.ayoshiko.productivebeesgenesis.multiblock.production;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim;
import net.minecraft.nbt.CompoundTag;

/** 封存映像与复合权威互斥；残余只留机器身份，不保留第二份库存。 */
public final class CombinedAssetProjection {
	public static final String MACHINE = "productivebeesgenesis:combined_apiary_controller";
	public static final String MARKER = "combinedAuthority";
	public static AssetImage capture(CombinedMachineWork work) {
		var tag = new CompoundTag(); tag.put("combinedWork", CombinedWorkCodec.encode(work)); return new AssetImage(tag);
	}
	/** 旧格式先严格解码再比较完整语义；序列化升级不能使旧已封存工作无法激活。 */
	public static boolean matches(CombinedMachineWork work, AssetImage image) {
		var tag = image.copy();
		if (!tag.getAllKeys().equals(java.util.Set.of("combinedWork"))) return false;
		var decoded = CombinedWorkCodec.decode(tag.getCompound("combinedWork"), key -> { }, key -> 64, item -> { });
		return CombinedWorkCodec.encode(decoded).equals(CombinedWorkCodec.encode(work));
	}
	public static AssetImage detach(CombinedMachineWork work) {
		var tag = new CompoundTag(); tag.putUUID(MARKER, work.machine()); tag.putLong("generation", work.generation()); return new AssetImage(tag);
	}
	public static void validate(MemberClaim claim, AssetImage residual, CombinedMachineWork work) {
		CombinedMachineCapacity.validate(work);
		if (!claim.machine().equals(MACHINE) || !claim.member().equals(work.machine()) || work.energy() != 0 || !detach(work).equals(residual))
			throw new IllegalArgumentException("Invalid combined authority identity or residual assets");
	}
	private CombinedAssetProjection() { }
}
