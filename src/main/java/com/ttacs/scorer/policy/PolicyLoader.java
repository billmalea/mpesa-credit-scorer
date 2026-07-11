package com.ttacs.scorer.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PolicyLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private PolicyLoader() {
    }

    public static PolicyFile load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Policy file not found: " + path.toAbsolutePath());
        }
        return YAML.readValue(Files.readString(path), PolicyFile.class);
    }

    public static PolicyFile loadClasspath() throws IOException {
        try (InputStream in = PolicyLoader.class.getClassLoader().getResourceAsStream("policy.yml")) {
            if (in == null) {
                throw new IOException("policy.yml not found on classpath");
            }
            return YAML.readValue(in, PolicyFile.class);
        }
    }
}
