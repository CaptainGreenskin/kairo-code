package io.kairo.code.server.dto;

import java.util.List;

public record SearchResponse(String query, List<SearchMatch> matches, boolean truncated) {}
