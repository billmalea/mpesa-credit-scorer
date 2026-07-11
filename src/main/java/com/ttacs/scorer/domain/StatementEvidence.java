package com.ttacs.scorer.domain;

import java.time.LocalDate;

public record StatementEvidence(
        StatementFormat format,
        int transactionCount,
        LocalDate periodStart,
        LocalDate periodEnd,
        String statementHash
) {
}
