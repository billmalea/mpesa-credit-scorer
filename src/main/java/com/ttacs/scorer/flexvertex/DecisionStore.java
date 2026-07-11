package com.ttacs.scorer.flexvertex;

import com.ttacs.scorer.domain.CreditDecision;
import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.domain.PortfolioReport;
import com.ttacs.scorer.domain.ReconstructResult;
import com.ttacs.scorer.domain.StatementEvidence;
import com.ttacs.scorer.domain.StatementFeatures;
import com.ttacs.scorer.policy.PolicyMaterializer;

import java.nio.file.Path;
import java.util.Optional;

public interface DecisionStore {

    void bootstrap();

    void materializePolicy(PolicyMaterializer.MaterializedPolicy policy, Path policyFilePath);

    void persist(
            EvaluateRequest request,
            StatementEvidence evidence,
            StatementFeatures features,
            CreditDecision decision,
            PolicyMaterializer.MaterializedPolicy policy
    );

    Optional<ReconstructResult> reconstruct(String applicationId);

    PortfolioReport portfolioReport();
}
