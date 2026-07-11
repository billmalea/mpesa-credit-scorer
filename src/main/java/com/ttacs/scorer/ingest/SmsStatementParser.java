package com.ttacs.scorer.ingest;

import com.ttacs.scorer.domain.MpesaTransaction;
import com.ttacs.scorer.domain.ParsedStatement;
import com.ttacs.scorer.domain.StatementFormat;
import com.ttacs.scorer.domain.TransactionDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SmsStatementParser implements StatementParser {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private static final Pattern RECEIVED = Pattern.compile(
            "confirmed\\.\\s*you have received ksh([0-9,]+(?:\\.[0-9]{1,2})?)\\s+from\\s+(.+?)\\s+(\\d{9,12})\\s+on\\s+(\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM))\\.",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern SENT = Pattern.compile(
            "confirmed\\.\\s*ksh([0-9,]+(?:\\.[0-9]{1,2})?)\\s+sent to\\s+(.+?)\\s+(\\d{9,12})\\s+on\\s+(\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM))\\.",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern SENT_MERCHANT = Pattern.compile(
            "confirmed\\.\\s*ksh([0-9,]+(?:\\.[0-9]{1,2})?)\\s+sent to\\s+(.+?)\\s+on\\s+(\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM))\\.",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern PAID = Pattern.compile(
            "confirmed\\.\\s*ksh([0-9,]+(?:\\.[0-9]{1,2})?)\\s+paid to\\s+(.+?)\\s+on\\s+(\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*(?:AM|PM))\\.",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    @Override
    public ParsedStatement parse(String text, String msisdnHint) {
        List<MpesaTransaction> transactions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            parseLine(trimmed).ifPresentOrElse(
                    transactions::add,
                    () -> warnings.add("Unparsed SMS line: " + abbreviate(trimmed, 120))
            );
        }

        transactions.sort(Comparator.comparing(MpesaTransaction::transactionInstant));
        LocalDate start = transactions.stream().map(MpesaTransaction::transactionDate).min(LocalDate::compareTo).orElse(null);
        LocalDate end = transactions.stream().map(MpesaTransaction::transactionDate).max(LocalDate::compareTo).orElse(null);

        return new ParsedStatement(StatementFormat.MPESA_SMS, null, msisdnHint, null, start, end, transactions, warnings);
    }

    private java.util.Optional<MpesaTransaction> parseLine(String line) {
        Matcher received = RECEIVED.matcher(line);
        if (received.find()) {
            return java.util.Optional.of(buildTxn(
                    received.group(4), received.group(5), TransactionDirection.CREDIT,
                    received.group(1), received.group(2), line));
        }
        Matcher sent = SENT.matcher(line);
        if (sent.find()) {
            return java.util.Optional.of(buildTxn(
                    sent.group(4), sent.group(5), TransactionDirection.DEBIT,
                    sent.group(1), sent.group(2), line));
        }
        Matcher sentMerchant = SENT_MERCHANT.matcher(line);
        if (sentMerchant.find()) {
            return java.util.Optional.of(buildTxn(
                    sentMerchant.group(3), sentMerchant.group(4), TransactionDirection.DEBIT,
                    sentMerchant.group(1), sentMerchant.group(2), line));
        }
        Matcher paid = PAID.matcher(line);
        if (paid.find()) {
            return java.util.Optional.of(buildTxn(
                    paid.group(3), paid.group(4), TransactionDirection.DEBIT,
                    paid.group(1), paid.group(2), line));
        }
        return java.util.Optional.empty();
    }

    private MpesaTransaction buildTxn(
            String dateToken,
            String timeToken,
            TransactionDirection direction,
            String amountToken,
            String counterparty,
            String rawLine) {
        LocalDate date = parseDate(dateToken);
        LocalTime time = parseTime(timeToken);
        LocalDateTime dateTime = LocalDateTime.of(date, time);
        return new MpesaTransaction(
                date,
                dateTime.atZone(NAIROBI).toInstant(),
                direction,
                parseAmount(amountToken),
                counterparty.trim(),
                rawLine
        );
    }

    private LocalDate parseDate(String token) {
        String[] patterns = { "d/M/yy", "d/M/yyyy", "dd/MM/yy", "dd/MM/yyyy" };
        for (String pattern : patterns) {
            try {
                return LocalDate.parse(token, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new IllegalArgumentException("Unparseable date: " + token);
    }

    private LocalTime parseTime(String token) {
        return LocalTime.parse(token.trim().toUpperCase(Locale.ROOT), DateTimeFormatter.ofPattern("h:mm a"));
    }

    private BigDecimal parseAmount(String token) {
        return new BigDecimal(token.replace(",", ""));
    }

    private String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
