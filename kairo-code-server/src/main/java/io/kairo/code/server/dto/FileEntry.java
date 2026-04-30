package io.kairo.code.server.dto;

public record FileEntry(String name, String path, boolean isDir, long size) {}
