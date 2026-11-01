package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.server.level.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Forge 1.20 reports chunk save failures here, before the newer server callback existed. */
@Mixin(ChunkMap.class)
public abstract class ResourceChunkSaveMixin {
 @Shadow @Final ServerLevel level;
 @Inject(method="save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",at=@At(value="INVOKE",target="Lorg/slf4j/Logger;error(Ljava/lang/String;[Ljava/lang/Object;)V",remap=false))
 private void genesis$saveFailure(CallbackInfoReturnable<Boolean> ci){ResourceRuntime.observeWorldSaveFailure(level.getServer());}
 @WrapOperation(method="scheduleUnload",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;whenComplete(Ljava/util/function/BiConsumer;)Ljava/util/concurrent/CompletableFuture;",remap=false),require=1)
 private <T> CompletableFuture<T> genesis$unloadFailure(CompletableFuture<T> future,BiConsumer<? super T,? super Throwable> callback,Operation<CompletableFuture<T>> original){
  return original.call(future,(BiConsumer<T,Throwable>)(value,error)->{if(error!=null)ResourceRuntime.observeWorldSaveFailure(level.getServer());callback.accept(value,error);});
 }
}
