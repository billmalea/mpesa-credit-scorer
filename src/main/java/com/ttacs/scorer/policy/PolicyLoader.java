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
        PolicyFile base = YAML.readValue(Files.readString(path), PolicyFile.class);
        Path local = path.toAbsolutePath().getParent() == null
                ? Path.of("policy.local.yml")
                : path.toAbsolutePath().getParent().resolve("policy.local.yml");
        if (Files.exists(local)) {
            PolicyFile overlay = YAML.readValue(Files.readString(local), PolicyFile.class);
            merge(base, overlay);
            System.out.println("Loaded local policy overlay: " + local.getFileName());
        }
        return base;
    }

    /** Overlay only non-null / meaningful FlexVertex + server demo secrets from policy.local.yml. */
    private static void merge(PolicyFile base, PolicyFile overlay) {
        if (overlay.flexvertex != null) {
            if (overlay.flexvertex.enabled) {
                base.flexvertex.enabled = true;
            }
            if (overlay.flexvertex.host != null && !overlay.flexvertex.host.isBlank()) {
                base.flexvertex.host = overlay.flexvertex.host;
            }
            if (overlay.flexvertex.port != 0) {
                base.flexvertex.port = overlay.flexvertex.port;
            }
            if (overlay.flexvertex.adminPath != null && !overlay.flexvertex.adminPath.isBlank()) {
                base.flexvertex.adminPath = overlay.flexvertex.adminPath;
            }
            if (overlay.flexvertex.adminPassword != null && !overlay.flexvertex.adminPassword.isBlank()
                    && !"REPLACE_ME".equals(overlay.flexvertex.adminPassword)) {
                base.flexvertex.adminPassword = overlay.flexvertex.adminPassword;
            }
            if (overlay.flexvertex.underwriterPassword != null && !overlay.flexvertex.underwriterPassword.isBlank()
                    && !"REPLACE_ME".equals(overlay.flexvertex.underwriterPassword)) {
                base.flexvertex.underwriterPassword = overlay.flexvertex.underwriterPassword;
            }
            if (overlay.flexvertex.domain != null && !overlay.flexvertex.domain.isBlank()) {
                base.flexvertex.domain = overlay.flexvertex.domain;
            }
            if (overlay.flexvertex.nexus != null && !overlay.flexvertex.nexus.isBlank()) {
                base.flexvertex.nexus = overlay.flexvertex.nexus;
            }
            if (overlay.flexvertex.schema != null && !overlay.flexvertex.schema.isBlank()) {
                base.flexvertex.schema = overlay.flexvertex.schema;
            }
        }
        if (overlay.server != null && overlay.server.port != 0) {
            base.server.port = overlay.server.port;
        }
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
