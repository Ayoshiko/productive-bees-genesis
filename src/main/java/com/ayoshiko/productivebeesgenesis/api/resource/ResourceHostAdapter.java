package com.ayoshiko.productivebeesgenesis.api.resource;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 由第三方为其真实 BlockEntityType 注册的资源宿主合同；方法只在服务器线程调用。
 * 查询方法只检查状态；恢复回调只确认已核验游标。不加载区块或启动后台世界任务，不保留玩家／世界引用。
 * 注册仅增加源类型，实际生产仍须在机器成功消费的边界调用 ResourceHostApi.reserve。
 */
public interface ResourceHostAdapter {
	/** 该宿主的生产接入是否已就绪；禁用或兼容检查失败时返回 false。 */
	boolean ready(BlockEntity host);

	/** 在通用距离、旁观者与建造检查之外，检查宿主自己的所有权／安全设置。 */
	boolean canAccess(ServerPlayer player, BlockEntity host);

	/** 存在仍由宿主持有的蜜蜂／已付费工作时返回 true，阻止玩家直接解绑账户。 */
	boolean hasLiveWork(BlockEntity host);

	/** 恢复要求移走可能重放的输入；宿主自己的未决游标可由确认回调处理，仍阻止普通解绑。 */
	default boolean canResolveProduction(BlockEntity host) { return !hasLiveWork(host); }

	/**
	 * 已核验的当前请求在资源结算前通知宿主。只确认 context 中的唯一游标，不再生成或交付资产；
	 * 必须幂等：回调成功而后续账户结算失败时，同一请求会再次确认。异常保持账户暂停。
	 */
	default void resolveProduction(BlockEntity host, net.minecraft.nbt.CompoundTag context, boolean completed) {}

	/** 创建或重新认领时采用的每资源身份容量；已绑定账户不随此返回值静默缩小。 */
	default long capacity(BlockEntity host) { return 1_000_000; }
}
