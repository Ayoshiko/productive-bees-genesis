package com.ayoshiko.productivebeesgenesis.command;

import java.util.Locale;

import com.mojang.brigadier.context.CommandContext;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.PerformanceMonitor;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static net.minecraft.commands.Commands.literal;

/**
 * 性能监控命令
 * <br/>
 * 提供 {@code /productivebeesgenesis perf} 查看统计快照，
 * {@code /productivebeesgenesis perf reset} 清零计数器。
 * <p>
 * 需 OP 权限等级 2。性能监控关闭（enablePerformanceMonitor=false）时命令仍可执行，
 * 此时计数器未被记录，输出为零值数据，便于运维确认命令可用性与监控开关状态。
 * <p>
 * 通过 {@code @EventBusSubscriber} 自动注册到 NeoForge 游戏事件总线
 * （RegisterCommandsEvent 属游戏事件，默认 bus 即 GAME）。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class PerfCommand {

    private static final String ROOT = "productivebeesgenesis";
    private static final String PERF = "perf";
    private static final String RESET = "reset";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                literal(ROOT)
                        .requires(source -> source.hasPermission(2))
                        .then(literal(PERF)
                                .executes(PerfCommand::showStats)
                                .then(literal(RESET)
                                        .executes(PerfCommand::resetStats)
                                )
                        )
        );
    }

    /**
     * 输出性能统计快照到执行者聊天框
     * <br/>
     * 读取各原子字段的当前值为快照，字段间非原子一致（可接受，监控用途），
     * 与 JMX MBean 的读取语义保持一致。
     */
    private static int showStats(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PerformanceMonitor pm = PerformanceMonitor.getInstance();

        source.sendSystemMessage(Component.literal("===== 资源蜜蜂：创世 性能监控 =====")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        // 监控关闭时给出提示，避免误读零值为真实性能数据
        if (!PerformanceMonitor.isEnabled()) {
            source.sendSystemMessage(Component.literal("[注意] 性能监控未启用（enablePerformanceMonitor=false），以下为零值数据")
                    .withStyle(ChatFormatting.YELLOW));
        }

        sendStat(source, "平均 tick 时间", String.format(Locale.ROOT, "%.3f ms", pm.getAverageTickTimeMs()), ChatFormatting.GREEN);
        sendStat(source, "最大 tick 时间", String.format(Locale.ROOT, "%.3f ms", pm.getMaxTickTimeMs()), ChatFormatting.RED);
        sendStat(source, "缓存命中率", String.format(Locale.ROOT, "%.2f%%", pm.getCacheHitRate() * 100), ChatFormatting.AQUA);
        sendStat(source, "平均配方查找耗时", String.format(Locale.ROOT, "%.3f μs", pm.getAverageRecipeLookupUs()), ChatFormatting.LIGHT_PURPLE);
        sendStat(source, "总能量消耗", pm.getTotalEnergyConsumed() + " FE", ChatFormatting.BLUE);
        sendStat(source, "tick 计数", String.valueOf(pm.getTickCount()), ChatFormatting.GRAY);

        source.sendSystemMessage(Component.literal("===================================")
                .withStyle(ChatFormatting.GOLD));
        return 1;
    }

    /** 重置所有统计计数器 */
    private static int resetStats(CommandContext<CommandSourceStack> ctx) {
        PerformanceMonitor.getInstance().reset();
        ctx.getSource().sendSuccess(() -> Component.literal("性能监控统计已重置")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** 发送一行统计信息：键（灰色） + 值（指定颜色） */
    private static void sendStat(CommandSourceStack source, String key, String value, ChatFormatting valueColor) {
        source.sendSystemMessage(Component.literal("  " + key + ": ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(valueColor)));
    }
}
