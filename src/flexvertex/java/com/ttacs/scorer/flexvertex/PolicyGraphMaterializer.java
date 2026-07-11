package com.ttacs.scorer.flexvertex;

import com.flexvertex.multiverse.shared.interfaces.IFlexObject;
import com.flexvertex.multiverse.shared.interfaces.IFlexSchema;
import com.ttacs.scorer.policy.PolicyFile;
import com.ttacs.scorer.policy.PolicyMaterializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PolicyGraphMaterializer {

    private static final String DOCUMENT_TYPE = "Credit Policy";

    private PolicyGraphMaterializer() {
    }

    static void materialize(
            IFlexSchema schema,
            PolicyMaterializer.MaterializedPolicy policy,
            Path policyPdfPath) throws Exception {
        String version = PolicyMaterializer.policyVersion(policy);
        if (activePolicyExists(schema, version)) {
            return;
        }

        supersedeActiveRules(schema);

        String policyText = "";
        IFlexObject asset = null;
        if (policyPdfPath != null && Files.exists(policyPdfPath)) {
            policyText = PolicyMaterializer.extractPdfText(policyPdfPath);
            try {
                asset = schema.createAssetFromPath(policyPdfPath.toString());
                asset.save();
            } catch (Exception ex) {
                System.err.println("FlexVertex policy asset upload skipped: " + ex.getMessage());
            }
        }

        List<String> chunks = PolicyMaterializer.chunkPolicyText(policyText);
        Map<String, IFlexObject> chunkObjects = persistChunks(schema, asset, chunks);
        persistRules(schema, policy, version, chunks, chunkObjects);
        schema.save();
    }

    private static boolean activePolicyExists(IFlexSchema schema, String version) throws Exception {
        for (IFlexObject rule : schema.sql(
                "select * from Rule where RuleType = 'CreditPolicy' and Status = 'Active' and PolicyVersion = '"
                        + escapeSql(version) + "'"
        ).getCollection()) {
            if (rule != null) {
                return true;
            }
        }
        return false;
    }

    private static void supersedeActiveRules(IFlexSchema schema) throws Exception {
        for (IFlexObject rule : schema.sql(
                "select * from Rule where RuleType = 'CreditPolicy' and Status = 'Active'"
        ).getCollection()) {
            rule.setProperty("Status", "Superseded");
            rule.save();
        }
    }

    private static Map<String, IFlexObject> persistChunks(
            IFlexSchema schema,
            IFlexObject asset,
            List<String> chunks) throws Exception {
        Map<String, IFlexObject> chunkObjects = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            IFlexObject chunk = schema.createObject("EmbeddingChunk");
            chunk.setProperty("Text", chunks.get(i));
            chunk.setProperty("DocumentType", DOCUMENT_TYPE);
            chunk.setProperty("ChunkIndex", i);
            if (asset != null) {
                chunk.setProperty("AssetKey", asset.getObjectKey());
            }
            chunk.save();
            chunkObjects.put("chunk:" + i, chunk);
        }
        return chunkObjects;
    }

    private static void persistRules(
            IFlexSchema schema,
            PolicyMaterializer.MaterializedPolicy policy,
            String version,
            List<String> chunks,
            Map<String, IFlexObject> chunkObjects) throws Exception {
        PolicyFile.EligibilitySection eligibility = policy.policy().eligibility;
        List<RuleTemplate> templates = List.of(
                new RuleTemplate(
                        "MIN_NET_INFLOW",
                        "Minimum verified monthly cash inflow",
                        "MIN_NET_INFLOW >= " + eligibility.minMonthlyNetInflowKes,
                        eligibility.minMonthlyNetInflowKes,
                        "inflow"
                ),
                new RuleTemplate(
                        "ACCOUNT_TENURE",
                        "Minimum account tenure",
                        "ACCOUNT_TENURE >= " + eligibility.minAccountTenureMonths,
                        eligibility.minAccountTenureMonths,
                        "tenure"
                ),
                new RuleTemplate(
                        "REPAYMENT_CAPACITY",
                        "Repayment-to-inflow capacity",
                        "REPAYMENT_RATIO <= " + eligibility.maxRepaymentToInflowRatio,
                        (int) (eligibility.maxRepaymentToInflowRatio * 100),
                        "repayment"
                ),
                new RuleTemplate(
                        "INFLOW_VOLATILITY",
                        "Inflow stability",
                        "INFLOW_VOLATILITY <= " + eligibility.maxInflowVolatilityCv,
                        (int) (eligibility.maxInflowVolatilityCv * 100),
                        "volatility"
                ),
                new RuleTemplate(
                        "FRAUD_ROUND_TRIP",
                        "Round-tripping detection",
                        "FRAUD_ROUND_TRIP = none",
                        0,
                        "round"
                ),
                new RuleTemplate(
                        "TRANSACTION_DEPTH",
                        "Minimum transaction depth",
                        "TRANSACTION_DEPTH >= " + eligibility.minTransactionCount,
                        eligibility.minTransactionCount,
                        "transaction"
                )
        );

        for (RuleTemplate template : templates) {
            IFlexObject rule = schema.createObject("Rule");
            rule.setProperty("Name", template.name());
            rule.setProperty("RuleCode", template.code());
            rule.setProperty("RuleType", "CreditPolicy");
            rule.setProperty("Status", "Active");
            rule.setProperty("Condition", template.condition());
            rule.setProperty("ThresholdValue", template.thresholdValue());
            rule.setProperty("ThresholdUnit", template.code().contains("TENURE") ? "months" : "KES");
            rule.setProperty("PolicySource", policy.source());
            rule.setProperty("PolicySnippet", policy.snippet());
            rule.setProperty("PolicyVersion", version);
            rule.setProperty("EffectiveFrom", new com.flexvertex.universe.components.time.FlexDateTime(java.time.Instant.now()));

            IFlexObject chunk = findChunkForHint(chunks, chunkObjects, template.searchHint());
            if (chunk != null) {
                rule.connectTo(chunk, "originatedFrom");
            }
            rule.save();
        }
    }

    private static IFlexObject findChunkForHint(
            List<String> chunks,
            Map<String, IFlexObject> chunkObjects,
            String hint) {
        String needle = hint.toLowerCase(Locale.ROOT);
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                return chunkObjects.get("chunk:" + i);
            }
        }
        return chunkObjects.isEmpty() ? null : chunkObjects.values().iterator().next();
    }

    private static String escapeSql(String value) {
        return value.replace("'", "''");
    }

    private record RuleTemplate(
            String code,
            String name,
            String condition,
            int thresholdValue,
            String searchHint
    ) {
    }
}
