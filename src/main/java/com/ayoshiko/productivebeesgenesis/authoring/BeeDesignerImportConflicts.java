package com.ayoshiko.productivebeesgenesis.authoring;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/** 身份与 PB 名称键分别检查；覆盖只针对同一身份，跨身份名称碰撞必须复制。 */
public record BeeDesignerImportConflicts(boolean worldIdentity, boolean localDraft, List<String> nameCollisions) {
	public static BeeDesignerImportConflicts inspect(String id, Collection<String> world, Collection<String> drafts) {
		String nameKey = BeeDesignerDocument.translationKey(id);
		List<String> collisions = Stream.concat(world.stream(), drafts.stream()).distinct()
				.filter(other -> !other.equals(id) && BeeDesignerDocument.translationKey(other).equals(nameKey)).sorted().toList();
		return new BeeDesignerImportConflicts(world.contains(id), drafts.contains(id), collisions);
	}

	public boolean canKeepIdentity() {
		return nameCollisions.isEmpty();
	}
}
