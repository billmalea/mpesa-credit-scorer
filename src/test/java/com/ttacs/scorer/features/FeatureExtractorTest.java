package com.ttacs.scorer.features;

import com.ttacs.scorer.domain.MpesaTransaction;
import com.ttacs.scorer.domain.ParsedStatement;
import com.ttacs.scorer.domain.StatementFormat;
import com.ttacs.scorer.domain.StatementFeatures;
import com.ttacs.scorer.domain.TransactionDirection;
import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.ingest.StatementIngestion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureExtractorTest {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final StatementIngestion ingestion = new StatementIngestion();
    private final FeatureExtractor extractor = new FeatureExtractor();

    @Test
    void doesNotFlagRoundTrippingOnRealHighVolumeStatement() throws Exception {
        Path pdf = Path.of("samples/MPESA_Statement_2026-07-06_to_2026-01-06_2547xxxxxx223.pdf");
        Assumptions.assumeTrue(Files.exists(pdf), "Private regression PDF not present (gitignored)");
        String password = System.getenv().getOrDefault("MPESA_PDF_PASSWORD", "");
        Assumptions.assumeFalse(password.isBlank(), "Set MPESA_PDF_PASSWORD to run private PDF regression");

        byte[] bytes = Files.readAllBytes(pdf);
        ParsedStatement statement = ingestion.ingest(EvaluateRequest.fromFile(
                "APP-TEST",
                "",
                "",
                75_000,
                12_500,
                0,
                bytes,
                password
        ));

        StatementFeatures features = extractor.extract(statement);

        assertFalse(features.roundTripping());
        assertTrue(features.monthlyVerifiedInflowKes().intValue() >= 50_000);
    }

    @Test
    void flagsDeliberateSameCounterpartyRoundTripping() {
        Instant firstCredit = LocalDate.of(2026, 1, 2).atTime(10, 0).atZone(NAIROBI).toInstant();
        Instant firstDebit = LocalDate.of(2026, 1, 2).atTime(18, 0).atZone(NAIROBI).toInstant();
        Instant secondCredit = LocalDate.of(2026, 2, 10).atTime(13, 0).atZone(NAIROBI).toInstant();
        Instant secondDebit = LocalDate.of(2026, 2, 10).atTime(19, 0).atZone(NAIROBI).toInstant();
        String counterparty = "FRIEND JOHN 254712345678";

        ParsedStatement statement = new ParsedStatement(
                StatementFormat.MPESA_SMS,
                null,
                "254712345678",
                null,
                LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 2, 10),
                List.of(
                        txn(LocalDate.of(2026, 1, 2), firstCredit, TransactionDirection.CREDIT, 18_000, counterparty),
                        txn(LocalDate.of(2026, 1, 2), firstDebit, TransactionDirection.DEBIT, 18_000, counterparty),
                        txn(LocalDate.of(2026, 2, 10), secondCredit, TransactionDirection.CREDIT, 22_000, counterparty),
                        txn(LocalDate.of(2026, 2, 10), secondDebit, TransactionDirection.DEBIT, 22_000, counterparty)
                ),
                List.of()
        );

        StatementFeatures features = extractor.extract(statement);

        assertTrue(features.roundTripping());
    }

    private MpesaTransaction txn(
            LocalDate date,
            Instant instant,
            TransactionDirection direction,
            int amount,
            String counterparty) {
        return new MpesaTransaction(
                date,
                instant,
                direction,
                BigDecimal.valueOf(amount),
                counterparty,
                "test"
        );
    }
}
