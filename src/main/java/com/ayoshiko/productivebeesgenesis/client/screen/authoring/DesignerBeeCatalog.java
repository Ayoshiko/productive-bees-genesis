package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 同一连接共享蜂种名称索引；来源仍来自服务端，字符串索引不持有世界和玩家。 */
final class DesignerBeeCatalog {
    private static WeakReference<ClientLevel> level = new WeakReference<>(null);
    private static Object definitions;
    private static Language language;
    private static long draftVersion = -1;
    private static Source current;

    private DesignerBeeCatalog() {
    }

    static Source source() {
        var mc = Minecraft.getInstance();
        Object data = BeeReloadListener.INSTANCE.getData();
        Language locale = Language.getInstance();
        long version = DesignerDraftIndex.version();
        if (current == null || level.get() != mc.level || definitions != data || language != locale || draftVersion != version) {
            current = new Source();
            level = new WeakReference<>(mc.level);
            definitions = data;
            language = locale;
            draftVersion = version;
        }
        return current;
    }

    static void clear() {
        current = null;
        level.clear();
        definitions = null;
        language = null;
        draftVersion = -1;
    }

    static final class Source {
        private final Iterator<String> pending;
        private final DesignerSearch.Index<String> index;
        private int size;
        private boolean ready;

        private Source() {
            Map<String, Integer> priority = new HashMap<>();
            List<String> drafts = DesignerDraftIndex.pendingIds();
            for (String id : drafts) {
                priority.put(id, priority.size());
            }
            TreeSet<String> ids = new TreeSet<>(BeeDesignerEggs.bees());
            ids.addAll(drafts);
            ids.removeIf(id -> BeeDesignerClient.retired(id) && !priority.containsKey(id));
            pending = ids.iterator();
            index = new DesignerSearch.Index<>(java.util.Comparator.comparingInt((String id) ->
                    priority.getOrDefault(id, Integer.MAX_VALUE)).thenComparing(id -> id));
        }

        boolean advance() {
            if (ready) {
                return false;
            }
            long started = System.nanoTime();
            int budget = 512;
            while (pending.hasNext() && budget-- > 0 && System.nanoTime() - started < 2_000_000L) {
                String id = pending.next();
                var name = DesignerDraftIndex.name(id);
                if (name == null) {
                    name = BeeDesignerClient.beeName(id);
                }
                index.add(id, ResourceLocation.parse(id).getNamespace(), DesignerSearch.indexed(id + " " + name.getString()), List.of());
                size++;
            }
            if (!pending.hasNext()) {
                index.finish();
                ready = true;
            }
            return true;
        }

        List<String> search(String query) {
            return index.search(query);
        }

        int size() {
            return size;
        }

        boolean ready() {
            return ready;
        }
    }
}
