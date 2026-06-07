package io.kairo.code.core.guardrail;

import io.kairo.api.guardrail.GuardrailContext;
import io.kairo.api.guardrail.GuardrailDecision;
import io.kairo.api.guardrail.GuardrailPayload;
import io.kairo.api.guardrail.GuardrailPhase;
import io.kairo.api.guardrail.GuardrailPolicy;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Blocks bash commands that write to the filesystem when the agent is in
 * coordinator mode. The coordinator should delegate file modifications to
 * coder workers — allowing bash writes would undermine the tool restriction.
 *
 * <p>Detects common write patterns: {@code >}, {@code >>}, {@code tee},
 * {@code mv}, {@code cp}, {@code rm}, {@code mkdir}, {@code touch},
 * {@code sed -i}, {@code install}.
 */
public class BashWriteGuardPolicy implements GuardrailPolicy {

    private static final Logger log = LoggerFactory.getLogger(BashWriteGuardPolicy.class);
    private static final String POLICY_NAME = "bash-write-guard";

    private static final Pattern WRITE_PATTERN = Pattern.compile(
            "(?:^|[;&|]|\\$\\()\\s*(?:"
                    + "(?:>|>>)\\s*[^&]"
                    + "|tee\\s"
                    + "|\\bsed\\s+-i"
                    + "|\\bdd\\s"
                    + "|\\binstall\\s"
                    + "|\\bmv\\s"
                    + "|\\bcp\\s"
                    + "|\\brm\\s"
                    + "|\\bmkdir\\s"
                    + "|\\btouch\\s"
                    + "|\\bchmod\\s"
                    + "|\\bchown\\s"
                    + "|\\bln\\s"
                    + "|\\bpatch\\s"
                    + ")",
            Pattern.MULTILINE);

    @Override
    public Mono<GuardrailDecision> evaluate(GuardrailContext context) {
        if (context.phase() != GuardrailPhase.PRE_TOOL) {
            return Mono.just(GuardrailDecision.allow(POLICY_NAME));
        }
        if (!"bash".equals(context.targetName())) {
            return Mono.just(GuardrailDecision.allow(POLICY_NAME));
        }

        if (!(context.payload() instanceof GuardrailPayload.ToolInput toolInput)) {
            return Mono.just(GuardrailDecision.allow(POLICY_NAME));
        }
        Map<String, Object> args = toolInput.args();
        if (args == null) {
            return Mono.just(GuardrailDecision.allow(POLICY_NAME));
        }
        Object cmdObj = args.get("command");
        if (cmdObj == null) {
            return Mono.just(GuardrailDecision.allow(POLICY_NAME));
        }
        String command = cmdObj.toString();
        if (WRITE_PATTERN.matcher(command).find()) {
            log.info("BashWriteGuard blocked write command in coordinator mode: {}",
                    command.length() > 100 ? command.substring(0, 100) + "..." : command);
            return Mono.just(GuardrailDecision.deny(
                    "Coordinator agents cannot write files via bash. "
                            + "Use the task tool to spawn a coder worker for file modifications.",
                    POLICY_NAME));
        }

        return Mono.just(GuardrailDecision.allow(POLICY_NAME));
    }

    @Override
    public int order() {
        return 50;
    }
}
