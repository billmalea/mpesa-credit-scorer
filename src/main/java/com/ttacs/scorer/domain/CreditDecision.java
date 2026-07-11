package com.ttacs.scorer.domain;

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
        int recommendedTenureMonths,
        int recommendedMonthlyRepaymentKes,
        int monthlyRepaymentCapacityKes,
        int requestedAmountKes,
        String reason,
        StatementFeatures features,
        List<RuleFinding> findings,
        List<String> warnings,
        Instant decidedAt
) {
}
