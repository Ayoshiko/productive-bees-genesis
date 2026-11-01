package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceKeyBridge;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceVisuals;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import mekanism.api.MekanismAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.fml.ModList;

import java.math.BigInteger;
import java.util.LinkedHashMap;

/** 按连接／配方代际缓存有界显示值，不解析账户，不修改物品或资源。 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class ResourceVisuals {
	private record Visual(Component name, Object aeKey, boolean available, ResourceNativeIcons.Icon nativeIcon) {}
	private static final LinkedHashMap<ResourceKey, Visual> CACHE = new LinkedHashMap<>(16, 0.75F, true);
	private static Object connection;
	private static long revision = -1;
	private ResourceVisuals() {}
	public static void clear() { CACHE.clear(); connection = null; revision = -1; }
	@net.minecraftforge.eventbus.api.SubscribeEvent
	public static void registerReload(net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
		event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> clear());
	}

	private static Visual visual(ResourceKey key) {
		var minecraft = Minecraft.getInstance();
		long current = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (connection != minecraft.getConnection() || revision != current) {
			CACHE.clear(); connection = minecraft.getConnection(); revision = current;
		}
		var cached = CACHE.get(key);
		if (cached != null) return cached;
		Object aeKey = null;
		Component name = fallbackName(key);
		boolean available = ResourceTypes.aeKeyType(key) == null && ResourceTypes.available(key);
		try {
			if (minecraft.level != null && ModList.get().isLoaded("ae2")) {
				aeKey = Ae2ResourceKeyBridge.resolve(minecraft.level.registryAccess(), key);
				if (aeKey != null) { name = Ae2ResourceVisuals.name(aeKey); available = true; }
			}
		} catch (RuntimeException | LinkageError failure) {
			ProductiveBeesGenesis.LOGGER.warn("资源显示无法解析：{}", id(key), failure);
			aeKey = null;
		}
		var result = new Visual(name, aeKey, available, ResourceNativeIcons.resolve(key));
		if (CACHE.size() >= 256) com.ayoshiko.productivebeesgenesis.port.ForgeCollections.pollFirst(CACHE);
		CACHE.put(key, result);
		return result;
	}

	public static ResourceLocation id(ResourceKey key) {
		var chemical = ResourceTypes.chemicalId(key);
		if (chemical != null) return chemical;
		if (ResourceTypes.aeKeyType(key) != null) return new ResourceLocation(key.payload().getString("id"));
		return key.kind();
	}
	private static Component fallbackName(ResourceKey key) {
		var chemical = ResourceTypes.chemicalId(key);
		if (chemical != null) {
			return com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.containsKey(chemical)
					? com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.get(chemical).getTextComponent() : Component.literal(chemical.toString());
		}
		if (ResourceTypes.aeKeyType(key) != null || !ResourceTypes.declared(key)) return Component.literal(id(key).toString());
		String kind = ResourceTypes.lightningTier(key) == null ? key.kind().getPath() : ResourceTypes.lightningTier(key);
		return Component.translatable("resource.productivebeesgenesis." + kind);
	}
	public static Component name(ResourceKey key) { return visual(key).name().copy(); }
	public static boolean available(ResourceKey key) { return visual(key).available(); }
	/** 向下截断，缩写不能暗示拥有多于实际余额的资源；完整 long 值始终在详情中。 */
	public static String compactAmount(long value) {
		if (value < 1000) return Long.toString(value);
		String[] units = {"K", "M", "G", "T", "P", "E"};
		long divisor = 1000; int unit = 0;
		while (unit < units.length - 1 && value / divisor >= 1000) { divisor *= 1000; unit++; }
		long whole = value / divisor, tenth = value % divisor / (divisor / 10);
		return whole + (whole < 100 && tenth > 0 ? "." + tenth : "") + units[unit];
	}
	public static String compactAmount(BigInteger value) {
        if (value.bitLength() < 63) return compactAmount(value.longValueExact());
        String digits = value.toString();
        return digits.charAt(0) + "." + digits.substring(1, 3) + "e" + (digits.length() - 1);
    }
    public static String amount(BigInteger amount, int portions) {
        return amount.multiply(BigInteger.valueOf(portions)).toString();
    }
	public static String compactAmount(ResourceKey key, BigInteger value) {
		return ResourceTypes.EMC.equals(key) ? ResourceEmcFormat.format(value) : compactAmount(value);
	}
	public static String amount(ResourceKey key, BigInteger amount, int portions) {
		var value = amount.multiply(BigInteger.valueOf(portions));
		return ResourceTypes.EMC.equals(key) ? ResourceEmcFormat.format(value) : value.toString();
	}
    public static String amount(long amount, int portions) {
		return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(portions)).toString();
	}
	public static Component quantity(ResourceKey key, long amount, int portions) {
        return quantity(key, BigInteger.valueOf(amount), portions);
    }
    public static Component quantity(ResourceKey key, BigInteger amount, int portions) {
        return Component.literal(amount(key, amount, portions) + " ").append(unit(key));
    }
    public static Component unit(ResourceKey key) {
		String unit = ResourceTypes.chemicalId(key) != null ? "mB" : ResourceTypes.FE.equals(key) ? "FE"
				: ResourceTypes.EXPERIENCE.equals(key) ? "XP" : ResourceTypes.EMC.equals(key) ? "EMC" : "";
		return unit.isEmpty() ? Component.translatable("resource.productivebeesgenesis.raw_units") : Component.literal(unit);
	}

	public static void draw(GuiGraphics graphics, ResourceKey key) {
		var visual = visual(key);
		if (visual.aeKey() != null) {
			try { Ae2ResourceVisuals.draw(graphics, visual.aeKey()); return; }
			catch (RuntimeException | LinkageError failure) {
				CACHE.put(key, new Visual(visual.name(), null, visual.available(), visual.nativeIcon()));
				ProductiveBeesGenesis.LOGGER.warn("资源图标不可用：{}", id(key), failure);
			}
		}
		var chemicalId = ResourceTypes.chemicalId(key);
		if (chemicalId != null && com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.containsKey(chemicalId)) {
			var chemical = com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.get(chemicalId);
			var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(chemical.getIcon());
			int color = chemical.getTint();
			graphics.setColor((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1);
			graphics.blit(0, 0, 0, 16, 16, sprite);
			graphics.setColor(1, 1, 1, 1);
			return;
		}
		if (visual.nativeIcon() != null) {
			visual.nativeIcon().draw(graphics);
			return;
		}
		graphics.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BARRIER), 0, 0);
	}
}
