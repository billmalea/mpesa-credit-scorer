package com.ttacs.scorer.ingest;

import com.ttacs.scorer.domain.MpesaTransaction;
import com.ttacs.scorer.domain.ParsedStatement;
import com.ttacs.scorer.domain.StatementFormat;
import com.ttacs.scorer.domain.TransactionDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CsvStatementParser implements StatementParser {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public ParsedStatement parse(String text, String msisdnHint) {
        List<MpesaTransaction> transactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String[] lines = text.split("\\R");
        int headerIndex = -1;

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains("completion time")) {
                headerIndex = i;
                break;
            }
        }

        if (headerIndex < 0) {
            warnings.add("CSV header row not found");
            return new ParsedStatement(StatementFormat.MPESA_CSV, null, msisdnHint, null, null, null, transactions, warnings);
        }

        for (int i = headerIndex + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 6) {
                warnings.add("Skipping malformed CSV row: " + line);
                continue;
            }

            try {
                LocalDateTime dateTime = LocalDateTime.parse(parts[1].trim(), CSV_TIME);
                String details = parts[2].trim().toLowerCase();
                TransactionDirection direction;
                if (details.contains("received")
                        || details.contains("deposit")
                        || details.contains("transfer of funds from")) {
                    direction = TransactionDirection.CREDIT;
                } else {
                    direction = TransactionDirection.DEBIT;
                }

                BigDecimal paidIn = parseDecimal(parts[4]);
                BigDecimal withdrawn = parseDecimal(parts[5]);
                BigDecimal amount = direction == TransactionDirection.CREDIT ? paidIn : withdrawn;

                transactions.add(new MpesaTransaction(
                        dateTime.toLocalDate(),
                        dateTime.atZone(NAIROBI).toInstant(),
                        direction,
                        amount,
                        parts[2].trim(),
                        line
                ));
            } catch (RuntimeException ex) {
                warnings.add("Failed to parse CSV row: " + line);
            }
        }

        transactions.sort(Comparator.comparing(MpesaTransaction::transactionInstant));
        LocalDate start = transactions.stream().map(MpesaTransaction::transactionDate).min(LocalDate::compareTo).orElse(null);
        LocalDate end = transactions.stream().map(MpesaTransaction::transactionDate).max(LocalDate::compareTo).orElse(null);

        return new ParsedStatement(StatementFormat.MPESA_CSV, null, msisdnHint, null, start, end, transactions, warnings);
    }

    private BigDecimal parseDecimal(String token) {
        if (token == null || token.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(token.replace(",", "").trim());
    }
}
