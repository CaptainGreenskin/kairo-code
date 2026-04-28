package io.kairo.code.examples;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test: Kairo Code fixes a failing test in a real Maven project.
 *
 * <p>Requires: KAIRO_CODE_API_KEY environment variable set.
 * Run manually: {@code mvn test -pl kairo-code-examples -Dtest=FixFailingTestE2E}
 */
@EnabledIfEnvironmentVariable(named = "KAIRO_CODE_API_KEY", matches = ".+")
class FixFailingTestE2E {

    @TempDir
    Path tempDir;

    @Test
    void agentFixesFailingTest() throws Exception {
        // 1. Copy fixture to temp dir
        Path fixture = Path.of("src/test/resources/fixtures/calculator-bug");
        Path workspace = tempDir.resolve("calculator-bug");
        copyDirectory(fixture, workspace);

        // 2. Verify fixture fails first
        int beforeResult = runMvnTest(workspace);
        assertNotEquals(0, beforeResult, "Fixture should fail before agent runs");

        // 3. Run Kairo Code
        String apiKey = System.getenv("KAIRO_CODE_API_KEY");
        String baseUrl = System.getenv().getOrDefault("KAIRO_CODE_BASE_URL", "https://api.openai.com");
        String model = System.getenv().getOrDefault("KAIRO_CODE_MODEL", "gpt-4o");

        CodeAgentConfig config = new CodeAgentConfig(
                apiKey, baseUrl, model, 30, workspace.toString(), null
        );
        Agent agent = CodeAgentFactory.create(config);

        String task = """
                The Maven project in the current working directory has a failing test.
                1. Run 'mvn test' in %s to see which test fails
                2. Read the source code to find the bug
                3. Fix the bug
                4. Run 'mvn test' again to verify all tests pass
                """.formatted(workspace);

        Msg response = agent.call(Msg.of(MsgRole.USER, task)).block();
        assertNotNull(response, "Agent should return a response");

        // 4. Verify tests pass now
        int afterResult = runMvnTest(workspace);
        assertEquals(0, afterResult, "All tests should pass after agent fix");
    }

    private int runMvnTest(Path dir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("mvn", "test", "-q")
                .directory(dir.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        // Drain output
        proc.getInputStream().transferTo(System.out);
        return proc.waitFor();
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
