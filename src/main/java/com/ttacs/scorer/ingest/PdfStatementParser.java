package com.ttacs.scorer.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

public final class PdfStatementParser {

    private final CsvStatementParser csvParser = new CsvStatementParser();
    private final SmsStatementParser smsParser = new SmsStatementParser();
    private final OfficialPdfTableParser officialPdfTableParser = new OfficialPdfTableParser();

    public String extractText(byte[] pdfBytes, String password) throws IOException {
        try (PDDocument document = open(pdfBytes, password)) {
            if (document.isEncrypted() && !document.getCurrentAccessPermission().canExtractContent()) {
                throw new StatementUnlockException("Password accepted but PDF content cannot be extracted");
            }
            return new PDFTextStripper().getText(document);
        } catch (StatementUnlockException ex) {
            throw ex;
        } catch (IOException ex) {
            if (isPasswordFailure(ex)) {
                throw new StatementUnlockException(
                        "M-Pesa statement PDF is password-protected. Provide --password or statementPassword.",
                        ex
                );
            }
            throw ex;
        }
    }

    public com.ttacs.scorer.domain.ParsedStatement parse(byte[] pdfBytes, String password, String msisdnHint) throws IOException {
        String text = extractText(pdfBytes, password);
        if (officialPdfTableParser.supports(text)) {
            return officialPdfTableParser.parse(text, msisdnHint);
        }
        if (text.toLowerCase().contains("completion time")) {
            return csvParser.parse(text, msisdnHint);
        }
        if (text.toLowerCase().contains("confirmed.")) {
            return smsParser.parse(text, msisdnHint);
        }
        return csvParser.parse(text, msisdnHint);
    }

    private PDDocument open(byte[] pdfBytes, String password) throws IOException {
        if (password == null || password.isBlank()) {
            try {
                return Loader.loadPDF(pdfBytes);
            } catch (IOException ex) {
                if (isPasswordFailure(ex)) {
                    throw new StatementUnlockException("PDF is password-protected", ex);
                }
                throw ex;
            }
        }
        return Loader.loadPDF(pdfBytes, password);
    }

    private boolean isPasswordFailure(IOException ex) {
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("password") || lower.contains("encrypted") || lower.contains("decrypt");
    }
}
