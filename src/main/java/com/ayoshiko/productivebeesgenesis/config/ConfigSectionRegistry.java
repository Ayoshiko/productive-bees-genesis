package com.ayoshiko.productivebeesgenesis.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
	 * 配置段注册表 — 聚合 {@link ServerConfig} 的全部子配置段并提供统一查找入口（Task 12 抽取）。
	 * <p>
	 * 遵循单一职责原则（SRP）：将配置段的创建调用、实例持有与按名查找逻辑从
	 * {@link ServerConfig} 抽离，使 {@link ServerConfig} 仅保留 Builder 入口与基础配置定义。
	 * <p>
	 * 当前聚合的配置段：
	 * <ul>
	 *   <li>{@link BeeAttributeConfigSection} — 万象创世蜜蜂属性覆盖配置（bee_attributes.*）</li>
	 *   <li>{@link CentrifugeConfigSection} — MEK 离心机配置（mek_centrifuge.*）</li>
	 *   <li>{@link ApiaryConfigSection} — MEK 通用机械蜂箱配置（mek_apiary.*）</li>
	 * </ul>
	 * <p>
	 * 注册方法（{@link #registerBeeAttributes}、{@link #registerCentrifuge}、{@link #registerApiary}）
	 * 由 {@link ServerConfig} 构造函数在对应 Builder 层级位置调用，以保持配置文件中节的顺序
	 * 与抽取前完全一致。每个配置段内部自行管理 {@code builder.push/pop}，调用方需保证在合适的
	 * 层级顺序中调用，以维持配置文件中节的顺序与抽取前一致。
	 * <p>
	 * 外部访问路径 {@code ModConfig.SERVER.xxx}（向后兼容委托字段）保持不变。
	 *
	 * @see ServerConfig
	 * @see BeeAttributeConfigSection
	 * @see CentrifugeConfigSection
	 * @see ApiaryConfigSection
	 */
public final class ConfigSectionRegistry {

	/** 万象创世蜜蜂属性配置段（注册后非 null） */
	private BeeAttributeConfigSection beeAttributes;
	/** MEK 离心机配置段（注册后非 null） */
	private CentrifugeConfigSection centrifuge;
	/** MEK 通用机械蜂箱配置段（注册后非 null） */
	private ApiaryConfigSection apiary;

	/**
	 * 构造空的配置段注册表。
	 * <p>
	 * 实例创建后需依次调用 {@link #registerBeeAttributes}、{@link #registerCentrifuge}、
	 * {@link #registerApiary} 完成配置段注册。注册顺序需与原 {@link ServerConfig} 构造函数
	 * 中的调用位置一致，以保证配置文件中节的顺序不变。
	 */
	public ConfigSectionRegistry() {
	}

	/**
	 * 注册万象创世蜜蜂属性配置段。
	 * <p>
	 * 委托至 {@link BeeAttributeConfigSection#create(ModConfigSpec.Builder)}，
	 * 执行 {@code builder.push("bee_attributes")} ... {@code builder.pop()}。
	 *
	 * @param builder NeoForge 配置构建器
	 * @return 已注册的蜜蜂属性配置段实例
	 */
	public BeeAttributeConfigSection registerBeeAttributes(ModConfigSpec.Builder builder) {
		this.beeAttributes = BeeAttributeConfigSection.create(builder);
		return this.beeAttributes;
	}

	/**
	 * 注册 MEK 离心机配置段。
	 * <p>
	 * 委托至 {@link CentrifugeConfigSection#create(ModConfigSpec.Builder)}，
	 * 执行 {@code builder.push("mek_centrifuge")} ... {@code builder.pop()}。
	 *
	 * @param builder NeoForge 配置构建器
	 * @return 已注册的离心机配置段实例
	 */
	public CentrifugeConfigSection registerCentrifuge(
			ModConfigSpec.Builder builder,
			ModConfigSpec.Builder capacityBuilder) {
		this.centrifuge = CentrifugeConfigSection.create(builder, capacityBuilder);
		return this.centrifuge;
	}

	/**
	 * 注册 MEK 通用机械蜂箱配置段。
	 * <p>
	 * 委托至 {@link ApiaryConfigSection#create(ModConfigSpec.Builder)}，
	 * 执行 {@code builder.push("mek_apiary")} ... {@code builder.pop()}。
	 *
	 * @param builder NeoForge 配置构建器
	 * @return 已注册的蜂箱配置段实例
	 */
	public ApiaryConfigSection registerApiary(
			ModConfigSpec.Builder builder,
			ModConfigSpec.Builder capacityBuilder) {
		this.apiary = ApiaryConfigSection.create(builder, capacityBuilder);
		return this.apiary;
	}

	/**
	 * 获取万象创世蜜蜂属性配置段。
	 *
	 * @return 蜜蜂属性配置段实例（未注册时为 {@code null}）
	 */
	public BeeAttributeConfigSection beeAttributes() {
		return beeAttributes;
	}

	/**
	 * 获取 MEK 离心机配置段。
	 *
	 * @return 离心机配置段实例（未注册时为 {@code null}）
	 */
	public CentrifugeConfigSection centrifuge() {
		return centrifuge;
	}

	/**
	 * 获取 MEK 通用机械蜂箱配置段。
	 *
	 * @return 蜂箱配置段实例（未注册时为 {@code null}）
	 */
	public ApiaryConfigSection apiary() {
		return apiary;
	}
}
