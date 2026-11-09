package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** 与外部模组类加载无关的不可变资源身份；数量不参与相等性。 */
public final class ResourceKey {
	/** 单个身份的 NBT 内存预算；不允许把库存内容塞进键。 */
	public static final int MAX_PAYLOAD_BYTES = 1024;
	private final ResourceLocation kind;
	private final int version;
	private final CompoundTag payload;

	/** 复制并验证原始身份；不要求对应可选模组当前已安装。 */
	public ResourceKey(ResourceLocation kind, int version, CompoundTag payload) {
		this.kind = Objects.requireNonNull(kind);
		if (version < 1 || kind.toString().length() > 256
				|| Objects.requireNonNull(payload).sizeInBytes() > MAX_PAYLOAD_BYTES) {
			throw new IllegalArgumentException("Invalid resource identity version or payload size");
		}
		this.version = version;
		this.payload = payload.copy();
	}

	/** 用注册名构造化学品身份，不加载 ChemicalStack 或 AEKey。 */
	public static ResourceKey chemical(ResourceLocation id) {
		var payload = new CompoundTag();
		payload.putString("id", Objects.requireNonNull(id).toString());
		return new ResourceKey(ResourceLocation.parse("productivebeesgenesis:chemical"), 1, payload);
	}

	/** 独立的 FE 产物身份；不等同于宿主机器的运行电池，也不代表 GTEU。 */
	public static ResourceKey energy() {
		var payload = new CompoundTag();
		payload.putString("energy_type", "FE");
		return new ResourceKey(ResourceLocation.parse("productivebeesgenesis:energy"), 1, payload);
	}

	/** 同质魔源点数的身份；不加载 Ars Nouveau，也不代表玩家魔力。 */
	public static ResourceKey source() {
		return new ResourceKey(ResourceLocation.parse("productivebeesgenesis:source"), 1, new CompoundTag());
	}

	/** Industrial Foregoing Souls 的同质灵魂计数，不代表某种实体或玩家灵魂。 */
	public static ResourceKey soul() {
		return new ResourceKey(ResourceLocation.parse("productivebeesgenesis:soul"), 1, new CompoundTag());
	}

	/** 压缩空气原始体积单位，1000 单位为 1 L；压力不是账户数量。 */
	public static ResourceKey air() {
		return new ResourceKey(ResourceLocation.parse("productivebeesgenesis:air"), 1, new CompoundTag());
	}

	/** ProjectE EMC 数量；不隐式改写任何玩家的转化知识或个人余额。 */
	public static ResourceKey emc() {
		return new ResourceKey(ResourceLocation.parse("productivebeesgenesis:emc"), 1, new CompoundTag());
	}

	/** ExperienceLib 的经验点数，不是玩家等级；不自动生成经验球。 */
	public static ResourceKey experience() {
		return new ResourceKey(ResourceLocation.parse("productivebeesgenesis:experience"), 1, new CompoundTag());
	}

	/** 两种闪电电压各自记账，未知电压不能回退成高压。 */
	public static ResourceKey lightning(String tier) {
		if (!"high_voltage".equals(tier) && !"extreme_high_voltage".equals(tier)) {
			throw new IllegalArgumentException("Unsupported lightning tier");
		}
		var payload = new CompoundTag();
		payload.putString("tier", tier);
		return new ResourceKey(ResourceLocation.parse("productivebeesgenesis:lightning"), 1, payload);
	}

	/** 包裹已注册 AEKey 的完整身份；外部类型、编码和真实 ID 在服务器准入时核验。 */
	public static ResourceKey aeKey(ResourceLocation type, ResourceLocation id, CompoundTag data) {
		var payload = new CompoundTag();
		payload.putString("type", Objects.requireNonNull(type).toString());
		payload.putString("id", Objects.requireNonNull(id).toString());
		payload.put("data", Objects.requireNonNull(data).copy());
		return new ResourceKey(ResourceLocation.parse("productivebeesgenesis:ae_key"), 1, payload);
	}

	/** 返回资源种类的稳定命名空间 ID。 */
	public ResourceLocation kind() { return kind; }
	/** 返回身份的编码版本。 */
	public int version() { return version; }
	/** 返回独立的原始身份副本。 */
	public CompoundTag payload() { return payload.copy(); }

	/** 保存原始身份，不调用可选模组 codec。 */
	public CompoundTag save() {
		var tag = new CompoundTag();
		tag.putString("kind", kind.toString());
		tag.putInt("version", version);
		tag.put("payload", payload.copy());
		return tag;
	}

	/** 严格读取身份；无效条目由账户加载器整体隔离，不猜测默认值。 */
	public static ResourceKey load(CompoundTag tag) {
		ResourceAccountData.keys(tag, java.util.Set.of("kind", "version", "payload"));
		if (!tag.contains("kind", Tag.TAG_STRING) || !tag.contains("version", Tag.TAG_INT)
				|| !tag.contains("payload", Tag.TAG_COMPOUND)) {
			throw new IllegalArgumentException("Incomplete resource identity");
		}
		return new ResourceKey(ResourceLocation.parse(tag.getString("kind")),
				tag.getInt("version"), tag.getCompound("payload"));
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ResourceKey key && version == key.version
				&& kind.equals(key.kind) && payload.equals(key.payload);
	}

	@Override
	public int hashCode() { return Objects.hash(kind, version, payload); }

	@Override
	public String toString() { return kind + "@" + version + payload; }
}
