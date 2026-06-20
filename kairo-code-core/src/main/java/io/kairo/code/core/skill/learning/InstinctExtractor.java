package io.kairo.code.core.skill.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts {@link Instinct}s from session tool observations using heuristic pattern detection.
 *
 * <p>Patterns detected:
 * <ol>
 *   <li><strong>Error recovery</strong>: tool failure → same tool success (learned workaround)</li>
 *   <li><strong>Tool sequence</strong>: repeated 3+ occurrence of the same tool chain
 *       (e.g., grep → read → edit)</li>
 * </ol>
 *
 * <p>Instincts are persisted to {@code ~/.kairo-code/skill-learning/instincts/} as JSON files.
 */
public final class InstinctExtractor {

    private static final Logger log = LoggerFactory.getLogger(InstinctExtractor.class);
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final int MIN_SEQUENCE_LENGTH = 2;
    private static final int MIN_SEQUENCE_OCCURRENCES = 3;

    private final Path instinctsDir;

    public InstinctExtractor() {
        this(Path.of(System.getProperty("user.home"),
                ".kairo-code", "skill-learning", "instincts"));
    }

    InstinctExtractor(Path instinctsDir) {
        this.instinctsDir = instinctsDir;
    }

    public List<Instinct> extract(List<ToolObservation> observations) {
        List<Instinct> results = new ArrayList<>();
        results.addAll(detectErrorRecovery(observations));
        results.addAll(detectToolSequences(observations));

        for (Instinct instinct : results) {
            Instinct merged = mergeWithExisting(instinct);
            persist(merged);
        }

        if (!results.isEmpty()) {
            log.info("Extracted {} instinct(s) from {} observations", results.size(), observations.size());
        }
        return results;
    }

    private List<Instinct> detectErrorRecovery(List<ToolObservation> observations) {
        List<Instinct> results = new ArrayList<>();
        for (int i = 0; i < observations.size() - 1; i++) {
            ToolObservation failed = observations.get(i);
            ToolObservation next = observations.get(i + 1);
            if (!failed.success() && next.success()
                    && failed.toolName().equals(next.toolName())) {
                String trigger = "When " + failed.toolName() + " fails with: "
                        + truncate(failed.errorMessage(), 100);
                String action = "Retry with adjusted input: "
                        + truncate(next.inputSummary(), 100);
                String id = Instinct.computeId(trigger, action);
                results.add(new Instinct(id, trigger, action, "debugging", 0.5, 1));
            }
        }
        return results;
    }

    private List<Instinct> detectToolSequences(List<ToolObservation> observations) {
        List<Instinct> results = new ArrayList<>();
        if (observations.size() < MIN_SEQUENCE_LENGTH * MIN_SEQUENCE_OCCURRENCES) return results;

        List<String> toolNames = observations.stream()
                .filter(ToolObservation::success)
                .map(ToolObservation::toolName)
                .toList();

        for (int seqLen = MIN_SEQUENCE_LENGTH; seqLen <= 4; seqLen++) {
            Map<String, Integer> seqCounts = new HashMap<>();
            for (int i = 0; i <= toolNames.size() - seqLen; i++) {
                String seq = String.join(" → ", toolNames.subList(i, i + seqLen));
                seqCounts.merge(seq, 1, Integer::sum);
            }
            for (var entry : seqCounts.entrySet()) {
                if (entry.getValue() >= MIN_SEQUENCE_OCCURRENCES) {
                    String trigger = "Repeated workflow pattern";
                    String action = entry.getKey();
                    String id = Instinct.computeId(trigger, action);
                    double confidence = Math.min(1.0, 0.3 + entry.getValue() * 0.1);
                    results.add(new Instinct(id, trigger, action, "workflow",
                            confidence, entry.getValue()));
                }
            }
        }
        return results;
    }

    private Instinct mergeWithExisting(Instinct newInstinct) {
        Path file = instinctsDir.resolve(newInstinct.id() + ".json");
        if (!Files.exists(file)) return newInstinct;
        try {
            Instinct existing = MAPPER.readValue(Files.readString(file), Instinct.class);
            return existing.withMoreEvidence();
        } catch (IOException e) {
            return newInstinct;
        }
    }

    private void persist(Instinct instinct) {
        try {
            Files.createDirectories(instinctsDir);
            Files.writeString(instinctsDir.resolve(instinct.id() + ".json"),
                    MAPPER.writeValueAsString(instinct));
        } catch (IOException e) {
            log.debug("Failed to persist instinct {}: {}", instinct.id(), e.getMessage());
        }
    }

    public List<Instinct> loadAll() {
        List<Instinct> result = new ArrayList<>();
        if (!Files.isDirectory(instinctsDir)) return result;
        try (var files = Files.list(instinctsDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                try {
                    result.add(MAPPER.readValue(Files.readString(p), Instinct.class));
                } catch (IOException e) {
                    log.debug("Skipping corrupt instinct file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Failed to list instincts: {}", e.getMessage());
        }
        return result;
    }

    public List<Instinct> loadByDomain(String domain) {
        return loadAll().stream().filter(i -> domain.equals(i.domain())).toList();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
