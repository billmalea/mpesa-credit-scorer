package com.ttacs.scorer.ingest;

import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.domain.ParsedStatement;
import com.ttacs.scorer.domain.StatementFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StatementIngestion {

    private final CsvStatementParser csvParser = new CsvStatementParser();
    private final SmsStatementParser smsParser = new SmsStatementParser();
    private final PdfStatementParser pdfParser = new PdfStatementParser();

    public ParsedStatement ingest(EvaluateRequest request) throws IOException {
        if (request.fileBytes() != null && request.fileBytes().length > 0) {
            return ingestFile(request.fileBytes(), request.password(), request.msisdn());
        }
        if (request.textContent() != null && !request.textContent().isBlank()) {
            return ingestText(request.textContent(), request.msisdn());
        }
        throw new IllegalArgumentException("Statement file or text content is required");
    }

    public ParsedStatement ingestPath(Path path, String password, String msisdnHint) throws IOException {
        String name = path.getFileName().toString().toLowerCase();
        byte[] bytes = Files.readAllBytes(path);
        if (name.endsWith(".pdf")) {
            return pdfParser.parse(bytes, password, msisdnHint);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        return ingestText(text, msisdnHint);
    }

    private ParsedStatement ingestFile(byte[] bytes, String password, String msisdnHint) throws IOException {
        if (looksLikePdf(bytes)) {
            ParsedStatement parsed = pdfParser.parse(bytes, password, msisdnHint);
            return withFormat(parsed, StatementFormat.MPESA_PDF);
        }
        return ingestText(new String(bytes, StandardCharsets.UTF_8), msisdnHint);
    }

    private ParsedStatement ingestText(String text, String msisdnHint) {
        if (text.toLowerCase().contains("completion time")) {
            return csvParser.parse(text, msisdnHint);
        }
        if (text.toLowerCase().contains("confirmed.")) {
            return smsParser.parse(text, msisdnHint);
        }
        return csvParser.parse(text, msisdnHint);
    }

    private boolean looksLikePdf(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F';
    }

    private ParsedStatement withFormat(ParsedStatement parsed, StatementFormat format) {
        return new ParsedStatement(
                format,
                parsed.customerName(),
                parsed.msisdn(),
                parsed.email(),
                parsed.periodStart(),
                parsed.periodEnd(),
                parsed.transactions(),
                parsed.warnings()
        );
    }
}
