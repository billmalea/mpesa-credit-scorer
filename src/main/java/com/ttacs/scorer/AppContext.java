package com.ttacs.scorer;

import com.ttacs.scorer.flexvertex.DecisionStore;
import com.ttacs.scorer.flexvertex.DecisionStoreFactory;
import com.ttacs.scorer.policy.PolicyFile;
import com.ttacs.scorer.policy.PolicyLoader;
import com.ttacs.scorer.policy.PolicyMaterializer;

import java.io.IOException;
import java.nio.file.Path;

public final class AppContext {

    private final CreditScoringService scoringService;
    private final DecisionStore decisionStore;
    private final PolicyFile policy;
    private final PolicyMaterializer.MaterializedPolicy materializedPolicy;
    private final int serverPort;

    private AppContext(
            CreditScoringService scoringService,
            DecisionStore decisionStore,
            PolicyFile policy,
            PolicyMaterializer.MaterializedPolicy materializedPolicy,
            int serverPort) {
        this.scoringService = scoringService;
        this.decisionStore = decisionStore;
        this.policy = policy;
        this.materializedPolicy = materializedPolicy;
        this.serverPort = serverPort;
    }

    public static AppContext create(Path policyPath) throws IOException {
        PolicyFile loaded = PolicyLoader.load(policyPath);
        PolicyMaterializer.MaterializedPolicy materialized = PolicyMaterializer.materialize(loaded, policyPath);

        DecisionStore store = DecisionStoreFactory.create(loaded, policyPath, materialized);

        CreditScoringService service = new CreditScoringService(materialized, store);
        return new AppContext(service, store, loaded, materialized, loaded.server.port);
    }

    public CreditScoringService scoringService() {
        return scoringService;
    }

    public DecisionStore decisionStore() {
        return decisionStore;
    }

    public PolicyFile policy() {
        return policy;
    }

    public PolicyMaterializer.MaterializedPolicy materializedPolicy() {
        return materializedPolicy;
    }

    public int serverPort() {
        return serverPort;
    }
}
