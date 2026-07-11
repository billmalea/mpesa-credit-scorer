package com.ttacs.scorer.score;

import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.domain.RuleFinding;
import com.ttacs.scorer.domain.RuleSeverity;
import com.ttacs.scorer.domain.StatementFeatures;
import com.ttacs.scorer.domain.Verdict;
import com.ttacs.scorer.policy.PolicyFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class RuleEvaluator {

    public List<RuleFinding> evaluate(PolicyFile policy, EvaluateRequest request, StatementFeatures features) {
        List<RuleFinding> findings = new ArrayList<>();
        findings.add(minNetInflow(policy, features));
        findings.add(accountTenure(policy, features));
        findings.add(repaymentCapacity(policy, request, features));
        findings.add(activeLoans(policy, request));
        findings.add(transactionDepth(policy, features));
        findings.add(inflowVolatility(policy, features));
        findings.add(fraudRoundTripping(features));
        return findings;
    }

    public Verdict aggregate(List<RuleFinding> findings) {
        boolean hardFail = findings.stream().anyMatch(f -> !f.passed() && f.severity() == RuleSeverity.HARD_FAIL);
        if (hardFail) {
            return Verdict.DECLINED;
        }
        boolean softRefer = findings.stream().anyMatch(f -> !f.passed() && f.severity() == RuleSeverity.SOFT_REFER);
        if (softRefer) {
            return Verdict.REFERRED;
        }
        return Verdict.APPROVED;
    }

    private RuleFinding minNetInflow(PolicyFile policy, StatementFeatures features) {
        int threshold = policy.eligibility.minMonthlyNetInflowKes;
        int actual = features.monthlyVerifiedInflowKes().intValue();
        boolean passed = actual >= threshold;
        return new RuleFinding(
                "MIN_NET_INFLOW",
                "Minimum verified monthly cash inflow",
                RuleSeverity.HARD_FAIL,
                passed,
                "KES " + actual,
                ">= KES " + threshold,
                passed ? "Verified inflow meets policy minimum" : "Verified inflow below policy minimum"
        );
    }

    private RuleFinding accountTenure(PolicyFile policy, StatementFeatures features) {
        int threshold = policy.eligibility.minAccountTenureMonths;
        int actual = features.tenureMonths();
        boolean passed = actual >= threshold;
        return new RuleFinding(
                "ACCOUNT_TENURE",
                "Minimum account tenure",
                RuleSeverity.HARD_FAIL,
                passed,
                actual + " months",
                ">= " + threshold + " months",
                passed ? "Statement history covers required tenure" : "Statement history too short"
        );
    }

    private RuleFinding repaymentCapacity(PolicyFile policy, EvaluateRequest request, StatementFeatures features) {
        BigDecimal inflow = features.monthlyVerifiedInflowKes();
        if (inflow.compareTo(BigDecimal.ZERO) <= 0) {
            return new RuleFinding(
                    "REPAYMENT_CAPACITY",
                    "Repayment-to-inflow capacity",
                    RuleSeverity.HARD_FAIL,
                    false,
                    "KES 0",
                    "<= " + (int) (policy.eligibility.maxRepaymentToInflowRatio * 100) + "% of inflow",
                    "No verified inflow for repayment capacity check"
            );
        }

        int projected = request.projectedMonthlyRepaymentKes();
        // When the applicant does not declare a repayment, size against policy capacity
        // (statement-derived offer will fit within this envelope).
        if (projected <= 0) {
            int capacity = (int) Math.floor(inflow.doubleValue() * policy.eligibility.maxRepaymentToInflowRatio);
            return new RuleFinding(
                    "REPAYMENT_CAPACITY",
                    "Repayment-to-inflow capacity",
                    RuleSeverity.HARD_FAIL,
                    capacity > 0,
                    "KES " + capacity + " capacity",
                    "<= " + (int) (policy.eligibility.maxRepaymentToInflowRatio * 100) + "% of inflow",
                    capacity > 0
                            ? "Affordability sized from statement capacity (no declared repayment)"
                            : "No repayment capacity from verified inflow"
            );
        }

        BigDecimal ratio = BigDecimal.valueOf(projected).divide(inflow, 4, RoundingMode.HALF_UP);
        boolean passed = ratio.doubleValue() <= policy.eligibility.maxRepaymentToInflowRatio;
        return new RuleFinding(
                "REPAYMENT_CAPACITY",
                "Repayment-to-inflow capacity",
                RuleSeverity.HARD_FAIL,
                passed,
                String.format("%.1f%%", ratio.doubleValue() * 100),
                String.format("<= %.0f%%", policy.eligibility.maxRepaymentToInflowRatio * 100),
                passed ? "Projected repayment is affordable" : "Projected repayment exceeds affordable range"
        );
    }

    private RuleFinding activeLoans(PolicyFile policy, EvaluateRequest request) {
        int threshold = policy.eligibility.maxActiveLoans;
        int actual = request.activeLoanCount();
        boolean passed = actual <= threshold;
        return new RuleFinding(
                "ACTIVE_LOANS",
                "Active loan stacking",
                RuleSeverity.SOFT_REFER,
                passed,
                String.valueOf(actual),
                "<= " + threshold,
                passed ? "Active loan count within policy" : "Applicant may be over-leveraged"
        );
    }

    private RuleFinding transactionDepth(PolicyFile policy, StatementFeatures features) {
        int threshold = policy.eligibility.minTransactionCount;
        int actual = features.creditCount() + features.debitCount();
        boolean passed = actual >= threshold;
        return new RuleFinding(
                "TRANSACTION_DEPTH",
                "Minimum transaction depth",
                RuleSeverity.SOFT_REFER,
                passed,
                String.valueOf(actual),
                ">= " + threshold,
                passed ? "Enough transaction history for assessment" : "Limited transaction history"
        );
    }

    private RuleFinding inflowVolatility(PolicyFile policy, StatementFeatures features) {
        double threshold = policy.eligibility.maxInflowVolatilityCv;
        double actual = features.inflowVolatilityCv().doubleValue();
        boolean stableIncome = features.salaryPattern() || features.businessInflowPattern();
        boolean passed = actual <= threshold || stableIncome;
        return new RuleFinding(
                "INFLOW_VOLATILITY",
                "Inflow stability",
                RuleSeverity.SOFT_REFER,
                passed,
                String.format("%.2f", actual),
                "<= " + String.format("%.2f", threshold),
                passed
                        ? (stableIncome && actual > threshold
                            ? "Variable months but recurring verified income source"
                            : "Inflow pattern is relatively stable")
                        : "Inflow pattern is volatile"
        );
    }

    private RuleFinding fraudRoundTripping(StatementFeatures features) {
        boolean passed = !features.roundTripping();
        return new RuleFinding(
                "FRAUD_ROUND_TRIP",
                "Round-tripping detection",
                RuleSeverity.HARD_FAIL,
                passed,
                features.roundTripping() ? "suspected" : "none",
                "none",
                passed ? "No round-tripping pattern detected" : "Same-counterparty credit/debit reversals detected"
        );
    }
}
