package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class DesignerPreferencesRaceMinecraftTest {
    @Test
    void delayedThemeImportRejectsNewChoiceAndClosedPageButAcceptsUnchangedPage() throws Exception {
        try (var fixture = new Fixture()) {
            var imported = DesignerPalette.preset("dark");
            Path dark = fixture.themeFile("dark.json", imported.json());
            fixture.settings.onFilesDrop(List.of(dark));
            assertEquals(1, fixture.files.size());
            DesignerPreferences.set("theme", "ae_light");
            JsonObject chosen = DesignerPreferences.snapshot();
            fixture.settings.status = "newer choice";
            fixture.completeFile();
            assertEquals(chosen, DesignerPreferences.snapshot());
            assertEquals("newer choice", fixture.settings.status);
            verify(fixture.settings, never()).rebuild();

            fixture.settings.onFilesDrop(List.of(dark));
            fixture.completeFile();
            assertEquals("custom_dark", DesignerPreferences.text("theme"));
            assertEquals(imported, DesignerPreferences.palette());
            assertEquals("designer.status.theme_applied", fixture.settings.status);
            verify(fixture.settings).rebuild();
            DesignerPreferences.save(message -> assertEquals("designer.status.preferences_saved", message));
            fixture.completeFile();
            assertFalse(DesignerPreferences.dirty());

            Path light = fixture.themeFile("light.json", DesignerPalette.preset("light").json());
            JsonObject beforeClose = DesignerPreferences.snapshot();
            fixture.settings.onFilesDrop(List.of(light));
            fixture.settings.onClose();
            assertSame(fixture.parent, fixture.minecraft.screen);
            // Reopening the same object cannot make its previous import current again.
            fixture.minecraft.screen = fixture.settings;
            fixture.settings.status = "reopened";
            fixture.completeFile();
            assertEquals(beforeClose, DesignerPreferences.snapshot());
            assertEquals("reopened", fixture.settings.status);
            assertFalse(DesignerPreferences.dirty());
        }
    }

    @Test
    void oldSuccessfulSaveKeepsNewControlValueDirtyAndLatestSaveCanClose() throws Exception {
        try (var fixture = new Fixture()) {
            DesignerPreferences.set("density", "compact");
            fixture.saveAndClose();
            assertTrue((boolean) field(DesignerSettingsScreen.class, "saving").get(fixture.settings));
            assertEquals(1, fixture.files.size());
            DesignerPreferences.set("density", "spacious");

            fixture.completeFile();
            JsonObject persisted = DesignerJsonCodec.parse(Files.readString(
                    fixture.directory.resolve("preferences.json"), StandardCharsets.UTF_8));
            assertEquals("compact", persisted.get("density").getAsString());
            assertEquals("spacious", DesignerPreferences.text("density"));
            assertTrue(DesignerPreferences.dirty());
            assertFalse((boolean) field(DesignerSettingsScreen.class, "saving").get(fixture.settings));
            assertEquals("designer.status.preferences_changed", fixture.settings.status);
            assertSame(fixture.settings, fixture.minecraft.screen);
            verify(fixture.minecraft, never()).setScreen(any());

            fixture.saveAndClose();
            fixture.completeFile();
            persisted = DesignerJsonCodec.parse(Files.readString(
                    fixture.directory.resolve("preferences.json"), StandardCharsets.UTF_8));
            assertEquals("spacious", persisted.get("density").getAsString());
            assertFalse(DesignerPreferences.dirty());
            assertSame(fixture.parent, fixture.minecraft.screen);
            verify(fixture.minecraft).setScreen(fixture.parent);
        }
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture implements AutoCloseable {
        final Minecraft minecraft = mock(Minecraft.class);
        final Screen parent = mock(Screen.class);
        final Queue<Runnable> files = new ArrayDeque<>();
        final Queue<Runnable> callbacks = new ArrayDeque<>();
        final Map<String, Object> priorPreferences = new LinkedHashMap<>();
        final Path directory;
        final DesignerSettingsScreen settings;
        private final MockedStatic<Minecraft> client;
        private final MockedStatic<DesignerTheme> theme;
        private final MockedStatic<CompletableFuture> futures;

        Fixture() throws Exception {
            Path evidence = Path.of("build", "audit-repairs-20261108", "RP04", "preferences-race")
                    .toAbsolutePath().normalize();
            Files.createDirectories(evidence);
            directory = Files.createTempDirectory(evidence, "case-");
            for (String name : List.of("values", "palette", "directory", "revision", "savedRevision", "problem")) {
                priorPreferences.put(name, field(DesignerPreferences.class, name).get(null));
            }
            client = mockStatic(Minecraft.class);
            client.when(Minecraft::getInstance).thenReturn(minecraft);
            theme = mockStatic(DesignerTheme.class);
            futures = mockStatic(CompletableFuture.class, invocation -> {
                if (!invocation.getMethod().getName().equals("supplyAsync") || invocation.getArguments().length != 2) {
                    return invocation.callRealMethod();
                }
                assertSame(field(DesignerProjectFiles.class, "FILES").get(null), invocation.getArgument(1));
                Supplier<?> action = invocation.getArgument(0);
                var pending = new CompletableFuture<Object>();
                files.add(() -> {
                    try {
                        pending.complete(action.get());
                    } catch (Throwable failure) {
                        pending.completeExceptionally(failure);
                    }
                });
                return pending;
            });
            doAnswer(invocation -> {
                callbacks.add(invocation.getArgument(0));
                return null;
            }).when(minecraft).execute(any(Runnable.class));
            doAnswer(invocation -> {
                minecraft.screen = invocation.getArgument(0);
                return null;
            }).when(minecraft).setScreen(any());
            DesignerPreferences.apply(DesignerPreferences.defaults());
            field(DesignerPreferences.class, "directory").set(null, directory);
            settings = spy(new DesignerSettingsScreen(parent));
            field(Screen.class, "minecraft").set(settings, minecraft);
            doNothing().when(settings).rebuild();
            minecraft.screen = settings;
            // Establish the clean starting state through the real writer and completion callback.
            DesignerPreferences.save(message -> assertEquals("designer.status.preferences_saved", message));
            completeFile();
            assertFalse(DesignerPreferences.dirty());
        }

        Path themeFile(String name, JsonObject value) throws Exception {
            Path path = directory.resolve(name);
            Files.writeString(path, value.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            return path;
        }

        void completeFile() {
            assertEquals(1, files.size(), "One bounded file operation must be pending");
            files.remove().run();
            assertEquals(1, callbacks.size(), "The real operation must post one client completion");
            callbacks.remove().run();
            assertTrue(files.isEmpty());
            assertTrue(callbacks.isEmpty());
        }

        void saveAndClose() throws ReflectiveOperationException {
            var save = DesignerSettingsScreen.class.getDeclaredMethod("savePreferences", boolean.class);
            save.setAccessible(true);
            save.invoke(settings, true);
        }

        @Override
        public void close() throws Exception {
            files.clear();
            callbacks.clear();
            for (var previous : priorPreferences.entrySet()) {
                field(DesignerPreferences.class, previous.getKey()).set(null, previous.getValue());
            }
            futures.close();
            theme.close();
            client.close();
        }
    }
}
