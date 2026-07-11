package com.ttacs.scorer;

import com.ttacs.scorer.domain.CreditDecision;
import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.domain.Verdict;
import com.ttacs.scorer.flexvertex.NoOpDecisionStore;
import com.ttacs.scorer.policy.PolicyLoader;
import com.ttacs.scorer.policy.PolicyMaterializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditScoringServiceTest {

    private CreditScoringService service() throws Exception {
        Path policyPath = Path.of("policy.yml").toAbsolutePath().normalize();
        var materialized = PolicyMaterializer.materialize(PolicyLoader.load(policyPath), policyPath);
        return new CreditScoringService(materialized, new NoOpDecisionStore());
    }

    private CreditDecision evaluateSample(String sample, int requested, int repayment, int activeLoans) throws Exception {
        byte[] statement = Files.readAllBytes(Path.of("samples/" + sample));
        return service().evaluate(EvaluateRequest.fromFile(
                "APP-" + sample,
                "Test Applicant",
                "254700000099",
                requested,
                repayment,
                activeLoans,
                statement,
                null
        ));
    }

    @Test
    void approvesStrongPayrollApplicant() throws Exception {
        CreditDecision decision = evaluateSample("amina-strong-inflow.csv", 1, 0, 0);

        assertEquals(Verdict.APPROVED, decision.verdict());
        assertTrue(decision.features().salaryPattern());
        assertTrue(decision.features().monthlyVerifiedInflowKes().intValue() >= 50_000);
        int expectedMaxLoan = (int) Math.floor(
                decision.features().monthlyVerifiedInflowKes().doubleValue()
                        * service().policy().product.loanToInflowRatio
                        * decision.creditScore() / 100.0
        );
        assertEquals(expectedMaxLoan, decision.maxLoanKes());
        assertTrue(decision.maxLoanKes() > decision.requestedAmountKes());
    }

    @Test
    void recommendedTenureKeepsInstallmentWithinStatementCapacity() throws Exception {
        CreditDecision decision = evaluateSample("amina-strong-inflow.csv", 0, 0, 0);

        assertEquals(Verdict.APPROVED, decision.verdict());
        assertTrue(decision.recommendedTenureMonths() > 0);
        assertTrue(decision.recommendedMonthlyRepaymentKes() <= decision.monthlyRepaymentCapacityKes());
    }

    @Test
    void declinesLowVerifiedIncomeApplicant() throws Exception {
        CreditDecision decision = evaluateSample("collins-weak-inflow.csv", 50_000, 10_000, 2);

        assertEquals(Verdict.DECLINED, decision.verdict());
        assertTrue(decision.creditScore() <= 39);
        assertEquals(0, decision.maxLoanKes());
    }

    @Test
    void approvesDiversifiedGigWorkerWithoutBusinessPattern() throws Exception {
        CreditDecision decision = evaluateSample("grace-gig-worker.csv", 60_000, 10_000, 0);

        assertEquals(Verdict.APPROVED, decision.verdict());
        assertFalse(decision.features().businessInflowPattern());
        assertFalse(decision.features().salaryPattern());
        assertTrue(decision.features().monthlyVerifiedInflowKes().intValue() >= 50_000);
    }

    @Test
    void refersVolatileIncomeWithoutRecurringSource() throws Exception {
        CreditDecision decision = evaluateSample("naomi-volatile.csv", 50_000, 8_000, 0);

        assertEquals(Verdict.REFERRED, decision.verdict());
        assertFalse(decision.features().businessInflowPattern());
        assertFalse(decision.features().salaryPattern());
        assertTrue(decision.findings().stream()
                .anyMatch(f -> "INFLOW_VOLATILITY".equals(f.code()) && !f.passed()));
    }

    @Test
    void declinesSameCounterpartyRoundTripping() throws Exception {
        CreditDecision decision = evaluateSample("sam-round-trip.csv", 80_000, 12_000, 0);

        assertEquals(Verdict.DECLINED, decision.verdict());
        assertTrue(decision.features().roundTripping());
        assertTrue(decision.findings().stream()
                .anyMatch(f -> "FRAUD_ROUND_TRIP".equals(f.code()) && !f.passed()));
    }

    @Test
    void regressionHighVolumeOfficialPdfStillApproves() throws Exception {
        Path pdf = Path.of("samples/MPESA_Statement_2026-07-06_to_2026-01-06_2547xxxxxx223.pdf");
        Assumptions.assumeTrue(Files.exists(pdf), "Private regression PDF not present (gitignored)");

        String password = System.getenv().getOrDefault("MPESA_PDF_PASSWORD", "");
        Assumptions.assumeFalse(password.isBlank(), "Set MPESA_PDF_PASSWORD to run private PDF regression");

        byte[] statement = Files.readAllBytes(pdf);
        CreditDecision decision = service().evaluate(EvaluateRequest.fromFile(
                "APP-REGRESSION-PDF",
                "",
                "",
                75_000,
                12_500,
                0,
                statement,
                password
        ));

        assertEquals(Verdict.APPROVED, decision.verdict());
        assertFalse(decision.features().roundTripping());
        assertTrue(decision.features().monthlyVerifiedInflowKes().intValue() >= 50_000);
    }
}
