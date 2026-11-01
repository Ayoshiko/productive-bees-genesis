package com.ayoshiko.productivebeesgenesis.port;

import net.minecraft.nbt.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;

/** Single ordered resource-account IO worker and atomic 1.20 NBT snapshots. */
public final class ForgeIO {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(task->{var thread=new Thread(task,"Genesis resource persistence");thread.setDaemon(true);return thread;});
    public static void withIOWorker(Runnable task){WORKER.submit(task);}
    public static void waitUntilIOWorkerComplete(){try{WORKER.submit(()->{}).get();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}catch(ExecutionException e){throw new IllegalStateException(e.getCause());}}
    public static CompoundTag readCompressed(Path file,long maximum) throws IOException {
        try(var input=new DataInputStream(new java.util.zip.GZIPInputStream(Files.newInputStream(file)))){return NbtIo.read(input,new NbtAccounter(maximum));}
    }
    public static void writeNbtCompressed(CompoundTag data,Path file) throws IOException {
        Path temporary=Files.createTempFile(file.getParent(),file.getFileName().toString(),".tmp");
        try {
            NbtIo.writeCompressed(data,temporary.toFile());
            try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
}
