package com.ttacs.scorer.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyFile {

    public ProductSection product = new ProductSection();
    public EligibilitySection eligibility = new EligibilitySection();
    public ScoringSection scoring = new ScoringSection();
    public PolicyPdfSection policy = new PolicyPdfSection();
    public FlexVertexSection flexvertex = new FlexVertexSection();
    public ServerSection server = new ServerSection();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductSection {
        public String name = "Unsecured Mobile Money Credit";
        public int maxLoanKes = 150_000;
        public double loanToInflowRatio = 1.5;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EligibilitySection {
        public int minMonthlyNetInflowKes = 50_000;
        public int minAccountTenureMonths = 6;
        public int maxActiveLoans = 2;
        public double maxRepaymentToInflowRatio = 0.40;
        public double maxInflowVolatilityCv = 0.65;
        public int minTransactionCount = 8;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScoringSection {
        public WeightSection weights = new WeightSection();
        public int roundTrippingPenalty = 25;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class WeightSection {
            public int tenure = 20;
            public int inflow = 30;
            public int stability = 20;
            public int activity = 15;
            public int salaryPattern = 10;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PolicyPdfSection {
        public String pdfPath = "../FlexVertex_Mobile_Money_Credit_Policy_Guidelines.pdf";
        public boolean materializeOnStart = true;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FlexVertexSection {
        public boolean enabled = false;
        public String host = "localhost";
        public int port = 10_000;
        public String adminPath = "/System/System/System/Admin";
        public String adminPassword = "REPLACE_ME";
        public String domain = "TTACS";
        public String nexus = "Scorer";
        public String schema = "MpesaCredit";
        public String underwriterPassword = "REPLACE_ME";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ServerSection {
        public int port = 8091;
    }
}
