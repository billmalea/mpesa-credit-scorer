package com.ttacs.scorer.domain;

import java.time.LocalDate;
import java.util.List;

public record ParsedStatement(
        StatementFormat format,
        String customerName,
        String msisdn,
        String email,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<MpesaTransaction> transactions,
        List<String> warnings
) {
}
