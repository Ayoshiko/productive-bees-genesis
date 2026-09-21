package com.ayoshiko.productivebeesgenesis.client.screen;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

/** 检查编译后的缓存所有权，避免弱键的值经按钮反向强引用窗口。 */
class OverlayCacheOwnershipTest {

	@ParameterizedTest
	@ValueSource(strings = {
			"AeInputOverlay", "AeOutputOverlay", "ApiaryDirectEjectOverlay",
			"CentrifugeDirectAeOutputOverlay", "DirectContainerOutputOverlay", "SmeltingCompatOverlay"
	})
	void staticWindowCachesDoNotOwnButtons(String overlay) throws Exception {
		String resource = "/com/ayoshiko/productivebeesgenesis/client/screen/" + overlay + ".class";
		AtomicInteger checked = new AtomicInteger();
		try (InputStream stream = getClass().getResourceAsStream(resource)) {
			assertNotNull(stream, resource);
			new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public FieldVisitor visitField(int access, String name, String descriptor,
						String signature, Object value) {
					if ((access & Opcodes.ACC_STATIC) != 0 && signature != null
							&& signature.contains("GuiSideConfiguration<")) {
						assertTrue(signature.contains(";Ljava/lang/ref/WeakReference<"),
								overlay + "." + name + " must not retain its window through a button");
						checked.incrementAndGet();
					}
					return null;
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		}
		assertTrue(checked.get() > 0, "No window cache checked in " + overlay);
	}
}
