package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraftforge.fml.ModList;

/** 仅注册原生合金炉配方；常驻入口不链接 Useless 或 AE 类型。 */
public final class UselessResourceIntegration {
    private UselessResourceIntegration() {
    }

    public static void install() {
        if (!ModList.get().isLoaded("useless_mod") || !ModList.get().isLoaded("ae2")) {
            return;
        }
        try {
            Class<?> adapter = Class.forName("com.sorrowmist.useless.api.recipe.IRecipeAdapter");
            Class<?> adapters = java.lang.reflect.Array.newInstance(adapter, 0).getClass();
            Class.forName("com.sorrowmist.useless.api.recipe.AlloyFurnaceCompatApi")
                    .getMethod("register", String.class, adapters);
            Class.forName("com.ayoshiko.productivebeesgenesis.mek.ae2.UselessResourceRecipes")
                    .getMethod("register", boolean.class).invoke(null, supportsLegacyRecovery());
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            ProductiveBeesGenesis.LOGGER.info("Useless resource recipes require the public alloy-furnace recipe API");
        } catch (ReflectiveOperationException | LinkageError failure) {
            ProductiveBeesGenesis.LOGGER.error("Useless resource recipe integration is unavailable", failure);
        }
    }

    /** 旧本地补丁仍可核验其已保存资产；该附加接口不再是原生配方兼容的前置。 */
    private static boolean supportsLegacyRecovery() {
        try {
            var api = Class.forName("com.sorrowmist.useless.content.machines.advanced_alloy_furnace.io.FurnacePaidOutputs");
            if (api.getField("API_VERSION").getInt(null) != 1) {
                return false;
            }
            api.getMethod("resolve", long.class, long.class);
            var host = Class.forName("com.sorrowmist.useless.content.blockentities.AdvancedAlloyFurnaceBlockEntity");
            host.getMethod("paidOutputStatus", int.class);
            host.getMethod("resolvePaidOutput", int.class, java.util.UUID.class, long.class, long.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            // 官方版本没有这组旧补丁接口，只需省略旧资产核验命令；原生加工不受影响。
            return false;
        }
    }
}
