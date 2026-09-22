package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicySnapshot;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** 同步入口仅用于显式编译／验证；正式运行通过游标逐名额推进。 */
public final class PbProductPolicyCompiler {
	public record Result(ProductPolicySnapshot snapshot, List<String> diagnostics) {
		public Result { diagnostics = List.copyOf(diagnostics); }
	}
	public static Result compile(ServerLevel level, long revision) {
		var cursor = new PbProductPolicyCompilation(level, revision);
		while (!cursor.step()) { }
		return cursor.result();
	}
	private PbProductPolicyCompiler() { }
}
