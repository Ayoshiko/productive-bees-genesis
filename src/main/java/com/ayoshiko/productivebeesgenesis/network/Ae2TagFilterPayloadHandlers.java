package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2TagFilter;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * smelt 输入标签过滤数据包的服务端/客户端处理。
 * <p>
 * 独立于 {@link Ae2FilterPayloadHandlers}（SRP + 该类已 497 行接近上限）：
 * 本类只处理标签表达式的保存与回传。
 * <p>
 * 安全模型与其他 AE2 过滤包一致：校验玩家身份 → 容器一致性（防 IDOR）→
 * 频次限制 → 方块实体类型 → 8 格交互距离，最后才写状态。
 * 表达式长度在 StreamCodec 已限，这里再复校验一次，
 * 防止未来 codec 上限调整后服务端失去保护。
 */
final class Ae2TagFilterPayloadHandlers {

	private Ae2TagFilterPayloadHandlers() {
	}

	/** 服务端处理：保存标签白/黑名单表达式。 */
	static void handleSetAeInputTagFilter(SetAeInputTagFilterPayload payload, IPayloadContext context) {
		if (payload.whitelist() == null || payload.blacklist() == null) return;
		if (payload.whitelist().length() > NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH
				|| payload.blacklist().length() > NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH) {
			LogThrottle.warn("ae2_tag_filter_too_long", "收到过长的 AE2 标签过滤表达式：白名单 {} / 黑名单 {}",
					payload.whitelist().length(), payload.blacklist().length());
			return;
		}
		if (!(context.player() instanceof ServerPlayer serverPlayer) || serverPlayer.level() == null) return;
		if (!Ae2PayloadHandlers.validateContainerMatch(serverPlayer, payload.pos(),
				"ae2_input_tag_filter_pos_mismatch")) return;
		if (!PayloadRateLimiter.tryAccept(serverPlayer, "ae_input_tag_filter",
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;

		BlockEntity be = serverPlayer.level().getBlockEntity(payload.pos());
		if (!(be instanceof IAe2InputHost host)
				|| serverPlayer.distanceToSqr(payload.pos().getCenter())
						> NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) return;
		Ae2TagFilter tagFilter = host.productivebeesgenesis$getAeTagFilter();
		if (tagFilter == null) return;

		if (tagFilter.apply(payload.whitelist(), payload.blacklist()) && be instanceof TileEntityMekanism mek) {
			mek.markForSave();
		}
		// 无论是否变化都回传一次：让客户端拿到服务端归一化后的文本与语法校验结果
		syncTagFilterToClient(be, serverPlayer);
	}

	/** 客户端处理：应用服务端推送的表达式与校验结果。 */
	static void handleSyncAeInputTagFilter(SyncAeInputTagFilterPayload payload, IPayloadContext context) {
		if (context.player().level() == null) return;
		BlockEntity be = context.player().level().getBlockEntity(payload.pos());
		if (!(be instanceof IAe2InputHost host)) return;
		Ae2TagFilter tagFilter = host.productivebeesgenesis$getAeTagFilter();
		if (tagFilter == null) return;
		if (payload.whitelist() == null || payload.blacklist() == null) return;
		if (payload.whitelist().length() > NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH
				|| payload.blacklist().length() > NetworkSecurityConstants.MAX_TAG_EXPRESSION_LENGTH) return;
		tagFilter.apply(payload.whitelist(), payload.blacklist());
	}

	/** 推送当前表达式与语法错误键给指定玩家。 */
	static void syncTagFilterToClient(BlockEntity be, ServerPlayer player) {
		if (be == null || be.getLevel() == null || be.getLevel().isClientSide) return;
		if (!(be instanceof IAe2InputHost host)) return;
		Ae2TagFilter tagFilter = host.productivebeesgenesis$getAeTagFilter();
		if (tagFilter == null) return;
		var spec = tagFilter.getSpec();
		PacketDistributor.sendToPlayer(player, new SyncAeInputTagFilterPayload(
				be.getBlockPos(),
				spec.whitelistSource(),
				spec.blacklistSource(),
				spec.whitelistErrorKey() == null ? "" : spec.whitelistErrorKey(),
				spec.blacklistErrorKey() == null ? "" : spec.blacklistErrorKey()));
	}
}
