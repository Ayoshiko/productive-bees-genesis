package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 移动物品产物的完整记录；以对象身份区分内容相同的两条产物。 */
final class DesignerOutputList {
	private DesignerOutputList() {
	}

	static boolean remove(JsonArray outputs, JsonObject output) {
		int index = index(outputs, output);
		if (index < 0) {
			return false;
		}
		outputs.remove(index);
		return true;
	}

	static boolean move(JsonArray outputs, JsonObject output, int destination) {
		int source = index(outputs, output);
		if (source < 0 || destination < 0 || destination >= outputs.size() || source == destination) {
			return false;
		}
		JsonElement held = outputs.get(source);
		int direction = source < destination ? 1 : -1;
		for (int index = source; index != destination; index += direction) {
			outputs.set(index, outputs.get(index + direction));
		}
		outputs.set(destination, held);
		return true;
	}

	static int index(JsonArray outputs, JsonObject output) {
		for (int index = 0; index < outputs.size(); index++) {
			if (outputs.get(index) == output) {
				return index;
			}
		}
		return -1;
	}

	record Transfer(JsonArray outputs, JsonObject output) {
		boolean accepts(JsonArray target) {
			return outputs == target && index(outputs, output) >= 0;
		}

		boolean drop(JsonArray target, JsonObject before) {
			return accepts(target) && move(outputs, output, index(target, before));
		}
	}
}
