package com.ttacs.scorer.flexvertex;

import com.ttacs.scorer.policy.PolicyFile;
import com.ttacs.scorer.policy.PolicyMaterializer;

import java.nio.file.Path;

public final class DecisionStoreFactory {

    private DecisionStoreFactory() {
    }

    public static DecisionStore create(
            PolicyFile policy,
            Path policyFilePath,
            PolicyMaterializer.MaterializedPolicy materialized) {
        if (!policy.flexvertex.enabled) {
            return new NoOpDecisionStore();
        }

        try {
            Class<?> storeClass = Class.forName("com.ttacs.scorer.flexvertex.FlexVertexDecisionStore");
            Object store = storeClass
                    .getConstructor(PolicyFile.FlexVertexSection.class, String.class)
                    .newInstance(policy.flexvertex, policy.product.name);
            DecisionStore decisionStore = (DecisionStore) store;
            decisionStore.bootstrap();
            decisionStore.materializePolicy(materialized, policyFilePath);
            return decisionStore;
        } catch (Throwable ex) {
            System.err.println("FlexVertex bootstrap failed, using in-memory mode: " + ex.getMessage());
            return new NoOpDecisionStore();
        }
    }
}
