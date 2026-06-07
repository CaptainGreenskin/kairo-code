package io.kairo.code.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts {@code export const meta = {...}} from a workflow script source
 * and parses it into a {@link WorkflowMeta}.
 */
public final class WorkflowMetaParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern META_PATTERN =
            Pattern.compile("export\\s+const\\s+meta\\s*=\\s*\\{", Pattern.MULTILINE);

    private WorkflowMetaParser() {}

    public static WorkflowMeta parse(String scriptSource) {
        Matcher m = META_PATTERN.matcher(scriptSource);
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "Script must begin with 'export const meta = { ... }' block");
        }
        int braceStart = m.end() - 1; // position of the opening '{'
        int braceEnd = findMatchingBrace(scriptSource, braceStart);
        if (braceEnd < 0) {
            throw new IllegalArgumentException("Unmatched brace in meta block");
        }
        String jsonBody = scriptSource.substring(braceStart, braceEnd + 1);
        // JS object literals allow trailing commas and unquoted keys — normalize
        jsonBody = normalizeToJson(jsonBody);
        try {
            JsonNode root = MAPPER.readTree(jsonBody);
            String name = root.has("name") ? root.get("name").asText() : "unnamed";
            String description = root.has("description") ? root.get("description").asText() : null;
            String whenToUse = root.has("whenToUse") ? root.get("whenToUse").asText() : null;
            List<WorkflowMeta.Phase> phases = parsePhases(root);
            return new WorkflowMeta(name, description, phases, whenToUse);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse meta JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the script body (everything after the meta block closing brace and optional
     * newline/semicolons).
     */
    public static String extractScriptBody(String scriptSource) {
        Matcher m = META_PATTERN.matcher(scriptSource);
        if (!m.find()) return scriptSource;
        int braceStart = m.end() - 1;
        int braceEnd = findMatchingBrace(scriptSource, braceStart);
        if (braceEnd < 0) return scriptSource;
        // skip past trailing whitespace/semicolons/newlines
        int bodyStart = braceEnd + 1;
        while (bodyStart < scriptSource.length()) {
            char c = scriptSource.charAt(bodyStart);
            if (c == ';' || c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                bodyStart++;
            } else {
                break;
            }
        }
        return scriptSource.substring(bodyStart);
    }

    private static int findMatchingBrace(String s, int start) {
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // skip escaped char
                } else if (c == stringChar) {
                    inString = false;
                }
            } else {
                if (c == '\'' || c == '"' || c == '`') {
                    inString = true;
                    stringChar = c;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    /**
     * Single-pass normalization: convert JS object literal to valid JSON.
     * Handles single-quoted strings → double-quoted, unquoted keys → quoted,
     * trailing commas, and JS line comments.
     */
    static String normalizeToJson(String jsObject) {
        StringBuilder out = new StringBuilder(jsObject.length() + 64);
        int len = jsObject.length();
        int i = 0;
        while (i < len) {
            char c = jsObject.charAt(i);

            // Skip // line comments
            if (c == '/' && i + 1 < len && jsObject.charAt(i + 1) == '/') {
                while (i < len && jsObject.charAt(i) != '\n') i++;
                continue;
            }

            // Double-quoted string: copy verbatim
            if (c == '"') {
                int end = skipString(jsObject, i, '"');
                out.append(jsObject, i, end);
                i = end;
                continue;
            }

            // Single-quoted string → double-quoted
            if (c == '\'') {
                out.append('"');
                i++;
                while (i < len) {
                    char sc = jsObject.charAt(i);
                    if (sc == '\\' && i + 1 < len) {
                        out.append(sc).append(jsObject.charAt(i + 1));
                        i += 2;
                    } else if (sc == '\'') {
                        out.append('"');
                        i++;
                        break;
                    } else {
                        // Escape embedded double quotes
                        if (sc == '"') out.append('\\');
                        out.append(sc);
                        i++;
                    }
                }
                continue;
            }

            // Unquoted key: identifier followed by colon
            if (isIdentStart(c) && isKeyPosition(out)) {
                int keyEnd = i;
                while (keyEnd < len && isIdentPart(jsObject.charAt(keyEnd))) keyEnd++;
                // Skip whitespace after key
                int afterKey = keyEnd;
                while (afterKey < len && jsObject.charAt(afterKey) == ' ') afterKey++;
                if (afterKey < len && jsObject.charAt(afterKey) == ':') {
                    out.append('"').append(jsObject, i, keyEnd).append('"');
                    i = keyEnd;
                    continue;
                }
            }

            // Trailing comma: skip comma when next non-whitespace is } or ]
            if (c == ',') {
                int next = i + 1;
                while (next < len && (jsObject.charAt(next) == ' ' || jsObject.charAt(next) == '\n'
                        || jsObject.charAt(next) == '\r' || jsObject.charAt(next) == '\t')) next++;
                if (next < len && (jsObject.charAt(next) == '}' || jsObject.charAt(next) == ']')) {
                    i++;
                    continue;
                }
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int skipString(String s, int start, char quote) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == quote) return i + 1;
            i++;
        }
        return i;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    private static boolean isKeyPosition(StringBuilder output) {
        for (int j = output.length() - 1; j >= 0; j--) {
            char p = output.charAt(j);
            if (p == ' ' || p == '\t' || p == '\n' || p == '\r') continue;
            return p == '{' || p == ',';
        }
        return true;
    }

    private static List<WorkflowMeta.Phase> parsePhases(JsonNode root) {
        if (!root.has("phases") || !root.get("phases").isArray()) {
            return List.of();
        }
        List<WorkflowMeta.Phase> phases = new ArrayList<>();
        for (JsonNode node : root.get("phases")) {
            String title = node.has("title") ? node.get("title").asText() : "Untitled";
            String detail = node.has("detail") ? node.get("detail").asText() : null;
            String model = node.has("model") ? node.get("model").asText() : null;
            phases.add(new WorkflowMeta.Phase(title, detail, model));
        }
        return phases;
    }
}
