package com.ttacs.scorer.ingest;

import com.ttacs.scorer.domain.ParsedStatement;
import com.ttacs.scorer.domain.TransactionDirection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvStatementParserTest {

    @Test
    void parsesOfficialStyleCsv() throws Exception {
        String text = Files.readString(Path.of("samples/amina-strong-inflow.csv"));
        ParsedStatement statement = new CsvStatementParser().parse(text, "254700000001");

        assertFalse(statement.transactions().isEmpty());
        assertTrue(statement.transactions().stream().anyMatch(t -> t.direction() == TransactionDirection.CREDIT));
        assertTrue(statement.periodStart() != null);
        assertTrue(statement.periodEnd() != null);
        assertTrue(statement.transactions().stream()
                .filter(t -> t.direction() == TransactionDirection.CREDIT)
                .allMatch(t -> t.counterparty().toLowerCase().contains("payroll")));
    }
}
