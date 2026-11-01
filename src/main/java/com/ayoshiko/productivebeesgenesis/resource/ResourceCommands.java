package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.Map;

/** 有界账户查询与显式恢复入口；结果不明的决策限管理员，并记录实际操作者。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ResourceCommands {
	private static final String PREFIX = "command.productivebeesgenesis.resource.";
	private static final int PAGE_SIZE = 10;
	private ResourceCommands() {}

	@SubscribeEvent
	public static void register(RegisterCommandsEvent event) {
		var root = Commands.literal("pbgresources").requires(source -> source.getEntity() instanceof net.minecraft.server.level.ServerPlayer)
				.executes(ctx -> reply(ctx.getSource(), "help"))
				.then(Commands.literal("list").executes(ctx -> run(ctx, () -> list(ctx, 1)))
						.then(Commands.argument("page", IntegerArgumentType.integer(1, 52))
								.executes(ctx -> run(ctx, () -> list(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
				.then(Commands.literal("retire-empty").then(Commands.argument("source", UuidArgument.uuid())
                        .then(Commands.argument("generation", LongArgumentType.longArg(1)).executes(ctx -> run(ctx, () -> {
                            var account = account(ctx);
                            var reference = reference(ctx, account);
                            if (!ResourceRuntime.accounts(ctx.getSource().getServer()).accounts().retireEmpty(reference))
                                throw new IllegalStateException("Account still has a binding, assets, work or unresolved data");
                            audit(ctx, "retire-empty", reference.sourceId().toString());
                            return reply(ctx.getSource(), "retired");
                        })))))
				.then(Commands.literal("status").then(Commands.argument("source", UuidArgument.uuid())
						.executes(ctx -> run(ctx, () -> status(ctx)))))
				.then(Commands.literal("bind").requires(source -> source.hasPermission(2))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(ctx -> run(ctx, () -> bind(ctx)))))
				.then(Commands.literal("detach").then(Commands.argument("source", UuidArgument.uuid())
						.then(Commands.argument("generation", LongArgumentType.longArg(1))
								.executes(ctx -> run(ctx, () -> detach(ctx))))))
				.then(Commands.literal("claim").then(Commands.argument("source", UuidArgument.uuid())
						.then(Commands.argument("generation", LongArgumentType.longArg(1))
								.then(Commands.argument("pos", BlockPosArgument.blockPos())
										.executes(ctx -> run(ctx, () -> claim(ctx)))))))
				.then(Commands.literal("resolve-production").requires(source -> source.hasPermission(2))
						.then(Commands.argument("source", UuidArgument.uuid())
								.then(Commands.argument("generation", LongArgumentType.longArg(1))
										.then(Commands.argument("sequence", LongArgumentType.longArg(1))
												.then(Commands.literal("confirm-completed")
														.executes(ctx -> run(ctx, () -> resolveProduction(ctx, true))))
												.then(Commands.literal("confirm-no-output")
														.executes(ctx -> run(ctx, () -> resolveProduction(ctx, false))))))))
				.then(Commands.literal("resolve-transfer").requires(source -> source.hasPermission(2))
						.then(Commands.argument("source", UuidArgument.uuid())
								.then(Commands.argument("generation", LongArgumentType.longArg(1))
										.then(Commands.argument("sequence", LongArgumentType.longArg(1))
												.then(Commands.argument("accepted", StringArgumentType.word())
														.executes(ctx -> run(ctx, () -> resolveTransfer(ctx))))))))
				.then(Commands.literal("retry-save").requires(source -> source.hasPermission(2))
						.executes(ctx -> run(ctx, () -> {
							ResourceRuntime.accounts(ctx.getSource().getServer()).requestSaveRetry();
							audit(ctx, "retry-save", "world");
							return reply(ctx.getSource(), "save_requested");
						})));
		event.getDispatcher().register(root);
	}

	private static ResourceAccount account(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return ResourceCommandAccess.account(ctx.getSource(), UuidArgument.getUuid(ctx, "source"));
	}

	private static ResourceAccount.Reference reference(CommandContext<CommandSourceStack> ctx, ResourceAccount account) {
		return ResourceCommandAccess.reference(account, LongArgumentType.getLong(ctx, "generation"));
	}

	private static int list(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
		var source = ctx.getSource();
		var player = source.getPlayerOrException();
		var visible = ResourceRuntime.accounts(source.getServer()).accounts().snapshot().stream()
				.filter(account -> account.owner().equals(player.getUUID()) || source.hasPermission(2)).toList();
		reply(source, "list", page, Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE), visible.size());
		if (source.hasPermission(2)) reply(source, "capacity", ResourceRuntime.accounts(source.getServer()).accounts().size(), ResourceAccounts.MAX_ACCOUNTS);
		visible.stream().skip((long) (page - 1) * PAGE_SIZE).limit(PAGE_SIZE).forEach(account -> summary(source, account));
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var account = account(ctx);
		var source = ctx.getSource();
		summary(source, account);
		amounts(source, "available", account.availableExact());
		amounts(source, "frozen", account.frozenProductionExact());
		amounts(source, "uncertain", account.uncertainTransferExact());
		if (account.productionTicket() != null) {
			reply(source, "production", account.productionTicket().sequence(),
					Component.translatable(PREFIX + "phase." + account.productionPhase(account.productionTicket()).toLowerCase(java.util.Locale.ROOT)));
		}
		if (account.transferTicket() != null) reply(source, "transfer", account.transferTicket().sequence());
		if (!account.suspension().isEmpty()) reply(source, "suspended");
		if (ResourceRuntime.accounts(source.getServer()).sessionIsolated()) reply(source, "session_isolated");
		return 1;
	}

	private static void summary(CommandSourceStack source, ResourceAccount account) {
		var binding = account.binding();
		var location = binding == null ? Component.translatable(PREFIX + "detached")
				: Component.literal(binding.dimension() + " " + net.minecraft.core.BlockPos.of(binding.packedPosition()).toShortString());
		reply(source, "account", account.reference().sourceId().toString(), account.reference().generation(), location);
	}

	private static void amounts(CommandSourceStack source, String kind, Map<ResourceKey, java.math.BigInteger> amounts) {
		for (var entry : amounts.entrySet()) {
			var key = entry.getKey();
			// 未解析 payload 不直接发往客户端；每个状态最多 16 行。
			String identity = key.kind().toString();
			if (key.kind().toString().equals("productivebeesgenesis:chemical")) identity += " / " + key.payload().getString("id");
			reply(source, kind, identity, entry.getValue().toString());
		}
	}

	private static int bind(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var player = ctx.getSource().getPlayerOrException();
		var hive = ResourceCommandAccess.hive(player, BlockPosArgument.getBlockPos(ctx, "pos"));
		var result = ResourceHiveHost.bind(hive, player.getUUID(), ResourceHiveHost.capacityFor(hive));
		audit(ctx, "bind", result.toString());
		return reply(ctx.getSource(), "bound", result.sourceId().toString(), result.generation());
	}

	private static int detach(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var account = account(ctx);
		var expected = reference(ctx, account);
		var hive = ResourceCommandAccess.boundHive(ctx.getSource(), account);
		var result = ResourceHiveHost.existing(hive).detach(hive, account.owner(), expected);
		audit(ctx, "detach", result.toString());
		return reply(ctx.getSource(), "unbound", result.sourceId().toString(), result.generation());
	}

	private static int claim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var account = account(ctx);
		var expected = reference(ctx, account);
		var hive = ResourceCommandAccess.hive(ctx.getSource().getPlayerOrException(), BlockPosArgument.getBlockPos(ctx, "pos"));
		var result = ResourceHiveHost.claim(hive, account.owner(), expected);
		audit(ctx, "claim", result.toString());
		return reply(ctx.getSource(), "bound", result.sourceId().toString(), result.generation());
	}

	private static int resolveProduction(CommandContext<CommandSourceStack> ctx, boolean completed)
			throws CommandSyntaxException {
		var account = account(ctx);
		var ticket = new ResourceAccount.Ticket(reference(ctx, account), LongArgumentType.getLong(ctx, "sequence"));
		// 核验原输入槽，避免确认后再次运行仍就绪的原周期；其它槽位继续保有生产权。
		var storage = ResourceRuntime.accounts(ctx.getSource().getServer()).accounts();
		if (!storage.persistenceFailure().isEmpty() || storage.hasInvalidData())
			throw new IllegalStateException("Resource storage is isolated; host recovery must not change local state");
		var context = account.productionContext(ticket);
		if (account.binding() != null) {
			var host = ResourceCommandAccess.boundHive(ctx.getSource(), account);
			var adapter = com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi.adapter(host);
			int lane = ResourceAccount.productionLane(context);
			boolean occupied = adapter == null ? ResourceHiveHost.hasBees(host) : !adapter.canResolveProduction(host);
			if (lane >= 0 && "resource_centrifuging".equals(context.getString("host"))
					&& host instanceof com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext machine) {
				occupied = lane >= machine.processes() || !machine.inputSlot(lane).isEmpty();
			} else if (lane >= 0 && "mek_apiary".equals(context.getString("host")) && host instanceof TileEntityMekApiary apiary) {
				occupied = lane >= apiary.getBeeSlots().length || !apiary.getBeeSlot(lane).isEmpty();
			}
			if (occupied)
				throw new IllegalArgumentException("empty_hive_first");
			if (adapter != null) adapter.resolveProduction(host, context, completed);
		}
		var frozen = account.frozenProduction();
		account.resolveProduction(account.owner(), ticket, completed);
		audit(ctx, completed ? "production-completed" : "production-no-output", ticket + " frozen=" + frozen);
		return reply(ctx.getSource(), "resolved");
	}

	private static int resolveTransfer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var account = account(ctx);
		var ticket = new ResourceAccount.Ticket(reference(ctx, account), LongArgumentType.getLong(ctx, "sequence"));
		String text = StringArgumentType.getString(ctx, "accepted");
        java.math.BigInteger accepted = "0".equals(text) ? java.math.BigInteger.ZERO : ResourceExactAmounts.parse(text);
		account.resolveTransferExact(account.owner(), ticket, accepted);
		audit(ctx, "transfer", ticket + " accepted=" + accepted);
		return reply(ctx.getSource(), "resolved");
	}

	private static void audit(CommandContext<CommandSourceStack> ctx, String action, String detail) throws CommandSyntaxException {
		ProductiveBeesGenesis.LOGGER.info("Resource account action: actor={} action={} detail={}",
				ctx.getSource().getPlayerOrException().getUUID(), action, detail);
	}

	private static int reply(CommandSourceStack source, String key, Object... values) {
		source.sendSuccess(() -> Component.translatable(PREFIX + key, values), false);
		return 1;
	}

	private static int run(CommandContext<CommandSourceStack> ctx, Attempt action) throws CommandSyntaxException {
		try {
			return action.execute();
		} catch (IllegalArgumentException failure) {
			String key = java.util.Set.of("account_access", "host_access", "not_hive", "stale", "empty_hive_first")
					.contains(failure.getMessage() == null ? "" : failure.getMessage()) ? failure.getMessage() : "rejected";
			ctx.getSource().sendFailure(Component.translatable(PREFIX + key));
			return 0;
		} catch (IllegalStateException | SecurityException failure) {
			ProductiveBeesGenesis.LOGGER.warn("Resource command rejected: {}", failure.toString());
			ctx.getSource().sendFailure(Component.translatable(PREFIX + "rejected"));
			return 0;
		}
	}

	@FunctionalInterface private interface Attempt { int execute() throws CommandSyntaxException; }
}
