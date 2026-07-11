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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses text extracted from official Safaricom M-Pesa PDF statements.
 */
public final class OfficialPdfTableParser {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter PDF_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern RECEIPT_LINE = Pattern.compile(
            "^([A-Z0-9]{10})\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2})\\s+(.*)$"
    );
    private static final Pattern COMPLETED_LINE = Pattern.compile(
            "^Completed\\s+(-?[\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})$"
    );
    private static final Pattern INLINE_COMPLETED = Pattern.compile(
            "Completed\\s+(-?[\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})$"
    );
    private static final Pattern MOBILE_NUMBER = Pattern.compile(
            "Mobile Number:\\s*(\\d+)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern STATEMENT_PERIOD = Pattern.compile(
            "Statement Period:\\s*([0-9]{2}\\s+[A-Za-z]{3}\\s+[0-9]{4})\\s*-\\s*([0-9]{2}\\s+[A-Za-z]{3}\\s+[0-9]{4})",
            Pattern.CASE_INSENSITIVE
    );
    private static final DateTimeFormatter PERIOD_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    public boolean supports(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            return false;
        }
        return extractedText.contains("M-PESA STATEMENT")
                && extractedText.contains("Receipt No.")
                && extractedText.contains("Completion Time");
    }

    public ParsedStatement parse(String extractedText, String msisdnHint) {
        List<MpesaTransaction> transactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        StatementIdentityExtractor.Identity identity = StatementIdentityExtractor.extract(extractedText).orElse(null);
        String msisdn = identity != null && identity.msisdn() != null
                ? identity.msisdn()
                : extractMsisdn(extractedText).orElse(msisdnHint);
        String customerName = identity != null ? identity.customerName() : null;
        String email = identity != null ? identity.email() : null;
        LocalDate periodStart = null;
        LocalDate periodEnd = null;

        Matcher periodMatcher = STATEMENT_PERIOD.matcher(extractedText);
        if (periodMatcher.find()) {
            periodStart = LocalDate.parse(periodMatcher.group(1), PERIOD_DATE);
            periodEnd = LocalDate.parse(periodMatcher.group(2), PERIOD_DATE);
        }

        PendingReceipt pending = null;

        for (String rawLine : extractedText.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher completed = COMPLETED_LINE.matcher(line);
            if (completed.matches()) {
                if (pending == null) {
                    warnings.add("Completed row without receipt context: " + line);
                    continue;
                }
                addTransaction(transactions, pending, completed.group(1), completed.group(2),
                        pending.details.toString().trim(), line);
                pending = null;
                continue;
            }

            Matcher receipt = RECEIPT_LINE.matcher(line);
            if (receipt.matches()) {
                if (pending != null) {
                    warnings.add("Receipt started before previous row completed: " + pending.receiptNo);
                }

                pending = new PendingReceipt(
                        receipt.group(1),
                        LocalDateTime.parse(receipt.group(2) + " " + receipt.group(3), PDF_TIME)
                );

                String details = receipt.group(4).trim();
                Matcher inline = INLINE_COMPLETED.matcher(details);
                if (inline.find()) {
                    String description = details.substring(0, inline.start()).trim();
                    addTransaction(transactions, pending, inline.group(1), inline.group(2), description, line);
                    pending = null;
                } else {
                    pending.details.append(details);
                }
                continue;
            }

            if (pending != null) {
                Matcher inline = INLINE_COMPLETED.matcher(line);
                if (inline.find()) {
                    if (!pending.details.isEmpty()) {
                        pending.details.append(' ');
                    }
                    pending.details.append(line, 0, inline.start());
                    String description = pending.details.toString().trim();
                    addTransaction(transactions, pending, inline.group(1), inline.group(2), description, line);
                    pending = null;
                } else {
                    if (!pending.details.isEmpty()) {
                        pending.details.append(' ');
                    }
                    pending.details.append(line);
                }
            }
        }

        if (pending != null) {
            warnings.add("Unclosed receipt row: " + pending.receiptNo);
        }

        transactions.sort(Comparator.comparing(MpesaTransaction::transactionInstant));

        if (periodStart == null && !transactions.isEmpty()) {
            periodStart = transactions.getFirst().transactionDate();
            periodEnd = transactions.getLast().transactionDate();
        }

        return new ParsedStatement(
                StatementFormat.MPESA_PDF, customerName, msisdn, email, periodStart, periodEnd, transactions, warnings);
    }

    private void addTransaction(
            List<MpesaTransaction> transactions,
            PendingReceipt pending,
            String signedAmount,
            String balance,
            String description,
            String rawLine) {
        BigDecimal amount = parseAmount(signedAmount);
        TransactionDirection direction = amount.signum() < 0
                ? TransactionDirection.DEBIT
                : TransactionDirection.CREDIT;

        transactions.add(new MpesaTransaction(
                pending.dateTime.toLocalDate(),
                pending.dateTime.atZone(NAIROBI).toInstant(),
                direction,
                amount.abs(),
                description,
                pending.receiptNo + " | balance " + balance + " | " + rawLine
        ));
    }

    private Optional<String> extractMsisdn(String text) {
        Matcher matcher = MOBILE_NUMBER.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String raw = matcher.group(1);
        if (raw.startsWith("0")) {
            return Optional.of("254" + raw.substring(1));
        }
        return Optional.of(raw);
    }

    private BigDecimal parseAmount(String token) {
        return new BigDecimal(token.replace(",", ""));
    }

    private static final class PendingReceipt {
        private final String receiptNo;
        private final LocalDateTime dateTime;
        private final StringBuilder details = new StringBuilder();

        private PendingReceipt(String receiptNo, LocalDateTime dateTime) {
            this.receiptNo = receiptNo;
            this.dateTime = dateTime;
        }
    }
}
