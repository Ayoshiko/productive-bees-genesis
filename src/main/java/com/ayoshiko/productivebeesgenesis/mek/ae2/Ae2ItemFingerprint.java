package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.TagParser;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stable, component-aware serialization for configured AE2 item keys. */
public final class Ae2ItemFingerprint {

	private Ae2ItemFingerprint() {
	}

	public static String encode(AEItemKey key, HolderLookup.Provider registries) {
		if (key == null || registries == null) return "";
		return key.toTag(registries).toString();
	}

	/**
	 * 带长度上限的安全编码：空串、超长或编码异常都返回 null。
	 * <p>
	 * 网络层（条目落库）与条目维护（模糊升级 / 键对齐）都必须拒绝超长指纹 ——
	 * 超过 {@code MAX_FILTER_ENTRY_LENGTH} 的条目会被客户端整包拒收，界面从此不再刷新。
	 */
	@Nullable
	public static String encodeBounded(AEItemKey key, HolderLookup.Provider registries, int maxLength) {
		try {
			String fingerprint = encode(key, registries);
			return fingerprint.isBlank() || fingerprint.length() > maxLength ? null : fingerprint;
		} catch (RuntimeException error) {
			// 单个键编码失败不应中断整批处理，交由调用方跳过该条目
			return null;
		}
	}

	/**
	 * 绝不抛异常的编码：正常返回组件感知 SNBT 指纹，编码失败时退回
	 * {@code key.toString()} 这一 legacy 格式（{@link #matchesLegacy} 仍可识别）。
	 * <p>
	 * <b>用途</b>：{@code Ae2InputPuller} 在 ME extract <b>之后</b>登记剩余物时使用。
	 * 抽取已不可撤回，此刻抛异常就等于物品丢失，因此必须有兜底键 ——
	 * legacy 指纹最坏只会让该条目在 {@code retryPendingItems} 中解析失败、
	 * 保留在缓冲里等待迁移（有告警、不丢物品），远优于直接失去物品。
	 * <p>
	 * 正常环境下不会走到兜底：{@code AEItemKey.toTag} 对合法键恒返回非空标签。
	 */
	public static String encodeOrLegacy(AEItemKey key, HolderLookup.Provider registries) {
		if (key == null) return "";
		try {
			String encoded = encode(key, registries);
			if (!encoded.isBlank()) return encoded;
		} catch (LinkageError | RuntimeException ignored) {
			// 落到 legacy 指纹，保证抽取后的剩余物一定能被登记
		}
		return key.toString();
	}

	@Nullable
	public static AEItemKey decode(String fingerprint, HolderLookup.Provider registries) {
		if (fingerprint == null || fingerprint.isBlank() || registries == null
				|| fingerprint.charAt(0) != '{') {
			return null;
		}
		try {
			return AEItemKey.fromTag(registries, TagParser.parseTag(fingerprint));
		} catch (CommandSyntaxException | RuntimeException ignored) {
			return null;
		}
	}

	/** Compatibility fallback for direct entries written before component-aware fingerprints. */
	public static boolean matchesLegacy(AEItemKey key, String fingerprint) {
		return key != null && fingerprint != null && fingerprint.equals(key.toString());
	}

	/** Resolves new SNBT fingerprints without scanning and old fingerprints with one snapshot pass. */
	public static Map<String, AEItemKey> resolve(List<Ae2InputFilter.DirectEntry> entries,
			KeyCounter cachedInventory, HolderLookup.Provider registries) {
		Map<String, AEItemKey> resolved = null;
		Set<String> legacy = null;
		for (Ae2InputFilter.DirectEntry entry : entries) {
			if (entry.key() != null) continue;
			AEItemKey decoded = decode(entry.fingerprint(), registries);
			if (decoded != null) {
				if (resolved == null) resolved = new HashMap<>();
				resolved.put(entry.fingerprint(), decoded);
			} else {
				if (legacy == null) legacy = new HashSet<>();
				legacy.add(entry.fingerprint());
			}
		}
		if (legacy == null || legacy.isEmpty() || cachedInventory == null) {
			return resolved == null ? Map.of() : resolved;
		}
		for (var stack : cachedInventory) {
			if (!(stack.getKey() instanceof AEItemKey itemKey)) continue;
			String fingerprint = itemKey.toString();
			if (legacy.contains(fingerprint)) {
				if (resolved == null) resolved = new HashMap<>();
				resolved.put(fingerprint, itemKey);
			}
		}
		return resolved == null ? Map.of() : resolved;
	}
}
