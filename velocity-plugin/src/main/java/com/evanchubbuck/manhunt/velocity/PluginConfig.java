package com.evanchubbuck.manhunt.velocity;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

public record PluginConfig(Map<String, Backend> backendServers, int staleSeconds, int pollSeconds) {
    public record Backend(String serverId, String statusFile) {
        public Backend {
            if (serverId == null || !serverId.matches("[a-z][a-z0-9-]{0,63}")
                    || statusFile == null || statusFile.isBlank()) {
                throw new IllegalArgumentException("Each backend requires serverId and statusFile");
            }
        }
        public Path statusPath() { return Path.of(statusFile).toAbsolutePath(); }
        public Path controlFile(String name) { return statusPath().getParent().resolve(name); }
    }

    public PluginConfig {
        if (backendServers == null || backendServers.size() < 2) {
            throw new IllegalArgumentException("Manhunt needs at least two backends");
        }
        backendServers = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(backendServers));
        var ids = new HashSet<String>();
        var paths = new HashSet<Path>();
        for (var entry : backendServers.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                    || !ids.add(entry.getValue().serverId()) || !paths.add(entry.getValue().statusPath())) {
                throw new IllegalArgumentException("Backend names, IDs, and status files must be unique");
            }
        }
        if (staleSeconds == 0) staleSeconds = 5;
        if (pollSeconds == 0) pollSeconds = 1;
        if (pollSeconds < 1 || staleSeconds < pollSeconds || staleSeconds > 60) {
            throw new IllegalArgumentException("Invalid pollSeconds/staleSeconds");
        }
    }

    public static PluginConfig load(Path path) throws IOException {
        try {
            PluginConfig config = new Gson().fromJson(Files.readString(path), PluginConfig.class);
            if (config == null) throw new IllegalArgumentException("Missing plugin config");
            return config;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Manhunt plugin configuration: " + path, exception);
        }
    }
}
