package com.ttacs.scorer.score;

import com.ttacs.scorer.domain.StatementFeatures;
import com.ttacs.scorer.domain.Verdict;
import com.ttacs.scorer.policy.PolicyFile;

public final class Scorecard {

    /** Preferred repayment tenures (months), shortest first. */
    private static final int[] TENURE_MONTHS = {1, 2, 3, 6, 9, 12};

    public int computeScore(PolicyFile policy, StatementFeatures features, Verdict verdict) {
        PolicyFile.ScoringSection.WeightSection weights = policy.scoring.weights;
        int score = 0;

        score += tenurePoints(weights.tenure, features.tenureMonths(), policy.eligibility.minAccountTenureMonths);
        score += inflowPoints(
                weights.inflow,
                features.monthlyVerifiedInflowKes().intValue(),
                policy.eligibility.minMonthlyNetInflowKes
        );
        score += stabilityPoints(weights.stability, features.inflowVolatilityCv().doubleValue(), policy.eligibility.maxInflowVolatilityCv);
        score += activityPoints(weights.activity, features.creditCount() + features.debitCount(), policy.eligibility.minTransactionCount);

        if (features.salaryPattern()) {
            score += weights.salaryPattern;
        }
        if (features.businessInflowPattern()) {
            score += Math.max(3, weights.salaryPattern / 2);
        }
        if (features.roundTripping()) {
            score -= policy.scoring.roundTrippingPenalty;
        }
        if (verdict == Verdict.DECLINED) {
            score = Math.min(score, 39);
        } else if (verdict == Verdict.REFERRED) {
            score = Math.min(score, 69);
        }

        return Math.max(0, Math.min(100, score));
    }

    public int monthlyRepaymentCapacity(PolicyFile policy, StatementFeatures features) {
        return (int) Math.floor(
                features.monthlyVerifiedInflowKes().doubleValue() * policy.eligibility.maxRepaymentToInflowRatio
        );
    }

    /**
     * Statement-derived maximum loan for accepted (non-declined) applicants.
     * Not capped by product catalog max or the applicant's requested amount.
     */
    public int maxLoan(PolicyFile policy, StatementFeatures features, int creditScore, Verdict verdict) {
        if (verdict == Verdict.DECLINED) {
            return 0;
        }
        double inflowCap = features.monthlyVerifiedInflowKes().doubleValue() * policy.product.loanToInflowRatio;
        double scoreFactor = creditScore / 100.0;
        int computed = (int) Math.floor(inflowCap * scoreFactor);
        return Math.max(0, computed);
    }

    /**
     * Shortest preferred tenure where loan / months stays within monthly capacity.
     * Falls back to 12 months if the loan still exceeds capacity at that horizon.
     */
    public int recommendTenureMonths(int loanKes, int monthlyCapacityKes) {
        if (loanKes <= 0) {
            return 0;
        }
        if (monthlyCapacityKes <= 0) {
            return TENURE_MONTHS[TENURE_MONTHS.length - 1];
        }
        for (int months : TENURE_MONTHS) {
            int installment = monthlyInstallment(loanKes, months);
            if (installment <= monthlyCapacityKes) {
                return months;
            }
        }
        return TENURE_MONTHS[TENURE_MONTHS.length - 1];
    }

    public int monthlyInstallment(int loanKes, int tenureMonths) {
        if (loanKes <= 0 || tenureMonths <= 0) {
            return 0;
        }
        return (int) Math.ceil(loanKes / (double) tenureMonths);
    }

    private int tenurePoints(int maxPoints, int actualMonths, int minMonths) {
        if (actualMonths <= 0 || minMonths <= 0) {
            return 0;
        }
        double ratio = Math.min(1.0, (double) actualMonths / (minMonths * 2.0));
        return (int) Math.round(maxPoints * ratio);
    }

    private int inflowPoints(int maxPoints, int actualInflow, int minInflow) {
        if (actualInflow <= 0 || minInflow <= 0) {
            return 0;
        }
        double ratio = Math.min(1.0, (double) actualInflow / (minInflow * 2.0));
        return (int) Math.round(maxPoints * ratio);
    }

    private int stabilityPoints(int maxPoints, double cv, double maxCv) {
        if (maxCv <= 0) {
            return maxPoints;
        }
        double ratio = Math.max(0.0, 1.0 - (cv / maxCv));
        return (int) Math.round(maxPoints * ratio);
    }

    private int activityPoints(int maxPoints, int txnCount, int minTxnCount) {
        if (txnCount <= 0 || minTxnCount <= 0) {
            return 0;
        }
        double ratio = Math.min(1.0, (double) txnCount / (minTxnCount * 2.0));
        return (int) Math.round(maxPoints * ratio);
    }
}
