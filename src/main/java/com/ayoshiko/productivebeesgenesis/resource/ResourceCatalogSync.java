package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.network.FriendlyByteBuf;
import com.ayoshiko.productivebeesgenesis.port.StreamCodec;
import com.ayoshiko.productivebeesgenesis.port.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.OnDatapackSyncEvent;
import com.ayoshiko.productivebeesgenesis.network.ForgeNetwork;
import com.ayoshiko.productivebeesgenesis.port.RegisterPayloadHandlersEvent;

/** 登录及数据包重载时发送服务端目录；不注册客户端写入入口。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ResourceCatalogSync {
	private ResourceCatalogSync() {}
	public record Page(long revision, int index, int total, ResourceRecipeView recipe) implements CustomPacketPayload {
		public static final Type<Page> TYPE = new Type<>(new ResourceLocation("productivebeesgenesis:resource_catalog"));
		public static final StreamCodec<FriendlyByteBuf, Page> CODEC = new StreamCodec<>() {
			@Override public Page decode(FriendlyByteBuf buffer) {
				long revision = buffer.readVarLong();
				int index = buffer.readVarInt(), total = buffer.readVarInt();
				if (revision < 0 || total < -1 || total > ResourceCatalogBuffer.MAX_RECIPES || index < 0
						|| (total <= 0 ? index != 0 : index >= total)) throw new IllegalArgumentException("Invalid resource catalog header");
				return new Page(revision, index, total, total > 0 ? ResourceRecipeView.read(buffer) : null);
			}
			@Override public void encode(FriendlyByteBuf buffer, Page page) {
				buffer.writeVarLong(page.revision); buffer.writeVarInt(page.index); buffer.writeVarInt(page.total);
				if (page.total > 0) page.recipe.write(buffer);
			}
		};
		@Override public Type<Page> type() { return TYPE; }
	}

	public static void register(RegisterPayloadHandlersEvent event) {
		event.registrar("2").playToClient(Page.TYPE, Page.CODEC,
				(page, context) -> context.enqueueWork(() ->
						com.ayoshiko.productivebeesgenesis.client.resource.ResourceClientCatalog.accept(page, context.connection())));
	}

	@SubscribeEvent public static void sync(OnDatapackSyncEvent event) {
		var server = event.getPlayerList().getServer();
		long revision = ProductiveBeesGenesis.RECIPE_VERSION.get();
		java.util.List<ResourceRecipeView> recipes;
		try { recipes = ResourceRecipeView.collect(ResourceRuntime.recipes(server)); }
		catch (RuntimeException | LinkageError failure) {
			ProductiveBeesGenesis.LOGGER.warn("资源展示目录不可用，清除客户端旧目录", failure);
			send(event, new Page(revision, 0, -1, null));
			return;
		}
		if (recipes.isEmpty()) send(event, new Page(revision, 0, 0, null));
		for (int i = 0; i < recipes.size(); i++) send(event, new Page(revision, i, recipes.size(), recipes.get(i)));
	}

	private static void send(OnDatapackSyncEvent event, Page page) {
		if (event.getPlayer() != null) ForgeNetwork.sendToPlayer(event.getPlayer(), page);
		else for (var player : event.getPlayerList().getPlayers()) ForgeNetwork.sendToPlayer(player, page);
	}
}
