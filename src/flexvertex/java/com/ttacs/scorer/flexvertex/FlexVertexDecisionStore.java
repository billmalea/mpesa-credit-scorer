package com.ttacs.scorer.flexvertex;

import com.flexvertex.messaging.client.drivers.FlexDirectClientDriver;
import com.flexvertex.messaging.client.interfaces.IFlexClientDriver;
import com.flexvertex.multiverse.client.FlexMultiverse;
import com.flexvertex.multiverse.shared.constants.FlexConstants.FlexType;
import com.flexvertex.multiverse.shared.interfaces.IFlexClass;
import com.flexvertex.multiverse.shared.interfaces.IFlexConnection;
import com.flexvertex.multiverse.shared.interfaces.IFlexDomain;
import com.flexvertex.multiverse.shared.interfaces.IFlexNexus;
import com.flexvertex.multiverse.shared.interfaces.IFlexObject;
import com.flexvertex.multiverse.shared.interfaces.IFlexSchema;
import com.flexvertex.multiverse.shared.interfaces.IFlexSegment;
import com.flexvertex.multiverse.shared.interfaces.IFlexSession;
import com.flexvertex.multiverse.shared.journey.FlexJourney;
import com.flexvertex.multiverse.shared.security.FlexUserAccount;
import com.flexvertex.multiverse.shared.transaction.FlexAutoSessionTx;
import com.flexvertex.security.shared.secrets.FlexPasswordSecret;
import com.flexvertex.security.shared.tokens.FlexUsernamePasswordToken;
import com.flexvertex.universe.components.interfaces.IFlexType;
import com.flexvertex.universe.components.time.FlexDateTime;
import com.ttacs.scorer.domain.CreditDecision;
import com.ttacs.scorer.domain.EvaluateRequest;
import com.ttacs.scorer.domain.PortfolioReport;
import com.ttacs.scorer.domain.ReconstructResult;
import com.ttacs.scorer.domain.ReconstructStep;
import com.ttacs.scorer.domain.RuleFinding;
import com.ttacs.scorer.domain.StatementEvidence;
import com.ttacs.scorer.domain.StatementFeatures;
import com.ttacs.scorer.policy.PolicyFile;
import com.ttacs.scorer.policy.PolicyMaterializer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class FlexVertexDecisionStore implements DecisionStore {

    private static final Pattern SAFE_APPLICATION_ID = Pattern.compile("[^A-Za-z0-9-]");

    private final PolicyFile.FlexVertexSection config;
    private final String productName;
    private boolean initialized;

    public FlexVertexDecisionStore(PolicyFile.FlexVertexSection config, String productName) {
        this.config = config;
        this.productName = productName;
    }

    @Override
    public void bootstrap() {
        ensureInitialized();
        try (IFlexSession session = openAdmin()) {
            try (FlexAutoSessionTx tx = new FlexAutoSessionTx(session)) {
                IFlexDomain domain = session.domainExists(config.domain)
                        ? session.openDomain(config.domain)
                        : session.createDomain(config.domain);
                IFlexNexus nexus = domain.nexusExists(config.nexus)
                        ? domain.openNexus(config.nexus)
                        : domain.createNexus(config.nexus);

                if (!nexus.schemaExists(config.schema)) {
                    try (IFlexSchema schema = nexus.createSchema(config.schema)) {
                        defineClasses(schema);
                        schema.createUserAccount("Underwriter", new FlexPasswordSecret(config.underwriterPassword));
                        schema.save();
                    }
                } else {
                    try (IFlexSchema schema = nexus.openSchema(config.schema)) {
                        defineClasses(schema);
                        try {
                            schema.createUserAccount("Underwriter", new FlexPasswordSecret(config.underwriterPassword));
                        } catch (Exception ignored) {
                        }
                        schema.save();
                    }
                }
                nexus.close();
                domain.close();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("FlexVertex bootstrap failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void materializePolicy(PolicyMaterializer.MaterializedPolicy policy, Path policyFilePath) {
        ensureInitialized();
        try (IFlexSession session = openUnderwriter()) {
            try (FlexAutoSessionTx tx = new FlexAutoSessionTx(session)) {
                FlexUserAccount account = (FlexUserAccount) session.getUserAccount();
                Path pdfPath = policy.pdfPath() != null
                        ? policy.pdfPath()
                        : PolicyMaterializer.resolvePdfPath(policy.policy(), policyFilePath);
                PolicyGraphMaterializer.materialize(account.getSchema(), policy, pdfPath);
            }
        } catch (Exception ex) {
            System.err.println("FlexVertex policy materialize skipped: " + ex.getMessage());
        }
    }

    @Override
    public void persist(
            EvaluateRequest request,
            StatementEvidence evidence,
            StatementFeatures features,
            CreditDecision decision,
            PolicyMaterializer.MaterializedPolicy policy) {
        ensureInitialized();
        try (IFlexSession session = openUnderwriter()) {
            try (FlexAutoSessionTx tx = new FlexAutoSessionTx(session)) {
                FlexUserAccount account = (FlexUserAccount) session.getUserAccount();
                IFlexSchema schema = account.getSchema();
                String policyVersion = PolicyMaterializer.policyVersion(policy);

                IFlexObject applicant = schema.createObject("Applicant");
                applicant.setProperty("Name", decision.applicantName());
                applicant.setProperty("Msisdn", orEmpty(request.msisdn()));
                applicant.setProperty("KycStatus", "Parsed");
                applicant.save();

                IFlexObject application = schema.createObject("CreditApplication");
                application.setProperty("ApplicationID", decision.applicationId());
                application.setProperty("RequestedAmountKES", decision.requestedAmountKes());
                application.setProperty("ProductType", productName);
                application.setProperty("Status", decision.verdict().name());
                application.setProperty("SubmittedAt", new FlexDateTime(decision.decidedAt()));
                application.save();

                IFlexConnection evaluation = applicant.connectTo("CreditEvaluation", application);
                evaluation.setProperty("VerifiedMonthlyNetCashInflowKES", features.monthlyVerifiedInflowKes().intValue());
                evaluation.setProperty("AccountTenureMonths", features.tenureMonths());
                evaluation.setProperty("ProjectedMonthlyRepaymentKES", request.projectedMonthlyRepaymentKes());
                evaluation.setProperty("ActiveLoanCount", request.activeLoanCount());
                evaluation.setProperty("EvidenceSource", "Parsed M-Pesa statement");
                evaluation.setProperty("CreditScore", decision.creditScore());
                evaluation.setProperty("MaxLoanKES", decision.maxLoanKes());
                if (evidence != null) {
                    evaluation.setProperty("StatementFormat", evidence.format() != null ? evidence.format().name() : "");
                    evaluation.setProperty("TransactionCount", evidence.transactionCount());
                    evaluation.setProperty("StatementPeriodStart", evidence.periodStart() != null
                            ? evidence.periodStart().toString()
                            : "");
                    evaluation.setProperty("StatementPeriodEnd", evidence.periodEnd() != null
                            ? evidence.periodEnd().toString()
                            : "");
                    evaluation.setProperty("StatementHash", orEmpty(evidence.statementHash()));
                }
                evaluation.save();

                IFlexObject decisionObject = schema.createObject("Decision");
                decisionObject.setProperty("DecisionType", "CreditUnderwriting");
                decisionObject.setProperty("Verdict", decision.verdict().name());
                decisionObject.setProperty("Reason", decision.reason());
                decisionObject.setProperty("CreditScore", decision.creditScore());
                decisionObject.setProperty("MaxLoanKES", decision.maxLoanKes());
                decisionObject.setProperty("TokensUsed", 0);
                decisionObject.setProperty("CreatedAt", new FlexDateTime(decision.decidedAt()));
                decisionObject.connectTo(application, "evaluated");
                decisionObject.save();

                for (RuleFinding finding : decision.findings()) {
                    IFlexObject rule = findActiveRule(schema, finding.code(), policyVersion);
                    IFlexObject findingObject = schema.createObject("Finding");
                    findingObject.setProperty("RuleCode", finding.code());
                    findingObject.setProperty("Name", finding.name());
                    findingObject.setProperty("FindingType", finding.passed() ? "Pass" : "Fail");
                    findingObject.setProperty("Severity", finding.severity().name());
                    findingObject.setProperty("Summary", finding.summary());
                    findingObject.setProperty("ActualValue", finding.actual());
                    findingObject.setProperty("ExpectedValue", finding.expected());
                    findingObject.connectTo(application, "appliesTo");
                    findingObject.connectTo(decisionObject, "documents");
                    if (rule != null) {
                        findingObject.connectTo(rule, "basedOn");
                    }
                    findingObject.save();
                }

                schema.save();
            }
        } catch (Exception ex) {
            System.err.println("FlexVertex persist skipped: " + ex.getMessage());
        }
    }

    @Override
    public Optional<ReconstructResult> reconstruct(String applicationId) {
        ensureInitialized();
        String safeId = SAFE_APPLICATION_ID.matcher(applicationId).replaceAll("");
        if (safeId.isBlank()) {
            return Optional.empty();
        }

        try (IFlexSession session = openUnderwriter()) {
            try (FlexAutoSessionTx tx = new FlexAutoSessionTx(session)) {
                IFlexSchema schema = ((FlexUserAccount) session.getUserAccount()).getSchema();
                for (IFlexObject application : schema.sql(
                        "select * from CreditApplication where ApplicationID = '" + safeId + "'"
                ).getCollection()) {
                    List<ReconstructStep> steps = new ArrayList<>();
                    String applicantName = "";
                    String verdict = stringProperty(application, "Status");

                    FlexJourney applicantJourney = application
                            .journey()
                            .connection("CreditEvaluation")
                            .from()
                            .explore();
                    if (applicantJourney.exists()) {
                        for (IFlexSegment segment : applicantJourney.getSegments()) {
                            IFlexObject applicant = segment.getSource();
                            applicantName = stringProperty(applicant, "Name");
                            if (!applicantName.isBlank()) {
                                steps.add(0, new ReconstructStep(
                                        "Applicant",
                                        applicantName,
                                        stringProperty(applicant, "Msisdn")
                                ));
                            }
                        }
                    }

                    steps.add(new ReconstructStep(
                            "Application",
                            safeId,
                            "Requested KES " + intProperty(application, "RequestedAmountKES")
                    ));

                    FlexJourney decisionJourney = application
                            .journey()
                            .connection()
                            .name("evaluated")
                            .from()
                            .target("Decision")
                            .explore();
                    if (decisionJourney.exists()) {
                        for (IFlexSegment segment : decisionJourney.getSegments()) {
                            IFlexObject decision = segment.getSource();
                            verdict = stringProperty(decision, "Verdict");
                            if (verdict.isBlank()) {
                                verdict = stringProperty(application, "Status");
                            }
                            steps.add(new ReconstructStep(
                                    "Decision",
                                    verdict,
                                    stringProperty(decision, "Reason")
                            ));
                        }
                    }

                    FlexJourney findingsJourney = application
                            .journey()
                            .connection()
                            .name("appliesTo")
                            .from()
                            .target("Finding")
                            .explore();
                    if (findingsJourney.exists()) {
                        for (IFlexSegment segment : findingsJourney.getSegments()) {
                            IFlexObject finding = segment.getTarget();
                            String code = stringProperty(finding, "RuleCode");
                            steps.add(new ReconstructStep(
                                    "Finding",
                                    code,
                                    stringProperty(finding, "Summary")
                                            + " · actual " + stringProperty(finding, "ActualValue")
                                            + " · expected " + stringProperty(finding, "ExpectedValue")
                            ));

                            FlexJourney ruleJourney = finding
                                    .journey()
                                    .connection()
                                    .name("basedOn")
                                    .target("Rule")
                                    .explore();
                            if (ruleJourney.exists()) {
                                IFlexObject rule = ruleJourney.getTarget();
                                steps.add(new ReconstructStep(
                                        "Rule",
                                        stringProperty(rule, "RuleCode"),
                                        stringProperty(rule, "Condition")
                                                + " · source " + stringProperty(rule, "PolicySource")
                                ));

                                FlexJourney chunkJourney = rule
                                        .journey()
                                        .connection()
                                        .name("originatedFrom")
                                        .target("EmbeddingChunk")
                                        .explore();
                                if (chunkJourney.exists()) {
                                    IFlexObject chunk = chunkJourney.getTarget();
                                    String text = stringProperty(chunk, "Text");
                                    if (text.length() > 240) {
                                        text = text.substring(0, 240) + "...";
                                    }
                                    steps.add(new ReconstructStep("PolicyChunk", code, text));
                                }
                            }
                        }
                    }

                    return Optional.of(new ReconstructResult(safeId, applicantName, verdict, steps));
                }
            }
        } catch (Exception ex) {
            System.err.println("FlexVertex reconstruct failed: " + ex.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public PortfolioReport portfolioReport() {
        ensureInitialized();
        try (IFlexSession session = openUnderwriter()) {
            try (FlexAutoSessionTx tx = new FlexAutoSessionTx(session)) {
                IFlexSchema schema = ((FlexUserAccount) session.getUserAccount()).getSchema();
                int approved = countWhere(schema, "CreditApplication", "Status = 'APPROVED'");
                int referred = countWhere(schema, "CreditApplication", "Status = 'REFERRED'");
                int declined = countWhere(schema, "CreditApplication", "Status = 'DECLINED'");
                int total = approved + referred + declined;

                Map<String, Integer> failedRuleCounts = new HashMap<>();
                for (IFlexObject finding : schema.sql(
                        "select * from Finding where FindingType = 'Fail'"
                ).getCollection()) {
                    String code = stringProperty(finding, "RuleCode");
                    if (!code.isBlank()) {
                        failedRuleCounts.merge(code, 1, Integer::sum);
                    }
                }

                return new PortfolioReport(total, approved, referred, declined, failedRuleCounts);
            }
        } catch (Exception ex) {
            System.err.println("FlexVertex report failed: " + ex.getMessage());
            return new PortfolioReport(0, 0, 0, 0, Map.of());
        }
    }

    private IFlexObject findActiveRule(IFlexSchema schema, String ruleCode, String policyVersion) throws Exception {
        for (IFlexObject rule : schema.sql(
                "select * from Rule where RuleCode = '"
                        + escapeSql(ruleCode)
                        + "' and Status = 'Active' and PolicyVersion = '"
                        + escapeSql(policyVersion)
                        + "'"
        ).getCollection()) {
            return rule;
        }
        for (IFlexObject rule : schema.sql(
                "select * from Rule where RuleCode = '" + escapeSql(ruleCode) + "' and Status = 'Active'"
        ).getCollection()) {
            return rule;
        }
        return null;
    }

    private int countWhere(IFlexSchema schema, String className, String whereClause) throws Exception {
        int count = 0;
        for (IFlexObject ignored : schema.sql("select * from " + className + " where " + whereClause).getCollection()) {
            count++;
        }
        return count;
    }

    private void defineClasses(IFlexSchema schema) throws Exception {
        createIfMissing(schema, "Applicant", cls -> {
            ensureProperty(cls, "Name", FlexType.String);
            ensureProperty(cls, "Msisdn", FlexType.String);
            ensureProperty(cls, "KycStatus", FlexType.String);
            cls.setLabelFormat("%s", "Name");
        });
        createIfMissing(schema, "CreditApplication", cls -> {
            ensureProperty(cls, "ApplicationID", FlexType.String);
            ensureProperty(cls, "RequestedAmountKES", FlexType.Integer);
            ensureProperty(cls, "ProductType", FlexType.String);
            ensureProperty(cls, "Status", FlexType.String);
            ensureProperty(cls, "SubmittedAt", FlexType.DateTime);
        });
        createConnectionIfMissing(schema, "CreditEvaluation", conn -> {
            ensureProperty(conn, "VerifiedMonthlyNetCashInflowKES", FlexType.Integer);
            ensureProperty(conn, "AccountTenureMonths", FlexType.Integer);
            ensureProperty(conn, "ProjectedMonthlyRepaymentKES", FlexType.Integer);
            ensureProperty(conn, "ActiveLoanCount", FlexType.Integer);
            ensureProperty(conn, "EvidenceSource", FlexType.String);
            ensureProperty(conn, "CreditScore", FlexType.Integer);
            ensureProperty(conn, "MaxLoanKES", FlexType.Integer);
            ensureProperty(conn, "StatementFormat", FlexType.String);
            ensureProperty(conn, "TransactionCount", FlexType.Integer);
            ensureProperty(conn, "StatementPeriodStart", FlexType.String);
            ensureProperty(conn, "StatementPeriodEnd", FlexType.String);
            ensureProperty(conn, "StatementHash", FlexType.String);
        });
        createIfMissing(schema, "EmbeddingChunk", cls -> {
            ensureProperty(cls, "Text", FlexType.String);
            ensureProperty(cls, "DocumentType", FlexType.String);
            ensureProperty(cls, "ChunkIndex", FlexType.Integer);
            ensureProperty(cls, "AssetKey", FlexType.Key);
        });
        createIfMissing(schema, "Rule", cls -> {
            ensureProperty(cls, "Name", FlexType.String);
            ensureProperty(cls, "RuleCode", FlexType.String);
            ensureProperty(cls, "RuleType", FlexType.String);
            ensureProperty(cls, "Status", FlexType.String);
            ensureProperty(cls, "Condition", FlexType.String);
            ensureProperty(cls, "ThresholdValue", FlexType.Integer);
            ensureProperty(cls, "ThresholdUnit", FlexType.String);
            ensureProperty(cls, "PolicySource", FlexType.String);
            ensureProperty(cls, "PolicySnippet", FlexType.String);
            ensureProperty(cls, "PolicyVersion", FlexType.String);
            ensureProperty(cls, "EffectiveFrom", FlexType.DateTime);
        });
        createIfMissing(schema, "Decision", cls -> {
            ensureProperty(cls, "DecisionType", FlexType.String);
            ensureProperty(cls, "Verdict", FlexType.String);
            ensureProperty(cls, "Reason", FlexType.String);
            ensureProperty(cls, "CreditScore", FlexType.Integer);
            ensureProperty(cls, "MaxLoanKES", FlexType.Integer);
            ensureProperty(cls, "TokensUsed", FlexType.Integer);
            ensureProperty(cls, "CreatedAt", FlexType.DateTime);
        });
        createIfMissing(schema, "Finding", cls -> {
            ensureProperty(cls, "RuleCode", FlexType.String);
            ensureProperty(cls, "Name", FlexType.String);
            ensureProperty(cls, "FindingType", FlexType.String);
            ensureProperty(cls, "Severity", FlexType.String);
            ensureProperty(cls, "Summary", FlexType.String);
            ensureProperty(cls, "ActualValue", FlexType.String);
            ensureProperty(cls, "ExpectedValue", FlexType.String);
        });
        schema.save();
    }

    private void createIfMissing(IFlexSchema schema, String className, ClassConfigurer configurer) throws Exception {
        IFlexClass cls = schema.classExists(className) ? schema.getClass(className) : schema.createClass(className);
        configurer.configure(cls);
        cls.save();
    }

    private void createConnectionIfMissing(IFlexSchema schema, String className, ClassConfigurer configurer) throws Exception {
        IFlexClass cls = schema.classExists(className)
                ? schema.getClass(className)
                : schema.createClass(className, "sysclass:FlexConnection");
        configurer.configure(cls);
        cls.save();
    }

    private void ensureProperty(IFlexClass cls, String name, IFlexType type) throws Exception {
        try {
            cls.createProperty(name, type);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            if (!message.contains("already exists")) {
                throw ex;
            }
        }
    }

    private IFlexSession openAdmin() throws Exception {
        IFlexClientDriver driver = new FlexDirectClientDriver(config.host, config.port);
        return FlexMultiverse.openSession(driver, new FlexUsernamePasswordToken(config.adminPath, config.adminPassword));
    }

    private IFlexSession openUnderwriter() throws Exception {
        IFlexClientDriver driver = new FlexDirectClientDriver(config.host, config.port);
        String underwriterPath = "/" + config.domain + "/" + config.nexus + "/" + config.schema + "/Underwriter";
        return FlexMultiverse.openSession(driver, new FlexUsernamePasswordToken(underwriterPath, config.underwriterPassword));
    }

    private void ensureInitialized() {
        if (!initialized) {
            FlexMultiverse.initialize();
            initialized = true;
        }
    }

    private String stringProperty(IFlexObject object, String property) {
        if (object == null) {
            return "";
        }
        Object value = object.getProperty(property);
        return value == null ? "" : String.valueOf(value);
    }

    private int intProperty(IFlexObject object, String property) {
        Object value = object.getProperty(property);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private String escapeSql(String value) {
        return value.replace("'", "''");
    }

    @FunctionalInterface
    private interface ClassConfigurer {
        void configure(IFlexClass cls) throws Exception;
    }
}
