package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.config.ServerConfigMigrationService;
import com.ayoshiko.productivebeesgenesis.config.ServerConfigRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;

/** 迁移先于 FML 选取 SERVER 文件；任意模组的正常加载失败都撤销本模组会话。 */
@Mixin(value = ServerLifecycleHooks.class, remap = false)
public abstract class ServerConfigLifecycleMixin {
	@WrapMethod(method = "handleServerAboutToStart", require = 1)
	private static void productivebeesgenesis$prepareConfigs(MinecraftServer server, Operation<Void> original) {
		try {
			ServerConfigRuntime.beginServer(server);
			ServerConfigMigrationService.prepareForServer(server);
			original.call(server);
			ServerConfigRuntime.finishServerStart(server);
		} catch (RuntimeException | Error failure) {
			try {
				ServerConfigRuntime.endServer(server);
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}
}
