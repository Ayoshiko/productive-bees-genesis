package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 → 客户端：同步 per-tile smelt 输入标签过滤表达式与其校验结果。
 * <br/>
 * errorKey 为空串表示无语法错误；非空时客户端据此显示本地化错误提示
 * （翻译键 {@code productivebeesgenesis.gui.tag_filter.error.<errorKey>}）。
 *
 * @param pos                方块坐标
 * @param whitelist          白名单标签表达式
 * @param blacklist          黑名单标签表达式
 * @param whitelistErrorKey  白名单语法错误键（空串=无错误）
 * @param blacklistErrorKey  黑名单语法错误键（空串=无错误）
 */
public record SyncAeInputTagFilterPayload(
		BlockPos pos,
		String whitelist,
		String blacklist,
		String whitelistErrorKey,
		String blacklistErrorKey
)  {

    public static SyncAeInputTagFilterPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new SyncAeInputTagFilterPayload(buf.readBlockPos(), buf.readUtf(512), buf.readUtf(512), buf.readUtf(64), buf.readUtf(64));
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
        buf.writeUtf(whitelist, 512);
        buf.writeUtf(blacklist, 512);
        buf.writeUtf(whitelistErrorKey, 64);
        buf.writeUtf(blacklistErrorKey, 64);
    }
}
