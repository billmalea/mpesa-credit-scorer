package com.ttacs.scorer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MpesaTransaction(
        LocalDate transactionDate,
        Instant transactionInstant,
        TransactionDirection direction,
        BigDecimal amountKes,
        String counterparty,
        String rawLine
) {
}
