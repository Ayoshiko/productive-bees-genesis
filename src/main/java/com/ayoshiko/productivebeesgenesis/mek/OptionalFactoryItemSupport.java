package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMEFactoryItemSupport;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEFactoryItemSupport;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Guarded facade for item metadata supplied by optional Mekanism addons. */
public final class OptionalFactoryItemSupport {

	private static final AtomicBoolean ME_FAILURE_LOGGED = new AtomicBoolean();
	private static final AtomicBoolean EME_FAILURE_LOGGED = new AtomicBoolean();

	private OptionalFactoryItemSupport() {
	}

	@Nullable
	public static Component colorizeName(Block block, Component baseName) {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			try {
				Component colored = MEFactoryItemSupport.colorizeName(block, baseName);
				if (colored != null) return colored;
			} catch (LinkageError | RuntimeException error) {
				logOptionalFailure("Mekanism Extras", error, ME_FAILURE_LOGGED);
			}
		}
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			try {
				return EMEFactoryItemSupport.colorizeName(block, baseName);
			} catch (LinkageError | RuntimeException error) {
				logOptionalFailure("Evolved Mekanism Extras", error, EME_FAILURE_LOGGED);
			}
		}
		return null;
	}

	public static boolean hasEmeFactoryType(Block block) {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) return false;
		try {
			return EMEFactoryItemSupport.hasFactoryType(block);
		} catch (LinkageError | RuntimeException error) {
			logOptionalFailure("Evolved Mekanism Extras", error, EME_FAILURE_LOGGED);
			return false;
		}
	}

	public static boolean appendEmeFactoryType(Block block, List<Component> tooltip) {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) return false;
		try {
			return EMEFactoryItemSupport.appendFactoryType(block, tooltip);
		} catch (LinkageError | RuntimeException error) {
			logOptionalFailure("Evolved Mekanism Extras", error, EME_FAILURE_LOGGED);
			return false;
		}
	}

	private static void logOptionalFailure(String addon, Throwable error, AtomicBoolean logged) {
		if (logged.compareAndSet(false, true)) {
			ProductiveBeesGenesis.LOGGER.warn("{} 物品元数据兼容层不可用，已安全降级", addon, error);
		}
	}
}
