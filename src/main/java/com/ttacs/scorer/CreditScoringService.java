package com.ttacs.scorer;

import com.ttacs.scorer.domain.CreditDecision;
import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.domain.ParsedStatement;
import com.ttacs.scorer.domain.ReconstructResult;
import com.ttacs.scorer.domain.RuleFinding;
import com.ttacs.scorer.domain.StatementEvidence;
import com.ttacs.scorer.domain.StatementPreview;
import com.ttacs.scorer.domain.StatementFeatures;
import com.ttacs.scorer.domain.Verdict;
import com.ttacs.scorer.features.FeatureExtractor;
import com.ttacs.scorer.flexvertex.DecisionStore;
import com.ttacs.scorer.ingest.StatementIngestion;
import com.ttacs.scorer.policy.PolicyFile;
import com.ttacs.scorer.policy.PolicyMaterializer;
import com.ttacs.scorer.score.RuleEvaluator;
import com.ttacs.scorer.score.Scorecard;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class CreditScoringService {

    private final PolicyMaterializer.MaterializedPolicy materializedPolicy;
    private final StatementIngestion ingestion = new StatementIngestion();
    private final FeatureExtractor featureExtractor = new FeatureExtractor();
    private final RuleEvaluator ruleEvaluator = new RuleEvaluator();
    private final Scorecard scorecard = new Scorecard();
    private final DecisionStore decisionStore;

    public CreditScoringService(PolicyMaterializer.MaterializedPolicy materializedPolicy, DecisionStore decisionStore) {
        this.materializedPolicy = materializedPolicy;
        this.decisionStore = decisionStore;
    }

    public PolicyFile policy() {
        return materializedPolicy.policy();
    }

    public PolicyMaterializer.MaterializedPolicy materializedPolicy() {
        return materializedPolicy;
    }

    public CreditDecision evaluate(EvaluateRequest request) throws IOException {
        PolicyFile policy = materializedPolicy.policy();
        ParsedStatement statement = ingestion.ingest(request);

        String applicantName = resolveApplicantName(request, statement);
        String msisdn = resolveMsisdn(request, statement);
        EvaluateRequest resolved = request.withApplicant(applicantName, msisdn);

        List<String> warnings = new ArrayList<>(statement.warnings());
        StatementFeatures features = featureExtractor.extract(statement);
        List<RuleFinding> findings = ruleEvaluator.evaluate(policy, resolved, features);
        Verdict verdict = ruleEvaluator.aggregate(findings);

        int creditScore = scorecard.computeScore(policy, features, verdict);
        int repaymentCapacity = scorecard.monthlyRepaymentCapacity(policy, features);
        int maxLoan = scorecard.maxLoan(policy, features, creditScore, resolved.requestedAmountKes(), verdict);
        boolean eligible = verdict != Verdict.DECLINED;

        String reason = buildReason(applicantName, verdict, findings, maxLoan);

        CreditDecision decision = new CreditDecision(
                resolved.applicationId(),
                applicantName,
                msisdn,
                verdict,
                eligible,
                creditScore,
                maxLoan,
                repaymentCapacity,
                resolved.requestedAmountKes(),
                reason,
                features,
                findings,
                warnings,
                Instant.now()
        );

        decisionStore.persist(resolved, buildEvidence(statement, request), features, decision, materializedPolicy);
        return decision;
    }

    public Optional<ReconstructResult> reconstruct(String applicationId) {
        return decisionStore.reconstruct(applicationId);
    }

    private StatementEvidence buildEvidence(ParsedStatement statement, EvaluateRequest request) {
        String hash = hashStatement(request.fileBytes());
        return new StatementEvidence(
                statement.format(),
                statement.transactions().size(),
                statement.periodStart(),
                statement.periodEnd(),
                hash
        );
    }

    private String hashStatement(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(fileBytes));
        } catch (NoSuchAlgorithmException ex) {
            return "";
        }
    }

    public StatementPreview preview(EvaluateRequest request) throws IOException {
        ParsedStatement statement = ingestion.ingest(request);
        return new StatementPreview(
                statement.customerName(),
                statement.msisdn(),
                statement.email(),
                statement.periodStart(),
                statement.periodEnd(),
                statement.transactions().size(),
                statement.format()
        );
    }

    private String resolveApplicantName(EvaluateRequest request, ParsedStatement statement) {
        if (isProvided(request.applicantName()) && !"Applicant".equalsIgnoreCase(request.applicantName())) {
            return request.applicantName().trim();
        }
        if (statement.customerName() != null && !statement.customerName().isBlank()) {
            return statement.customerName().trim();
        }
        return "Applicant";
    }

    private String resolveMsisdn(EvaluateRequest request, ParsedStatement statement) {
        if (isProvided(request.msisdn())) {
            return request.msisdn().trim();
        }
        if (statement.msisdn() != null && !statement.msisdn().isBlank()) {
            return statement.msisdn().trim();
        }
        return "";
    }

    private boolean isProvided(String value) {
        return value != null && !value.isBlank();
    }

    private String buildReason(String applicantName, Verdict verdict, List<RuleFinding> findings, int maxLoan) {
        String failed = findings.stream()
                .filter(f -> !f.passed())
                .map(RuleFinding::code)
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");

        return switch (verdict) {
            case APPROVED -> applicantName + " approved. Recommended max loan KES " + maxLoan + ".";
            case REFERRED -> applicantName + " referred for manual review. Flags: " + failed + ".";
            case DECLINED -> applicantName + " declined. Failed rules: " + failed + ".";
        };
    }
}
