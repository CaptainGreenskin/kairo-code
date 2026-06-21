package io.kairo.code.server.dto;

import java.util.List;

public record SearchMatch(String file, int line, String preview,
                           List<String> beforeContext, List<String> afterContext) {

    public SearchMatch(String file, int line, String preview) {
        this(file, line, preview, List.of(), List.of());
    }
}
