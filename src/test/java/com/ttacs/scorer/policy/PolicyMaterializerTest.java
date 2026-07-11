package com.ttacs.scorer.policy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyMaterializerTest {

    @Test
    void chunksPolicyTextIntoParagraphBlocks() {
        String text = "Section A\n\nSection B with more detail\n\nSection C";
        List<String> chunks = PolicyMaterializer.chunkPolicyText(text);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("Section A")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.contains("Section C")));
    }

    @Test
    void buildsStablePolicyVersionFingerprint() throws Exception {
        Path policyPath = Path.of("policy.yml").toAbsolutePath().normalize();
        PolicyFile policy = PolicyLoader.load(policyPath);
        PolicyMaterializer.MaterializedPolicy materialized = PolicyMaterializer.materialize(policy, policyPath);

        String version = PolicyMaterializer.policyVersion(materialized);
        assertTrue(version.contains("inflow="));
        assertTrue(version.contains("tenure="));
    }
}
