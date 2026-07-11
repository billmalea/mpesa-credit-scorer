package com.ttacs.scorer.policy;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads lender policy PDF once and overlays YAML thresholds when a rule is found.
 * Mirrors the demo's "materialize rule from document" step without requiring an LLM.
 */
public final class PolicyMaterializer {

    private static final Pattern INFLOW_RULE = Pattern.compile(
            "verified monthly net cash inflow of less than\\s+KES\\s+([0-9,]+)",
            Pattern.CASE_INSENSITIVE
    );

    private PolicyMaterializer() {
    }

    public static MaterializedPolicy materialize(PolicyFile policy, Path policyFilePath) {
        PolicyFile effective = copy(policy);
        String source = "policy.yml";
        String snippet = "Configured thresholds from policy.yml";

        Path pdfPath = resolvePdfPath(policy, policyFilePath);
        if (policy.policy.materializeOnStart && Files.exists(pdfPath)) {
            try {
                String text = extractPdfText(pdfPath);
                Optional<Integer> threshold = extractMinInflowThreshold(text);
                if (threshold.isPresent()) {
                    effective.eligibility.minMonthlyNetInflowKes = threshold.get();
                    source = pdfPath.getFileName().toString();
                    snippet = "Materialized from policy PDF: minimum net inflow KES " + threshold.get();
                }
            } catch (IOException ex) {
                System.err.println("Policy PDF read failed, using YAML thresholds: " + ex.getMessage());
            }
        }

        return new MaterializedPolicy(effective, source, snippet, pdfPath);
    }

    public static Path resolvePdfPath(PolicyFile policy, Path policyFilePath) {
        Path configured = Path.of(policy.policy.pdfPath);
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        Path base = policyFilePath.toAbsolutePath().getParent();
        if (base == null) {
            base = Path.of(".").toAbsolutePath().normalize();
        }
        return base.resolve(configured).normalize();
    }

    public static String extractPdfText(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(Files.readAllBytes(pdfPath))) {
            return new PDFTextStripper().getText(document);
        }
    }

    public static List<String> chunkPolicyText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String[] paragraphs = text.split("\\R{2,}");
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (current.length() + trimmed.length() > 1800) {
                if (current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current = new StringBuilder();
                }
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    public static String policyVersion(MaterializedPolicy policy) {
        PolicyFile.EligibilitySection eligibility = policy.policy().eligibility;
        return policy.source()
                + "|inflow=" + eligibility.minMonthlyNetInflowKes
                + "|tenure=" + eligibility.minAccountTenureMonths
                + "|vol=" + eligibility.maxInflowVolatilityCv;
    }

    private static PolicyFile copy(PolicyFile policy) {
        try {
            ObjectMapperYaml helper = new ObjectMapperYaml();
            return helper.copy(policy);
        } catch (IOException ex) {
            return policy;
        }
    }

    private static Optional<Integer> extractMinInflowThreshold(String text) {
        Matcher matcher = INFLOW_RULE.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(matcher.group(1).replace(",", "")));
    }

    public record MaterializedPolicy(
            PolicyFile policy,
            String source,
            String snippet,
            Path pdfPath
    ) {
        public MaterializedPolicy(PolicyFile policy, String source, String snippet) {
            this(policy, source, snippet, null);
        }
    }

    /** Small helper to deep-copy YAML POJOs without adding a dependency. */
    private static final class ObjectMapperYaml {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());

        PolicyFile copy(PolicyFile policy) throws IOException {
            return mapper.readValue(mapper.writeValueAsString(policy), PolicyFile.class);
        }
    }
}
