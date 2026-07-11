package com.ttacs.scorer.domain;

public record EvaluateRequest(
        String applicationId,
        String applicantName,
        String msisdn,
        int requestedAmountKes,
        int projectedMonthlyRepaymentKes,
        int activeLoanCount,
        byte[] fileBytes,
        String textContent,
        String password
) {
    public static EvaluateRequest fromFile(
            String applicationId,
            String applicantName,
            String msisdn,
            int requestedAmountKes,
            int projectedMonthlyRepaymentKes,
            int activeLoanCount,
            byte[] fileBytes,
            String password) {
        return new EvaluateRequest(
                applicationId,
                applicantName,
                msisdn,
                requestedAmountKes,
                projectedMonthlyRepaymentKes,
                activeLoanCount,
                fileBytes,
                null,
                password
        );
    }

    public EvaluateRequest withApplicant(String applicantName, String msisdn) {
        return new EvaluateRequest(
                applicationId,
                applicantName,
                msisdn,
                requestedAmountKes,
                projectedMonthlyRepaymentKes,
                activeLoanCount,
                fileBytes,
                textContent,
                password
        );
    }

    public static EvaluateRequest fromText(
            String applicationId,
            String applicantName,
            String msisdn,
            int requestedAmountKes,
            int projectedMonthlyRepaymentKes,
            int activeLoanCount,
            String textContent) {
        return new EvaluateRequest(
                applicationId,
                applicantName,
                msisdn,
                requestedAmountKes,
                projectedMonthlyRepaymentKes,
                activeLoanCount,
                null,
                textContent,
                null
        );
    }
}
