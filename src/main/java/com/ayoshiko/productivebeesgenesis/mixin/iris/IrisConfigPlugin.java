package com.ayoshiko.productivebeesgenesis.mixin.iris;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
	 * Iris 光影兼容 Mixin 配置插件
	 * <br/>
	 * 原理：实现 IMixinConfigPlugin 接口，在 shouldApplyMixin 中检查
	 * LoadingModList 是否包含 "iris" 模组，并读取着色器跳过接口的字节码。
	 * Celeritas 提供 Iris v0 API，但不包含这组接口，不能仅凭模组 ID 应用 Mixin。
	 * 检查过程不加载 Iris 类，避免在转换前提前定义目标或可选依赖。
	 * <p>
	 * 与主 {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin} 风格一致：
	 * 使用 Holder 模式（JVM 类初始化保证线程安全）+ FMLLoader.getLoadingModList() 检测。
	 */
public class IrisConfigPlugin implements IMixinConfigPlugin {

	/** Iris 模组 ID */
	private static final String IRIS_MOD_ID = "iris";
	private static final String SKIP_LIST_TYPE = "net/irisshaders/iris/compat/SkipList";
	private static final String SHADER_INTERFACE_TYPE = "net/irisshaders/iris/mixinterface/ShaderInstanceInterface";

	/**
	 * Holder 模式 — 线程安全的懒加载
	 * <br/>
	 * JVM 类初始化阶段保证原子性，检测结果在 Mixin 阶段计算一次后常驻，
	 * 避免 shouldApplyMixin 每次调用都重复访问 FMLLoader。
	 */
	private static final class Holder {
		/** Iris 是否加载（Mixin 阶段检测，仅计算一次） */
		static final boolean IRIS_LOADED = isIrisLoaded();
		static final boolean SHADER_SKIP_SUPPORTED = IRIS_LOADED && isShaderSkipSupported();
	}

	/**
	 * 在 Mixin 加载阶段检测 Iris 是否已加载
	 * <br/>
	 * 原理：FMLLoader.getLoadingModList() 返回当前正在加载的 mod 列表，
	 * 此阶段早于 ModList.get() 可用时机。getModFileById 返回 null 表示该 mod 未加载。
	 * 异常时安全降级为 false，避免 FMLLoader 状态异常导致 Mixin 阶段崩溃。
	 */
	private static boolean isIrisLoaded() {
		try {
			return FMLLoader.getLoadingModList().getModFileById(IRIS_MOD_ID) != null;
		} catch (LinkageError | RuntimeException t) {
			// LinkageError 覆盖 FMLLoader 版本不兼容；RuntimeException 覆盖状态异常。
			// 不捕获 Throwable 以避免吞没 OOM 等严重错误。
			// 防御性：FMLLoader 状态异常时安全降级，不应用 Iris Mixin
			// Mixin 早期阶段日志系统可能未就绪，使用 System.err 输出（单次检测不会刷屏）
			System.err.println("[ProductiveBeesGenesis] Mixin 阶段检测 Iris 失败, 视为未加载: " + t);
			return false;
		}
	}

	/** 在 Mixin 转换前检查符号，避免旧版或替代实现缺类、缺方法时阻止客户端启动。 */
	private static boolean isShaderSkipSupported() {
		try (InputStream skipListStream = openClassResource(SKIP_LIST_TYPE + ".class");
				InputStream shaderInterfaceStream = openClassResource(SHADER_INTERFACE_TYPE + ".class")) {
			if (skipListStream == null || shaderInterfaceStream == null) {
				System.err.println("[ProductiveBeesGenesis] Iris 缺少着色器跳过接口，停用 ShaderInstanceMixin");
				return false;
			}
			ClassNode skipList = new ClassNode();
			ClassNode shaderInterface = new ClassNode();
			int flags = ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;
			new ClassReader(skipListStream).accept(skipList, flags);
			new ClassReader(shaderInterfaceStream).accept(shaderInterface, flags);
			if (!supportsShaderSkip(skipList, shaderInterface)) {
				System.err.println("[ProductiveBeesGenesis] Iris 着色器跳过接口签名不兼容，停用 ShaderInstanceMixin");
				return false;
			}
			return true;
		} catch (IOException | LinkageError | RuntimeException exception) {
			System.err.println("[ProductiveBeesGenesis] 检查 Iris 着色器跳过接口失败，停用 ShaderInstanceMixin: "
					+ exception);
			return false;
		}
	}

	static boolean supportsShaderSkip(ClassNode skipList, ClassNode shaderInterface) {
		if (skipList == null || shaderInterface == null
				|| !SKIP_LIST_TYPE.equals(skipList.name) || !SHADER_INTERFACE_TYPE.equals(shaderInterface.name)
				|| (skipList.access & Opcodes.ACC_PUBLIC) == 0
				|| (shaderInterface.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE))
						!= (Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE)) {
			return false;
		}
		String skipListDescriptor = "Ljava/lang/invoke/MethodHandle;";
		boolean hasNone = skipList.fields.stream().anyMatch(field -> "NONE".equals(field.name)
				&& skipListDescriptor.equals(field.desc)
				&& (field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
						== (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
		return hasNone && shaderInterface.methods.stream().anyMatch(method -> "setShouldSkip".equals(method.name)
				&& ("(" + skipListDescriptor + ")V").equals(method.desc)
				&& (method.access & Opcodes.ACC_PUBLIC) != 0
				&& (method.access & Opcodes.ACC_STATIC) == 0);
	}

	private static InputStream openClassResource(String resourceName) {
		ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
		if (contextLoader != null) {
			InputStream stream = contextLoader.getResourceAsStream(resourceName);
			if (stream != null) {
				return stream;
			}
		}
		ClassLoader ownLoader = IrisConfigPlugin.class.getClassLoader();
		if (ownLoader != null) {
			InputStream stream = ownLoader.getResourceAsStream(resourceName);
			if (stream != null) {
				return stream;
			}
		}
		return ClassLoader.getSystemResourceAsStream(resourceName);
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	/**
	 * 仅当 Iris 已加载且着色器跳过接口兼容时才应用 Mixin
	 * <br/>
	 * 原理：通过 Holder 模式缓存 Iris 加载状态，避免每次调用都访问 FMLLoader。
	 */
	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return Holder.IRIS_LOADED && Holder.SHADER_SKIP_SUPPORTED;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
