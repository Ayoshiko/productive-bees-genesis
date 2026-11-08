package com.ayoshiko.productivebeesgenesis;

import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("minecraft")
@EnabledIfSystemProperty(named = "productivebeesgenesis.test.kubejs", matches = "true")
class KubeJSPluginMinecraftTest {
	@Test
	void automaticallyDiscoveredPluginRegistersEventsAndScriptBindings() throws ReflectiveOperationException {
		assertTrue(ModList.get().isLoaded("kubejs"), "此检查必须由真实 KubeJS 模组启动提供发现结果");
		assertTrue(ModList.get().isLoaded("rhino"));

		Class<?> pluginsType = Class.forName("dev.latvian.mods.kubejs.plugin.KubeJSPlugins");
		var plugins = (List<?>) pluginsType.getMethod("getAll").invoke(null);
		String pluginName = "com.ayoshiko.productivebeesgenesis.compat.kubejs.ProductiveBeesGenesisKubeJSPlugin";
		assertEquals(1L, plugins.stream().filter(plugin -> plugin.getClass().getName().equals(pluginName)).count(),
				"根级 kubejs.plugins.txt 必须自动发现且仅加载本插件一次");

		Class<?> groupsType = Class.forName("dev.latvian.mods.kubejs.event.EventGroups");
		Object allGroups = groupsType.getField("ALL").get(null);
		Object groups = allGroups.getClass().getMethod("get").invoke(allGroups);
		var groupMap = (Map<?, ?>) groupsType.getMethod("map").invoke(groups);
		Object group = groupMap.get("MyriadBeeEvents");
		assertNotNull(group, "插件必须进入 KubeJS 的真实事件组注册表");
		var handlers = (Map<?, ?>) group.getClass().getMethod("getHandlers").invoke(group);
		Object register = handlers.get("register");
		assertNotNull(register, "脚本入口为 MyriadBeeEvents.register");
		Class<?> scriptType = Class.forName("dev.latvian.mods.kubejs.script.ScriptType");
		assertSame(scriptType.getField("SERVER").get(null),
				register.getClass().getField("scriptTypePredicate").get(register));

		Class<?> kubejsType = Class.forName("dev.latvian.mods.kubejs.KubeJS");
		Object manager = kubejsType.getMethod("getStartupScriptManager").invoke(null);
		assertNotNull(manager, "KubeJS 构造器必须已经建立 startup 脚本管理器");
		Object factory = manager.getClass().getField("contextFactory").get(manager);
		assertNotNull(factory, "真实 startup 脚本加载必须已经初始化 Rhino factory");

		// 使用已启动管理器的上下文工厂，bindings 由 KubeJS 按已发现插件自动注册。
		Object context = factory.getClass().getMethod("enter").invoke(factory);
		Object scope = context.getClass().getField("topLevelScope").get(context);
		Class<?> contextType = Class.forName("dev.latvian.mods.rhino.Context");
		Class<?> scopeType = Class.forName("dev.latvian.mods.rhino.Scriptable");
		Object result = contextType.getMethod("evaluateString", scopeType, String.class,
				String.class, int.class, Object.class).invoke(context, scope,
				"MYRIAD_CREATIONS === 'productivebees:myriadcreations' && PB_MOD_ID === 'productivebees'",
				"productivebeesgenesis-kubejs-bindings-check", 1, null);
		assertEquals(Boolean.TRUE, result, "全局常量必须可以在真实 Rhino 脚本 scope 中使用");
	}
}
