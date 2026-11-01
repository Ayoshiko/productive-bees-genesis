package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

/** 崩溃／保存抛错也会触发 StoppedEvent；只观察确实走完的正常停止路径。 */
@Mixin(MinecraftServer.class)
public abstract class ResourceServerLifecycleMixin {
	@WrapMethod(method = "reloadResources", require = 1)
	private CompletableFuture<Void> productivebeesgenesis$reload(Collection<String> selected, Operation<CompletableFuture<Void>> original) {
		MinecraftServer server = (MinecraftServer) (Object) this;
		ResourceRuntime.beginReload(server);
		try {
			return original.call(selected).whenComplete((unused, failure) -> ResourceRuntime.finishReload(server, failure == null));
		} catch (RuntimeException | Error failure) {
			ResourceRuntime.finishReload(server, false);
			throw failure;
		}
	}

    @Inject(method = "runServer", at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/server/ServerLifecycleHooks;handleServerStopping(Lnet/minecraft/server/MinecraftServer;)V",
            shift = At.Shift.AFTER, remap = false))
    private void productivebeesgenesis$orderlyStop(CallbackInfo ci) {
        ResourceRuntime.observeOrderlyStop((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void productivebeesgenesis$beginWorldClose(CallbackInfo ci) {
        ResourceRuntime.observeWorldClose((MinecraftServer) (Object) this, false);
    }

    @Inject(method = "stopServer", at = @At("RETURN"))
    private void productivebeesgenesis$completedWorldClose(CallbackInfo ci) {
        ResourceRuntime.observeWorldClose((MinecraftServer) (Object) this, true);
    }

    // 原版捕获世界关闭的 IOException 后仍正常返回，不能据此清除会话标记。
    @Inject(method = "stopServer", at = @At(value = "INVOKE",
            target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V", remap = false))
    private void productivebeesgenesis$worldCloseFailed(CallbackInfo ci) {
        ResourceRuntime.observeWorldSaveFailure((MinecraftServer) (Object) this);
    }

}
