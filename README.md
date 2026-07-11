# M-Pesa Credit Scorer

**TTACS Management Consulting** — deterministic M-Pesa statement underwriting with a core [FlexVertex](https://docs.flexvertex.com/) audit graph for demos.

License: [GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0).

---

## What this is

A Java 21 credit scorecard that:

1. **Ingests** Safaricom M-Pesa statements (CSV export, SMS text, or password-protected PDF)
2. **Extracts** cash-flow features (verified inflow, tenure, volatility, salary/business patterns, round-tripping)
3. **Evaluates** eligibility rules from `policy.yml` (optionally overlaid from a lender policy PDF)
4. **Scores** 0–100 and recommends a max loan
5. **Persists** Applicant → Decision → Finding → Rule → PolicyChunk in FlexVertex for reconstructable audit

Scoring is **local and deterministic**. FlexVertex does not change APPROVE / REFER / DECLINE math — it is the **audit / provenance pillar** of every demo. Demos always expect FlexVertex Iron running with the audit graph enabled.

---

## Architecture

```text
┌─────────────┐     ┌──────────────────┐     ┌────────────────┐     ┌────────────┐
│ Statement   │────▶│ FeatureExtractor │────▶│ RuleEvaluator  │────▶│ Scorecard  │
│ CSV/PDF/SMS │     │                  │     │ HARD / SOFT    │     │ 0–100 +    │
└─────────────┘     └──────────────────┘     └────────────────┘     │ max loan   │
                                                                    └─────┬──────┘
                                                                          │
                                                          ┌───────────────▼───────────────┐
                                                          │ CreditDecision (JSON / UI)    │
                                                          └───────────────┬───────────────┘
                                                                          │ core demo path
                                                          ┌───────────────▼───────────────┐
                                                          │ FlexVertexDecisionStore       │
                                                          │ TTACS / Scorer / MpesaCredit  │
                                                          └───────────────────────────────┘
```

### Decision pipeline

| Stage | Module | Responsibility |
|-------|--------|----------------|
| Ingest | `StatementIngestion`, parsers | Detect format; unlock PDF; parse transactions + identity |
| Features | `FeatureExtractor` | Monthly verified inflow, surplus, CV, patterns, fraud signals |
| Rules | `RuleEvaluator` | Eligibility with HARD_FAIL → DECLINED, SOFT_REFER → REFERRED |
| Score | `Scorecard` | Weighted points, caps by verdict, max loan vs requested |
| Audit | `DecisionStore` | FlexVertex audit graph (demo default); NoOp only as ops fallback |

### Verdicts

| Verdict | When |
|---------|------|
| `DECLINED` | Any HARD_FAIL rule fails (inflow, tenure, repayment, round-trip) |
| `REFERRED` | No hard fail, but a SOFT_REFER fails (active loans, depth, volatility) |
| `APPROVED` | All applicable rules pass |

`eligible` is true when verdict is not `DECLINED` (referred applications remain reviewable).

### Rules (codes)

| Code | Severity | Meaning |
|------|----------|---------|
| `MIN_NET_INFLOW` | HARD | Verified monthly inflow ≥ policy minimum |
| `ACCOUNT_TENURE` | HARD | Statement span ≥ min months |
| `REPAYMENT_CAPACITY` | HARD | Projected repayment / inflow ≤ max ratio |
| `FRAUD_ROUND_TRIP` | HARD | Same-counterparty round-trips (≥500 KES, 48h, ≥2 pairs) |
| `ACTIVE_LOANS` | SOFT | Active loan count ≤ max |
| `TRANSACTION_DEPTH` | SOFT | Enough transactions for assessment |
| `INFLOW_VOLATILITY` | SOFT | CV ≤ max, or salary/business pattern waiver |

### FlexVertex audit graph (core)

Demos always run with Iron Edition reachable on `localhost:10000` and `flexvertex.enabled: true`:

```text
Applicant --CreditEvaluation--> CreditApplication
Decision --evaluated--> CreditApplication
Finding --appliesTo--> CreditApplication
Finding --basedOn--> Rule --originatedFrom--> EmbeddingChunk (policy PDF)
```

APIs: `GET /api/v1/applications/{id}/reconstruct`, `GET /api/v1/report`, CLI `report`. Inspect schema `TTACS/Scorer/MpesaCredit` in Cartographer after evaluate.

---

## Repository layout

```text
mpesa-credit-scorer/
├── docs/                  # Synthetic credit policy PDF (sandbox)
├── samples/               # Synthetic CSV fixtures (no real customer PII)
│   └── fixtures/          # Header text for identity unit tests
├── scripts/               # run, FlexVertex sync/setup, demo tunnel
├── src/main/java/         # Core scorer + HTTP UI
├── src/flexvertex/java/   # Compiled only with -Pflexvertex
├── src/test/java/         # JUnit 5
├── policy.yml             # Local config (placeholders for secrets)
├── policy.example.yml     # Safe template
├── pom.xml
└── LICENSE                # AGPL-3.0
```

---

## Requirements

- **JDK 21+**
- **Maven 3.9+**
- **Docker + FlexVertex Iron Edition** — required for a full demo (audit graph)
- Optional: `cloudflared` for public scorer tunnels (FlexVertex stays local)

---

## Quick start

Full demo path: **start FlexVertex → sync libs → enable → serve**.

```bash
git clone https://github.com/billmalea/mpesa-credit-scorer.git
cd mpesa-credit-scorer

# 1. Config (no secrets in git — edit locally)
cp policy.example.yml policy.yml
# Set flexvertex.adminPassword / underwriterPassword to your local Iron Edition passwords
# (demos require flexvertex.enabled: true — already the default in policy.example.yml)

# 2. Start FlexVertex Iron + sync client JARs into lib/ (gitignored)
./scripts/setup-flexvertex.sh
# Or, if Iron is already up: ./scripts/sync-flexvertex-libs.sh

# 3. Score + serve with FlexVertex audit
mvn -Pflexvertex test
./scripts/run.sh evaluate --file samples/amina-strong-inflow.csv
./scripts/run.sh serve
# UI: http://localhost:8091  ·  Cartographer: http://localhost:8080
```

### CLI

```bash
./scripts/run.sh evaluate --file samples/amina-strong-inflow.csv \
  --requested 75000 --repayment 12500 --active-loans 0

./scripts/run.sh extract --file statement.pdf --password '...' --out samples/private
./scripts/run.sh policy
./scripts/run.sh report    # portfolio counts from FlexVertex audit graph
```

### HTTP API

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| GET | `/health` | Public | Liveness |
| GET | `/` | Optional Basic | UI |
| POST | `/api/v1/parse` | Optional Basic | Statement preview |
| POST | `/api/v1/evaluate` | Optional Basic | Full decision |
| GET | `/api/v1/applications/{id}/reconstruct` | Optional Basic | Audit trail (FlexVertex) |
| GET | `/api/v1/report` | Optional Basic | Portfolio counts (FlexVertex) |

Multipart evaluate fields: `statement`, `statementPassword`, `applicantName`, `msisdn`, `requestedAmountKes`, `projectedMonthlyRepaymentKes`, `activeLoanCount`, `applicationId`.

Demo Basic Auth (recommended for tunnels):

```bash
export SCORER_BASIC_AUTH='demo:choose-a-strong-password'
./scripts/serve-demo.sh          # or ./scripts/run.sh serve
./scripts/tunnel-cloudflare.sh   # refuses to tunnel without auth
```

---

## FlexVertex (core for demos)

1. Start Iron Edition (typically `localhost:8080` UI, `localhost:10000` client API).
2. From this repo:

```bash
./scripts/setup-flexvertex.sh
# or separately:
./scripts/sync-flexvertex-libs.sh   # copies client JARs into lib/ (gitignored)
```

3. In `policy.yml` keep `flexvertex.enabled: true` and set real admin/underwriter passwords **locally only** (must match your Iron Edition install).
4. `./scripts/run.sh serve` then evaluate; reconstruct via the UI / API and inspect Cartographer.

**Ops resilience:** if FlexVertex is down or passwords are wrong, the scorer falls back to in-memory `NoOpDecisionStore` and still returns score decisions. That path is for resilience only — **do not run demos that way**; reconstruct, report, and Cartographer provenance will be missing.

---

## Samples

Synthetic CSVs (safe to commit):

| File | Expected |
|------|----------|
| `samples/amina-strong-inflow.csv` | APPROVED (payroll) |
| `samples/collins-weak-inflow.csv` | DECLINED (low inflow) |
| `samples/grace-gig-worker.csv` | APPROVED (diversified gig) |
| `samples/naomi-volatile.csv` | REFERRED (volatility) |
| `samples/sam-round-trip.csv` | DECLINED (round-trip fraud) |

**Do not commit** real customer PDFs or `samples/extracted/` dumps. Those paths are gitignored. Private PDF regression tests skip unless the file exists and `MPESA_PDF_PASSWORD` is set.

---

## Security notes

- Never commit `.demo-credentials`, `DEMO_AUTH.txt`, `.env`, or real FlexVertex passwords.
- `policy.yml` in git uses `REPLACE_ME` placeholders; use `policy.local.yml` (gitignored) or local edits for secrets.
- Public tunnels must use `SCORER_BASIC_AUTH`; the tunnel script refuses unprotected UIs.
- FlexVertex ports `8080` / `10000` must **not** be tunneled for demos — only the scorer (`8091`).
- Upload size capped; API 500s scrub internal exception detail.

---

## Configuration reference

See [`policy.example.yml`](policy.example.yml):

- **product** — max loan, loan-to-inflow ratio  
- **eligibility** — thresholds for rules  
- **scoring.weights** — scorecard mix + round-trip penalty  
- **policy.pdfPath** — synthetic guidelines PDF for threshold overlay / graph chunks  
- **flexvertex** — audit graph connection (enabled by default for demos; set local Iron passwords)  
- **server.port** — HTTP listen port (default `8091`)

---

## Development

```bash
mvn test                 # unit tests (NoOp store; CI without Iron)
mvn -Pflexvertex test    # requires lib/ JARs from sync-flexvertex-libs.sh
mvn -Pflexvertex package
```

Key packages under `com.ttacs.scorer`:

- `ingest` — CSV / SMS / PDF parsers  
- `features` — feature engineering  
- `score` — rules + scorecard  
- `policy` — YAML load + PDF materialize  
- `api` — HTTP server + static UI + demo security  
- `flexvertex` — DecisionStore SPI + Iron client (profile)

---

## License

Copyright (C) 2026 TTACS Management Consulting

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

See [LICENSE](LICENSE) for the full AGPL-3.0 text. Network use of modified versions requires offering corresponding source to users (AGPL §13).

---

## Disclaimer

This software is a **sandbox / demonstration** underwriting toolkit. It is not a regulated credit bureau product. Synthetic policy PDFs and sample CSVs are for internal demos only. Do not process production customer data without appropriate legal, privacy, and security controls.
