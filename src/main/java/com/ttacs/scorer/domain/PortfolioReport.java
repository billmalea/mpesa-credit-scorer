package com.ttacs.scorer.domain;

import java.util.Map;

public record PortfolioReport(
        int totalApplications,
        int approved,
        int referred,
        int declined,
        Map<String, Integer> failedRuleCounts
) {
}
