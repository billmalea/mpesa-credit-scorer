package com.ttacs.scorer.domain;

import java.math.BigDecimal;

public record StatementFeatures(
        int tenureMonths,
        BigDecimal monthlyVerifiedInflowKes,
        BigDecimal monthlyNetSurplusKes,
        BigDecimal avgMonthlyCreditsKes,
        BigDecimal avgMonthlyDebitsKes,
        BigDecimal inflowVolatilityCv,
        int creditCount,
        int debitCount,
        int counterpartyCount,
        boolean salaryPattern,
        boolean businessInflowPattern,
        boolean roundTripping
) {
}
