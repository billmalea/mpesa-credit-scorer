package com.ttacs.scorer.domain;

public record RuleFinding(
        String code,
        String name,
        RuleSeverity severity,
        boolean passed,
        String actual,
        String expected,
        String summary
) {
}
