package com.ttacs.scorer.domain;

import java.util.List;

public record ReconstructResult(
        String applicationId,
        String applicantName,
        String verdict,
        List<ReconstructStep> steps
) {
}
