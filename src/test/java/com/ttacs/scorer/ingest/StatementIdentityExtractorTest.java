package com.ttacs.scorer.ingest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementIdentityExtractorTest {

    @Test
    void extractsIdentityFromStatementHeaderText() throws Exception {
        String text = Files.readString(Path.of("samples/fixtures/sample-mpesa-header.txt"));

        StatementIdentityExtractor.Identity identity = StatementIdentityExtractor.extract(text).orElseThrow();

        assertEquals("AMINA OTIENO DEMO", identity.customerName());
        assertEquals("254700000001", identity.msisdn());
        assertEquals("amina.demo@example.com", identity.email());
        assertTrue(identity.msisdn().startsWith("254"));
    }
}
