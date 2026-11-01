package com.ayoshiko.productivebeesgenesis.resource;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.io.InputStream;

/** 不加载 PB 类的字节码合同；不兼容形状跳过整组 Mixin，应用后所有注入点还必须满足 require=1。 */
public final class PbResourceMixinContract {
	private static final String TARGET =
			"cy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract";
	private static final String OCCUPANT = "net/minecraft/world/level/block/entity/BeehiveBlockEntity$Occupant";
	private static final String ADVANCED = "cy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntity";
	public static final String ITEM_PRODUCE = "Lcy/jdkdigital/productivebees/util/BeeHelper;getBeeProduce(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/animal/Bee;ZD)Ljava/util/List;";

	/** PB 1.21.1 的精确入口描述符。 */
	public static final String TICK = "tickBees(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;)V";
	/** PB 1.21.1 的精确入口描述符。 */
	public static final String SIMULATE = "simulateBee(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract$Inhabitant;)V";
	/** PB 1.21.1 的精确入口描述符。 */
	public static final String RELEASE = "releaseOccupant(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract$Inhabitant;Lcy/jdkdigital/productivebees/common/block/entity/AdvancedBeehiveBlockEntityAbstract;Ljava/util/List;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$BeeReleaseStatus;)Z";
	/** PB 1.21.1 的精确入口描述符。 */
	public static final String RELEASE_ALL = "releaseAllOccupants(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$BeeReleaseStatus;)Ljava/util/List;";
	/** PB 1.21.1 的精确入口描述符。 */
	public static final String EVENT = "beeReleasePostAction(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/animal/Bee;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$BeeReleaseStatus;)V";

	private PbResourceMixinContract() {}

	/** 在 Mixin 之前检查受支持的方法和关键调用点。 */
    public static boolean supported() {
        try(var input=Thread.currentThread().getContextClassLoader().getResourceAsStream(TARGET+".class")){
            if(input==null)return false;var node=new ClassNode();new ClassReader(input).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            return node.methods.stream().anyMatch(m->m.name.equals("tickBees")) && node.methods.stream().anyMatch(m->m.name.equals("simulateBee") && m.desc.endsWith("$Inhabitant;)V")) && node.methods.stream().anyMatch(m->m.name.equals("releaseBee"));
        }catch(IOException|RuntimeException e){return false;}
    }

	private static long count(ClassNode node, String method, String owner, String call) {
		long count = 0;
		for (var candidate : node.methods) {
			if (!(candidate.name + candidate.desc).equals(method)) continue;
			for (var instruction : candidate.instructions) {
				if (instruction instanceof MethodInsnNode invoke && invoke.owner.equals(owner) && invoke.name.equals(call)) {
					count++;
				}
			}
		}
		return count;
	}

}
