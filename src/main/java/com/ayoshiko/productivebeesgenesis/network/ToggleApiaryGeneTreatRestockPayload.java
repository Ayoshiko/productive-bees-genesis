package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端请求切换当前机械蜂箱的基因小食补货；状态由服务端校验后同步。 */
public record ToggleApiaryGeneTreatRestockPayload(BlockPos pos) implements CustomPacketPayload {

	/** 数据包注册标识。 */
	public static final Type<ToggleApiaryGeneTreatRestockPayload> TYPE = new Type<>(
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "toggle_apiary_gene_treat_restock"));
	/** 只传目标坐标，客户端不能提供物品或模板。 */
	public static final StreamCodec<ByteBuf, ToggleApiaryGeneTreatRestockPayload> STREAM_CODEC =
			StreamCodec.composite(BlockPos.STREAM_CODEC, ToggleApiaryGeneTreatRestockPayload::pos,
					ToggleApiaryGeneTreatRestockPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
