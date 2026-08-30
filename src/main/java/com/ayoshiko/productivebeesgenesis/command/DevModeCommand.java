package com.ayoshiko.productivebeesgenesis.command;

import com.ayoshiko.productivebeesgenesis.mek.DevModeManager;
import com.ayoshiko.productivebeesgenesis.network.DevModeStateSyncPacket;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.Set;

/**
	 * 开发者模式命令 — 运行时控制开发者模式开关与子功能
	 * <br/>
	 * 用法：
	 * <ul>
	 *   <li>{@code /productivebeesgenesis dev on} — 开启主开关</li>
	 *   <li>{@code /productivebeesgenesis dev off} — 关闭主开关</li>
	 *   <li>{@code /productivebeesgenesis dev status} — 查看当前状态</li>
	 *   <li>{@code /productivebeesgenesis dev <feature> on|off} — 切换子功能（框架预留）</li>
	 * </ul>
	 * 需要 OP 权限等级 2（与原版管理命令一致）。
	 * <p>
	 * 状态仅存在于内存（{@link DevModeManager}），服务器重启后重置为关闭。
	 * 每次状态变更后广播 {@link DevModeStateSyncPacket} 给所有在线玩家，
	 * 同步客户端镜像状态（{@code ClientDevModeState}）以控制创造标签页开发物品可见性。
	 *
	 * @since 2.0.0
	 */
public final class DevModeCommand {

	/** 命令根字面量名称 */
	public static final String ROOT_LITERAL = "productivebeesgenesis";

	/** dev 子命令字面量 */
	private static final String DEV_LITERAL = "dev";

	/** on/off/status 子命令字面量 */
	private static final String ON_LITERAL = "on";
	private static final String OFF_LITERAL = "off";
	private static final String STATUS_LITERAL = "status";

	/** 子功能名称参数 */
	private static final String FEATURE_ARG = "feature";

	/** 已知子功能白名单 — 与 {@code DevLog} 各调用点的 feature 名保持一致；未知名称直接拒绝 */
	private static final Set<String> KNOWN_FEATURES = Set.of(
			"ae2_expensive_network", "ae2_fluid_push", "apiary_speed", "bee_cache", "bee_info", "bee_nbt",
			"bee_renderer", "bee_tooltip", "centrifuge_batch", "centrifuge_mixin",
			"config_apply", "fluid_eject", "fluid_tank", "jei", "nbt_serialize",
			"pb_recipe", "pb_upgrade_slot", "pb_upgrade_truncated", "recipe_reload",
			"crafting_upgrade"
	);

	/** feature 参数补全建议器 */
	private static final SuggestionProvider<CommandSourceStack> FEATURE_SUGGESTIONS =
			(context, builder) -> SharedSuggestionProvider.suggest(KNOWN_FEATURES, builder);

	private DevModeCommand() {
		// 工具类禁止实例化
	}

	/**
	 * 命令注册入口 — 由主类 {@code RegisterCommandsEvent} 监听器调用
	 * <br/>
	 * 注册 {@code /productivebeesgenesis dev <on|off|status|<feature> on|off>} 命令树，
	 * 需要 OP 权限等级 2。
	 *
	 * @param event 命令注册事件
	 */
	public static void register(RegisterCommandsEvent event) {
		event.getDispatcher().register(
				Commands.literal(ROOT_LITERAL)
						.requires(source -> source.hasPermission(2))
						.then(Commands.literal(DEV_LITERAL)
								.then(Commands.literal(ON_LITERAL)
										.executes(DevModeCommand::onMasterEnable))
								.then(Commands.literal(OFF_LITERAL)
										.executes(DevModeCommand::onMasterDisable))
								.then(Commands.literal(STATUS_LITERAL)
										.executes(DevModeCommand::onStatus))
								.then(Commands.argument(FEATURE_ARG, StringArgumentType.string())
										.suggests(FEATURE_SUGGESTIONS)
										.then(Commands.literal(ON_LITERAL)
												.executes(DevModeCommand::onFeatureEnable))
										.then(Commands.literal(OFF_LITERAL)
												.executes(DevModeCommand::onFeatureDisable)))));
	}

	/**
	 * 处理 {@code dev on} 子命令 — 开启主开关并广播同步包
	 */
	private static int onMasterEnable(CommandContext<CommandSourceStack> ctx) {
		DevModeManager.setEnabled(true);
		broadcastState();
		ctx.getSource().sendSuccess(() -> Component.literal("开发者模式已开启"), true);
		return 1;
	}

	/**
	 * 处理 {@code dev off} 子命令 — 关闭主开关并广播同步包
	 */
	private static int onMasterDisable(CommandContext<CommandSourceStack> ctx) {
		DevModeManager.setEnabled(false);
		broadcastState();
		ctx.getSource().sendSuccess(() -> Component.literal("开发者模式已关闭"), true);
		return 1;
	}

	/**
	 * 处理 {@code dev status} 子命令 — 显示主开关和所有子功能状态
	 */
	private static int onStatus(CommandContext<CommandSourceStack> ctx) {
		boolean master = DevModeManager.isEnabled();
		String masterText = "开发者模式：" + (master ? "开启" : "关闭");
		ctx.getSource().sendSuccess(() -> Component.literal(masterText), false);
		Map<String, Boolean> features = DevModeManager.getFeatureStates();
		if (features.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("子功能：无"), false);
		} else {
			for (Map.Entry<String, Boolean> entry : features.entrySet()) {
				String name = entry.getKey();
				boolean enabled = entry.getValue();
				String featureText = "子功能：" + name + "=" + (enabled ? "开启" : "关闭");
				ctx.getSource().sendSuccess(() -> Component.literal(featureText), false);
			}
		}
		return 1;
	}

	/**
	 * 处理 {@code dev <feature> on} 子命令 — 开启指定子功能并广播同步包
	 */
	private static int onFeatureEnable(CommandContext<CommandSourceStack> ctx) {
		String feature = StringArgumentType.getString(ctx, FEATURE_ARG);
		if (!KNOWN_FEATURES.contains(feature)) {
			ctx.getSource().sendFailure(Component.literal("未知子功能：" + feature + "（可用功能见补全列表）"));
			return 0;
		}
		DevModeManager.setEnabled(feature, true);
		broadcastState();
		ctx.getSource().sendSuccess(() -> Component.literal("子功能 " + feature + " 已开启"), true);
		return 1;
	}

	/**
	 * 处理 {@code dev <feature> off} 子命令 — 关闭指定子功能并广播同步包
	 */
	private static int onFeatureDisable(CommandContext<CommandSourceStack> ctx) {
		String feature = StringArgumentType.getString(ctx, FEATURE_ARG);
		if (!KNOWN_FEATURES.contains(feature)) {
			ctx.getSource().sendFailure(Component.literal("未知子功能：" + feature + "（可用功能见补全列表）"));
			return 0;
		}
		DevModeManager.setEnabled(feature, false);
		broadcastState();
		ctx.getSource().sendSuccess(() -> Component.literal("子功能 " + feature + " 已关闭"), true);
		return 1;
	}

	/**
	 * 广播开发者模式状态到所有在线玩家
	 * <br/>
	 * 构建当前状态快照并通过 {@link PacketDistributor#sendToAllPlayers} 发送给所有在线玩家，
	 * 客户端接收后更新 {@code ClientDevModeState} 镜像状态。
	 */
	private static void broadcastState() {
		DevModeStateSyncPacket packet = new DevModeStateSyncPacket(
				DevModeManager.isEnabled(),
				DevModeManager.getFeatureStates()
		);
		PacketDistributor.sendToAllPlayers(packet);
	}
}
