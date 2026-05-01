package io.kairo.code.core.skill;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.code.core.skill.FsSkillLoader.SkillWithSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillWatcherTest {

    @TempDir
    Path tempDir;

    @Test
    void reloadNowDeliversCurrentSkills() throws Exception {
        Path globalDir = Files.createDirectory(tempDir.resolve("global"));
        Files.writeString(globalDir.resolve("init.md"), """
                ---
                name: init
                description: Init skill
                ---

                # Init

                Initial.
                """);

        FsSkillLoader loader = new FsSkillLoader(globalDir, null);
        List<List<SkillWithSource>> received = new CopyOnWriteArrayList<>();
        try (SkillWatcher watcher = new SkillWatcher(loader)) {
            watcher.addListener(received::add);
            watcher.reloadNow();

            // reloadNow is synchronous
        }
        assertThat(received).isNotEmpty();
        assertThat(received.get(0)).anySatisfy(s ->
                assertThat(s.skill().name()).isEqualTo("init"));
    }

    @Test
    void hotReloadTriggersListener() throws Exception {
        // Ensure the directory exists before creating the watcher so it gets registered
        Path globalDir = Files.createDirectories(tempDir.resolve("global"));
        Path projectDir = Files.createDirectories(tempDir.resolve("project"));

        FsSkillLoader loader = new FsSkillLoader(globalDir, projectDir);
        List<List<SkillWithSource>> received = new CopyOnWriteArrayList<>();
        try (SkillWatcher watcher = new SkillWatcher(loader)) {
            watcher.addListener(received::add);

            // Give the watcher thread time to register directories
            Thread.sleep(300);

            // Create a skill file to trigger watch event
            Files.writeString(globalDir.resolve("hello.md"), """
                    ---
                    name: hello
                    description: Hello skill
                    ---

                    # Hello

                    World.
                    """);

            // Wait up to 5 seconds for debounce + notification
            long deadline = System.currentTimeMillis() + 5000;
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
        }
        assertThat(received).as("Listener should have been called after file change").isNotEmpty();
        assertThat(received.get(0)).anySatisfy(s ->
                assertThat(s.skill().name()).isEqualTo("hello"));
    }

    @Test
    void debounceCoalescesRapidChanges() throws Exception {
        Path globalDir = Files.createDirectories(tempDir.resolve("global"));

        FsSkillLoader loader = new FsSkillLoader(globalDir, null);

        List<List<SkillWithSource>> received = new CopyOnWriteArrayList<>();
        try (SkillWatcher watcher = new SkillWatcher(loader)) {
            watcher.addListener(received::add);

            // Give the watcher thread time to register directories
            Thread.sleep(300);

            // Rapidly create multiple files
            for (int i = 0; i < 5; i++) {
                Files.writeString(globalDir.resolve("skill-" + i + ".md"), """
                        ---
                        name: skill-%d
                        description: Skill %d
                        ---

                        # Skill %d
                        """.formatted(i, i, i));
                Thread.sleep(100);
            }

            // Wait for debounce to fire
            long deadline = System.currentTimeMillis() + 5000;
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
        }

        // Should have received at least one reload
        assertThat(received).as("Should have received reload events").isNotEmpty();
        // Final reload should contain all 5 skills
        List<SkillWithSource> last = received.get(received.size() - 1);
        assertThat(last).hasSize(5);
    }

    @Test
    void listenerExceptionDoesNotBreakOtherListeners() throws Exception {
        Path globalDir = Files.createDirectories(tempDir.resolve("global"));
        FsSkillLoader loader = new FsSkillLoader(globalDir, null);

        AtomicBoolean goodListenerCalled = new AtomicBoolean(false);

        try (SkillWatcher watcher = new SkillWatcher(loader)) {
            // Add a throwing listener first
            watcher.addListener(skills -> {
                throw new RuntimeException("boom");
            });

            // Add a good listener
            watcher.addListener(skills -> goodListenerCalled.set(true));

            // Give the watcher thread time to register directories
            Thread.sleep(300);

            Files.writeString(globalDir.resolve("test.md"), """
                    ---
                    name: test
                    description: Test
                    ---

                    # Test
                    """);

            long deadline = System.currentTimeMillis() + 5000;
            while (!goodListenerCalled.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }
        }
        assertThat(goodListenerCalled).as("Good listener should have been called").isTrue();
    }
}
