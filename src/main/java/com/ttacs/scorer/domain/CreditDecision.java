package com.ttacs.scorer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CreditDecision(
        String applicationId,
        String applicantName,
        String msisdn,
        Verdict verdict,
        boolean eligible,
        int creditScore,
        int maxLoanKes,
        int monthlyRepaymentCapacityKes,
        int requestedAmountKes,
        String reason,
        StatementFeatures features,
        List<RuleFinding> findings,
        List<String> warnings,
        Instant decidedAt
) {
}
