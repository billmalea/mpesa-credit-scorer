package com.ttacs.scorer.ingest;

import com.ttacs.scorer.domain.ParsedStatement;

public interface StatementParser {
    ParsedStatement parse(String text, String msisdnHint);
}
