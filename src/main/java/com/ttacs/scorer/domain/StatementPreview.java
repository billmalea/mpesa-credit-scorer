package com.ttacs.scorer.domain;

import java.time.LocalDate;

public record StatementPreview(
        String customerName,
        String msisdn,
        String email,
        LocalDate periodStart,
        LocalDate periodEnd,
        int transactionCount,
        StatementFormat format
) {
}
