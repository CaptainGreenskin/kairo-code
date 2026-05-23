package io.kairo.code.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import picocli.CommandLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Tests for ACP server mode functionality.
 */
public class KairoCodeAcpServerTest {

    private static String originalHome;
    private static Path isolatedHome;

    @BeforeAll
    static void enableDryRun() throws Exception {
        // Without dryrun, cmd.execute("--acp-server", ...) actually starts the ACP server
        // which blocks on stdin reading JSON-RPC frames — test never returns.
        System.setProperty("kairo.code.dryrun", "true");
        // ConfigLoader reads ~/.kairo-code/config.properties — on dev machines that file
        // typically contains a real api-key so smoke / interactive use works. Tests that
        // assert "missing api-key fails" therefore see a defaulted key and false-pass to
        // exit 0. Isolate user.home to a clean temp dir so behavior is deterministic
        // regardless of dev workstation state. Same pattern as KairoCodeMainOptionTest.
        originalHome = System.getProperty("user.home");
        isolatedHome = Files.createTempDirectory("acp-server-test-home");
        System.setProperty("user.home", isolatedHome.toString());
    }

    @AfterAll
    static void disableDryRun() {
        System.clearProperty("kairo.code.dryrun");
        if (originalHome != null) {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testAcpServerFlagParsesCorrectly() {
        KairoCodeMain main = new KairoCodeMain();
        CommandLine cmd = new CommandLine(main);
        
        cmd.parseArgs("--acp-server", "--api-key", "test-key");
        assertTrue(main.acpServer, "ACP server flag should be set");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testAcpServerRequiresApiKey() {
        KairoCodeMain main = new KairoCodeMain();
        CommandLine cmd = new CommandLine(main);
        
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err));
        
        int result = cmd.execute("--acp-server");
        assertNotEquals(0, result, "Should fail without API key");
        
        String errorOutput = err.toString(StandardCharsets.UTF_8);
        assertTrue(errorOutput.contains("API key"), "Error should mention API key");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testAcpServerModeExitsCleanlyWithValidConfig() {
        KairoCodeMain main = new KairoCodeMain();
        CommandLine cmd = new CommandLine(main);
        
        // Parse with minimal valid config
        cmd.parseArgs("--acp-server", "--api-key", "sk-test-key-123");
        assertTrue(main.acpServer, "ACP server flag should be set");
        
        // Note: We can't actually run the server in tests as it blocks on stdin
        // But we can verify the configuration is set up correctly
        assertFalse(cmd.getParseResult().hasMatchedOption("--task"),
            "ACP server mode should not require task");
    }
}