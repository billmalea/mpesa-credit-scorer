package com.ttacs.scorer.flexvertex;

import com.ttacs.scorer.domain.CreditDecision;
import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.domain.PortfolioReport;
import com.ttacs.scorer.domain.ReconstructResult;
import com.ttacs.scorer.domain.StatementEvidence;
import com.ttacs.scorer.domain.StatementFeatures;
import com.ttacs.scorer.policy.PolicyMaterializer;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class NoOpDecisionStore implements DecisionStore {

    @Override
    public void bootstrap() {
    }

    @Override
    public void materializePolicy(PolicyMaterializer.MaterializedPolicy policy, Path policyFilePath) {
    }

    @Override
    public void persist(
            EvaluateRequest request,
            StatementEvidence evidence,
            StatementFeatures features,
            CreditDecision decision,
            PolicyMaterializer.MaterializedPolicy policy) {
    }

    @Override
    public Optional<ReconstructResult> reconstruct(String applicationId) {
        return Optional.empty();
    }

    @Override
    public PortfolioReport portfolioReport() {
        return new PortfolioReport(0, 0, 0, 0, Map.of());
    }
}
