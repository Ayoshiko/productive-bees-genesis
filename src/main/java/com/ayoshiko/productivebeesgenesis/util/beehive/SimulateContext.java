package com.ayoshiko.productivebeesgenesis.util.beehive;

import javax.annotation.Nullable;

/**
 * simulateBee() 调用期间的线程本地上下文，用于向 Redirect 传递是否跳过查询的标志。
 */
public final class SimulateContext {
    private static final ThreadLocal<SimulateContext> CURRENT = new ThreadLocal<>();

    public final int cooldown;
    public boolean skipFarmer;
    public boolean skipHoarder;

    private SimulateContext(int cooldown) {
        this.cooldown = cooldown;
    }

    public static SimulateContext enter(int cooldown) {
        SimulateContext ctx = new SimulateContext(cooldown);
        CURRENT.set(ctx);
        return ctx;
    }

    public static void exit() {
        CURRENT.remove();
    }

    @Nullable
    public static SimulateContext get() {
        return CURRENT.get();
    }
}
