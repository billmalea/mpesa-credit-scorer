package com.ttacs.scorer.features;

import com.ttacs.scorer.domain.MpesaTransaction;
import com.ttacs.scorer.domain.ParsedStatement;
import com.ttacs.scorer.domain.StatementFeatures;
import com.ttacs.scorer.domain.TransactionDirection;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FeatureExtractor {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);
    private static final BigDecimal MIN_VERIFIED_CREDIT_KES = BigDecimal.valueOf(100);
    private static final BigDecimal MIN_ROUND_TRIP_KES = BigDecimal.valueOf(500);
    private static final int ROUND_TRIP_WINDOW_HOURS = 48;
    private static final int ROUND_TRIP_PAIR_THRESHOLD = 2;
    private static final Pattern MSISDN = Pattern.compile("254\\d{9}");

    public StatementFeatures extract(ParsedStatement statement) {
        List<MpesaTransaction> txns = statement.transactions();
        LocalDate start = statement.periodStart();
        LocalDate end = statement.periodEnd();

        int tenureMonths = calculateTenureMonths(start, end);
        Set<LocalDate> partialMonths = partialMonths(start, end);

        Map<LocalDate, BigDecimal> monthlyVerifiedCredits = new HashMap<>();
        Map<LocalDate, BigDecimal> monthlyOperationalDebits = new HashMap<>();
        Map<String, BigDecimal> verifiedCreditsBySource = new HashMap<>();
        Map<String, Set<LocalDate>> verifiedSourceMonths = new HashMap<>();

        int creditCount = 0;
        int debitCount = 0;
        Set<String> counterparties = new HashSet<>();

        for (MpesaTransaction txn : txns) {
            LocalDate monthBucket = txn.transactionDate().withDayOfMonth(1);
            counterparties.add(txn.counterparty());

            if (txn.direction() == TransactionDirection.CREDIT) {
                creditCount++;
                if (isVerifiedExternalCredit(txn, statement.customerName())) {
                    monthlyVerifiedCredits.merge(monthBucket, txn.amountKes(), BigDecimal::add);
                    String source = incomeSourceKey(txn.counterparty());
                    verifiedCreditsBySource.merge(source, txn.amountKes(), BigDecimal::add);
                    verifiedSourceMonths.computeIfAbsent(source, ignored -> new HashSet<>()).add(monthBucket);
                }
            } else if (txn.direction() == TransactionDirection.DEBIT) {
                debitCount++;
                if (isOperationalDebit(txn)) {
                    monthlyOperationalDebits.merge(monthBucket, txn.amountKes(), BigDecimal::add);
                }
            }
        }

        BigDecimal totalVerifiedCredits = monthlyVerifiedCredits.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOperationalDebits = monthlyOperationalDebits.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal monthlyVerifiedInflow = totalVerifiedCredits.divide(
                BigDecimal.valueOf(Math.max(1, tenureMonths)),
                MC
        );

        BigDecimal monthlyNetSurplus = averageMonthlyNetSurplus(
                monthlyVerifiedCredits,
                monthlyOperationalDebits,
                partialMonths
        );

        BigDecimal avgMonthlyCredits = totalVerifiedCredits.divide(
                BigDecimal.valueOf(Math.max(1, monthlyVerifiedCredits.size())),
                MC
        );
        BigDecimal avgMonthlyDebits = totalOperationalDebits.divide(
                BigDecimal.valueOf(Math.max(1, monthlyOperationalDebits.size())),
                MC
        );

        double volatility = coefficientOfVariation(
                monthlyVerifiedCredits,
                partialMonths
        );
        boolean salaryPattern = detectSalaryPattern(verifiedSourceMonths);
        boolean businessInflowPattern = detectBusinessInflowPattern(
                verifiedCreditsBySource,
                verifiedSourceMonths,
                totalVerifiedCredits
        );
        boolean roundTripping = detectRoundTripping(txns);

        return new StatementFeatures(
                tenureMonths,
                monthlyVerifiedInflow.setScale(0, RoundingMode.HALF_UP),
                monthlyNetSurplus.max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP),
                avgMonthlyCredits.setScale(0, RoundingMode.HALF_UP),
                avgMonthlyDebits.setScale(0, RoundingMode.HALF_UP),
                BigDecimal.valueOf(volatility).setScale(4, RoundingMode.HALF_UP),
                creditCount,
                debitCount,
                counterparties.size(),
                salaryPattern,
                businessInflowPattern,
                roundTripping
        );
    }

    private int calculateTenureMonths(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return 0;
        }
        long months = ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1));
        return (int) Math.max(1, months + 1);
    }

    private Set<LocalDate> partialMonths(LocalDate start, LocalDate end) {
        Set<LocalDate> partial = new HashSet<>();
        if (start == null || end == null) {
            return partial;
        }
        if (start.getDayOfMonth() > 1) {
            partial.add(start.withDayOfMonth(1));
        }
        if (end.getDayOfMonth() < end.lengthOfMonth()) {
            partial.add(end.withDayOfMonth(1));
        }
        return partial;
    }

    private boolean isVerifiedExternalCredit(MpesaTransaction txn, String customerName) {
        if (txn.direction() != TransactionDirection.CREDIT) {
            return false;
        }
        if (txn.amountKes().compareTo(MIN_VERIFIED_CREDIT_KES) < 0) {
            return false;
        }

        String counterparty = txn.counterparty().toLowerCase();
        if (counterparty.contains("overdraft")) {
            return false;
        }
        if (customerName != null && !customerName.isBlank()) {
            String normalizedName = customerName.trim().toLowerCase();
            if (counterparty.contains(normalizedName)) {
                return false;
            }
        }
        return true;
    }

    private boolean isOperationalDebit(MpesaTransaction txn) {
        if (txn.direction() != TransactionDirection.DEBIT) {
            return false;
        }
        String counterparty = txn.counterparty().toLowerCase();
        if (counterparty.contains("charge")) {
            return false;
        }
        return txn.amountKes().compareTo(BigDecimal.valueOf(50)) >= 0;
    }

    private String incomeSourceKey(String counterparty) {
        String normalized = counterparty.trim();
        int via = normalized.toLowerCase().indexOf(" via ");
        if (via > 0) {
            normalized = normalized.substring(0, via).trim();
        }
        int from = normalized.toLowerCase().indexOf(" from ");
        if (from >= 0 && from + 6 < normalized.length()) {
            normalized = normalized.substring(from + 6).trim();
        }
        return normalized.toLowerCase();
    }

    private BigDecimal averageMonthlyNetSurplus(
            Map<LocalDate, BigDecimal> monthlyVerifiedCredits,
            Map<LocalDate, BigDecimal> monthlyOperationalDebits,
            Set<LocalDate> partialMonths) {
        Set<LocalDate> months = new HashSet<>();
        months.addAll(monthlyVerifiedCredits.keySet());
        months.addAll(monthlyOperationalDebits.keySet());

        List<BigDecimal> nets = new ArrayList<>();
        for (LocalDate month : months) {
            if (partialMonths.contains(month)) {
                continue;
            }
            BigDecimal credits = monthlyVerifiedCredits.getOrDefault(month, BigDecimal.ZERO);
            BigDecimal debits = monthlyOperationalDebits.getOrDefault(month, BigDecimal.ZERO);
            nets.add(credits.subtract(debits));
        }

        if (nets.isEmpty()) {
            for (LocalDate month : months) {
                BigDecimal credits = monthlyVerifiedCredits.getOrDefault(month, BigDecimal.ZERO);
                BigDecimal debits = monthlyOperationalDebits.getOrDefault(month, BigDecimal.ZERO);
                nets.add(credits.subtract(debits));
            }
        }

        if (nets.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = nets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(nets.size()), MC);
    }

    private double coefficientOfVariation(Map<LocalDate, BigDecimal> monthlyValues, Set<LocalDate> partialMonths) {
        List<BigDecimal> list = new ArrayList<>();
        for (Map.Entry<LocalDate, BigDecimal> entry : monthlyValues.entrySet()) {
            if (!partialMonths.contains(entry.getKey())) {
                list.add(entry.getValue());
            }
        }
        if (list.size() < 2) {
            monthlyValues.values().forEach(list::add);
        }
        if (list.size() < 2) {
            return 0.0d;
        }

        BigDecimal mean = list.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(list.size()), MC);
        if (mean.compareTo(BigDecimal.ZERO) == 0) {
            return 0.0d;
        }

        double variance = list.stream()
                .mapToDouble(v -> v.subtract(mean, MC).pow(2).doubleValue())
                .average()
                .orElse(0.0d);
        return Math.sqrt(variance) / mean.doubleValue();
    }

    private boolean detectSalaryPattern(Map<String, Set<LocalDate>> verifiedSourceMonths) {
        for (Map.Entry<String, Set<LocalDate>> entry : verifiedSourceMonths.entrySet()) {
            if (entry.getValue().size() >= 3 && isPayrollLikeSource(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private boolean isPayrollLikeSource(String source) {
        String normalized = source.toLowerCase();
        return normalized.contains("payroll")
                || normalized.contains("salary")
                || normalized.contains("wages");
    }

    private boolean isInstitutionalIncomeSource(String source) {
        String normalized = source.toLowerCase();
        return normalized.contains("bank")
                || normalized.contains(" plc")
                || normalized.contains(" ltd")
                || normalized.contains("business payment")
                || normalized.contains("payroll");
    }

    private boolean detectBusinessInflowPattern(
            Map<String, BigDecimal> verifiedCreditsBySource,
            Map<String, Set<LocalDate>> verifiedSourceMonths,
            BigDecimal totalVerified) {
        if (totalVerified.compareTo(BigDecimal.ZERO) <= 0 || verifiedCreditsBySource.isEmpty()) {
            return false;
        }

        int minRecurringMonths = 3;
        double dominanceThreshold = 0.45;

        for (Map.Entry<String, BigDecimal> entry : verifiedCreditsBySource.entrySet()) {
            Set<LocalDate> months = verifiedSourceMonths.getOrDefault(entry.getKey(), Set.of());
            if (months.size() < minRecurringMonths) {
                continue;
            }
            if (entry.getValue().divide(totalVerified, MC).doubleValue() < dominanceThreshold) {
                continue;
            }
            if (isInstitutionalIncomeSource(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    private boolean detectRoundTripping(List<MpesaTransaction> txns) {
        Map<String, Integer> counterpartyPairs = new HashMap<>();

        for (int i = 0; i < txns.size(); i++) {
            MpesaTransaction a = txns.get(i);
            if (!isRoundTripCandidate(a)) {
                continue;
            }
            for (int j = i + 1; j < txns.size(); j++) {
                MpesaTransaction b = txns.get(j);
                if (!isRoundTripCandidate(b)) {
                    continue;
                }
                if (a.direction() == b.direction()) {
                    continue;
                }
                if (a.amountKes().compareTo(b.amountKes()) != 0) {
                    continue;
                }
                if (Math.abs(ChronoUnit.HOURS.between(a.transactionInstant(), b.transactionInstant()))
                        > ROUND_TRIP_WINDOW_HOURS) {
                    continue;
                }

                String counterparty = normalizeCounterparty(a.counterparty());
                if (!counterparty.equals(normalizeCounterparty(b.counterparty()))) {
                    continue;
                }

                counterpartyPairs.merge(counterparty, 1, Integer::sum);
            }
        }

        return counterpartyPairs.values().stream()
                .anyMatch(count -> count >= ROUND_TRIP_PAIR_THRESHOLD);
    }

    private boolean isRoundTripCandidate(MpesaTransaction txn) {
        if (txn.amountKes().compareTo(MIN_ROUND_TRIP_KES) < 0) {
            return false;
        }
        String counterparty = txn.counterparty().toLowerCase();
        return !counterparty.contains("charge");
    }

    private String normalizeCounterparty(String counterparty) {
        Matcher matcher = MSISDN.matcher(counterparty);
        if (matcher.find()) {
            return matcher.group();
        }

        String normalized = counterparty.trim().toLowerCase();
        for (String prefix : List.of(
                "customer transfer of funds from ",
                "customer transfer to - ",
                "customer transfer to ")) {
            if (normalized.startsWith(prefix)) {
                return normalized.substring(prefix.length()).trim();
            }
        }
        return normalized;
    }
}
