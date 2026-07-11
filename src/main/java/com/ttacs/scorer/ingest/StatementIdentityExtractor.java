package com.ttacs.scorer.ingest;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StatementIdentityExtractor {

    private static final Pattern CUSTOMER_NAME = Pattern.compile(
            "Customer Name:\\s*(.+)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MOBILE_NUMBER = Pattern.compile(
            "Mobile Number:\\s*(\\d+)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern EMAIL = Pattern.compile(
            "Email Address:\\s*(\\S+)", Pattern.CASE_INSENSITIVE
    );

    private StatementIdentityExtractor() {
    }

    public record Identity(String customerName, String msisdn, String email) {
    }

    public static Optional<Identity> extract(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String name = matchFirst(CUSTOMER_NAME, text, 1).orElse(null);
        String msisdn = matchFirst(MOBILE_NUMBER, text, 1).map(StatementIdentityExtractor::normalizeMsisdn).orElse(null);
        String email = matchFirst(EMAIL, text, 1).orElse(null);
        if (name == null && msisdn == null && email == null) {
            return Optional.empty();
        }
        return Optional.of(new Identity(name, msisdn, email));
    }

    private static Optional<String> matchFirst(Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String value = matcher.group(group).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static String normalizeMsisdn(String raw) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("254")) {
            return digits;
        }
        if (digits.startsWith("0") && digits.length() >= 10) {
            return "254" + digits.substring(1);
        }
        return digits;
    }
}
