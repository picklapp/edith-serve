# Edith Platform — SAML SSO Demo

A multi-service platform demonstrating SAML 2.0 Single Sign-On (SSO) with IdP-initiated flows and chained federation. Supports two SSO entry points:

1. **Direct login** — Users authenticate at Edith Core and SSO into RDC/ACH
2. **Bank SSO** — Bank users authenticate at Edith Bank or Jarvis Bank, SSO into Edith Core, then chain-SSO into RDC/ACH

## Architecture

```
┌──────────────────┐         ┌──────────────────────┐
│  edith-bank-ui   │  REST   │  edith-bank-backend   │
│  (Node.js :5000) │────────>│  (Spring Boot :9093)  │
│  Bank Login      │         │  Auth + SAML IdP      │
└────────┬─────────┘         └──────────┬────────────┘
         │                              │
         │  "Open Edith Core"           │
         │  SAMLResponse via POST       │
         │                              │
┌────────┴─────────┐         ┌──────────┴────────────┐
│ jarvis-bank-ui   │  REST   │ jarvis-bank-backend    │
│ (Node.js :5001)  │────────>│ (Spring Boot :9094)    │
│ Jarvis Login     │         │ Auth + SAML IdP        │
└────────┬─────────┘         └──────────┬─────────────┘
         │                              │
         │  "Open Edith Core"           │
         │  SAMLResponse via POST       │
         │                              │
         ▼                              ▼
┌──────────────────┐         ┌──────────────────────┐
│  edith-core-ui   │  REST   │  edith-core-backend   │
│  (Node.js :3000) │────────>│  (Spring Boot :9090)  │
│  Login, Dashboard │         │  SAML IdP + SP        │
│  POST /saml/acs  │         │  Multi-IdP trust      │
└────────┬─────────┘         │  Generates RDC/ACH    │
         │                   └──────────┬────────────┘
         │  "Open RDC" / "Open ACH"     │
         │  SAMLResponse via POST       │
         │                              │
    ┌────┴──────────────────────────────┘
    │  HTTP-POST binding (SAMLResponse)
    │
    ├──────────────────────┐──────────────────────┐
    ▼                      ▼                      ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│ edith-rdc-ui │   │ edith-ach-ui │   │ future SPs   │
│ (Node :4000) │   │ (Node :4001) │   │              │
│ Check Upload │   │ ACH Payments │   │              │
└──────┬───────┘   └──────┬───────┘   └──────────────┘
       │                  │
       ▼                  ▼
┌──────────────┐   ┌──────────────┐
│edith-rdc-back│   │edith-ach-back│
│(Spring :9091)│   │(Spring :9092)│
│ SAML SP      │   │ SAML SP      │
└──────────────┘   └──────────────┘
```

## Projects

| Project | Type | Port | Role | Description |
|---------|------|------|------|-------------|
| edith-bank-ui | Node.js/Express | 5000 | IdP Frontend | Bank login, dashboard with SSO link to Edith Core |
| edith-bank-backend | Spring Boot | 9093 | SAML IdP | Bank user auth, SAML assertion generation for edith-core |
| jarvis-bank-ui | Node.js/Express | 5001 | IdP Frontend | Jarvis Bank login, dashboard with SSO link to Edith Core |
| jarvis-bank-backend | Spring Boot | 9094 | SAML IdP | Jarvis user auth, SAML assertion generation for edith-core |
| edith-core-ui | Node.js/Express | 3000 | IdP + SP Frontend | Login page, dashboard, receives SSO from banks |
| edith-core-backend | Spring Boot | 9090 | SAML IdP + SP | Dual role: IdP for RDC/ACH, SP for edith-bank + jarvis-bank |
| edith-rdc-ui | Node.js/Express | 4000 | SP Frontend | Remote deposit check upload interface |
| edith-rdc-backend | Spring Boot | 9091 | SAML SP | Validates SAML assertions, extracts user info |
| edith-ach-ui | Node.js/Express | 4001 | SP Frontend | ACH payment request interface |
| edith-ach-backend | Spring Boot | 9092 | SAML SP | Validates SAML assertions, extracts user info |

## SAML SSO Flows

### Flow 1: Chained SSO (Bank → Core → RDC/ACH)

```
1. Bank user logs in      2. Clicks "Open Edith Core"   3. Lands on Core dashboard
   at :5000                  SAMLResponse posted to         Clicks "Open RDC"
                             :3000/saml/acs                 SAMLResponse posted to
                                                            :4000/saml/acs

edith-bank ──SAML──> edith-core (SP validates) ──SAML──> edith-rdc (SP validates)
  (IdP)                (creates session)           (IdP)     (creates session)
  :5000/:9093          :3000/:9090                           :4000/:9091
```

This is a **3-hop chained federation**: the bank user never enters credentials at edith-core or edith-rdc.

### Flow 2: Direct login (Core → RDC/ACH)

```
1. User logs in          2. Clicks "Open RDC"      3. Browser auto-POSTs
   at :3000                 on dashboard               SAMLResponse to SP

┌──────┐  POST /login   ┌──────────┐             ┌──────────┐
│ User │───────────────> │core-ui   │             │core-ui   │
│      │  john/pass123   │          │             │          │
└──────┘                 └──────────┘             └────┬─────┘
                              │                        │
                              │ GET /saml/sso/rdc      │ POST to backend
                              ▼                        ▼
                         ┌──────────┐            ┌──────────┐
                         │core-back │            │core-back │
                         │          │            │ Signs    │
                         │ Validate │            │ SAML XML │
                         │ creds    │            │ with IdP │
                         └──────────┘            │ private  │
                                                 │ key      │
                                                 └────┬─────┘
                                                      │
                                              Base64 SAMLResponse
                                                      │
                                                      ▼
                                                 ┌──────────┐
                                          4.     │ rdc-ui   │
                                          POST   │ :4000    │
                                          /saml/ │ /saml/acs│
                                          acs    └────┬─────┘
                                                      │
                                              Forwards to backend
                                                      │
                                                      ▼
                                                 ┌──────────┐
                                          5.     │ rdc-back │
                                          Verify │ :9091    │
                                          sig    │ Validate │
                                          with   │ signature│
                                          IdP    │ + issuer │
                                          cert   │ + status │
                                                 └────┬─────┘
                                                      │
                                              Returns user info
                                                      │
                                                      ▼
                                          6. Session created,
                                             user sees upload page
```

### What gets shared between IdP and SP

| Artifact | From | To | Purpose |
|----------|------|----|---------|
| `idp-cert.pem` | edith-core (IdP) | RDC/ACH SP backends | SP uses it to verify SAML assertion signatures |
| `bank-idp-cert.pem` | edith-bank (IdP) | edith-core-backend | Core SP validates edith-bank's SAML assertions |
| `jarvis-idp-cert.pem` | jarvis-bank (IdP) | edith-core-backend | Core SP validates jarvis-bank's SAML assertions |
| SP entity ID | Each SP | Corresponding IdP config | IdP sets this as the `Audience` in the assertion |
| SP ACS URL | Each SP | Corresponding IdP config | IdP sets this as the `Destination` — where the SAMLResponse is POSTed |

Private keys **never** leave their respective backends:
- `idp-key.pem` stays in edith-core-backend
- `bank-idp-key.pem` stays in edith-bank-backend
- `jarvis-idp-key.pem` stays in jarvis-bank-backend

## Certificates

Self-signed X.509 certificates (RSA 2048-bit, 365 days) are used for signing and verification.

| File | Location | Purpose |
|------|----------|---------|
| `idp-cert.pem` | edith-core-ui/saml/, RDC/ACH backend resources | Core IdP public cert for signature verification |
| `idp-key.pem` | edith-core-ui/saml/, edith-core-backend resources | Core IdP private key for signing assertions |
| `bank-idp-cert.pem` | edith-bank-ui/saml/, edith-bank-backend resources, edith-core-backend resources | Edith Bank IdP public cert for signature verification |
| `bank-idp-key.pem` | edith-bank-ui/saml/, edith-bank-backend resources | Edith Bank IdP private key for signing assertions |
| `jarvis-idp-cert.pem` | jarvis-bank-ui/saml/, jarvis-bank-backend resources, edith-core-backend resources | Jarvis Bank IdP public cert for signature verification |
| `jarvis-idp-key.pem` | jarvis-bank-ui/saml/, jarvis-bank-backend resources | Jarvis Bank IdP private key for signing assertions |
| `sp-cert.pem` | edith-rdc-ui/saml/, edith-rdc-backend resources | RDC SP certificate |
| `sp-key.pem` | edith-rdc-ui/saml/ | RDC SP private key |
| `sp-cert.pem` | edith-ach-ui/saml/, edith-ach-backend resources | ACH SP certificate |
| `sp-key.pem` | edith-ach-ui/saml/ | ACH SP private key |

## SAML Assertion Structure

The signed XML assertion generated by edith-core-backend contains:

```xml
<saml2p:Response Destination="http://localhost:4000/saml/acs">
  <saml2:Issuer>http://localhost:3000/saml/metadata</saml2:Issuer>
  <saml2p:Status>
    <saml2p:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>
  </saml2p:Status>
  <saml2:Assertion>
    <saml2:Issuer>http://localhost:3000/saml/metadata</saml2:Issuer>
    <saml2:Subject>
      <saml2:NameID Format="email">john@example.com</saml2:NameID>
      <saml2:SubjectConfirmation Method="bearer">
        <saml2:SubjectConfirmationData Recipient="http://localhost:4000/saml/acs"/>
      </saml2:SubjectConfirmation>
    </saml2:Subject>
    <saml2:Conditions>
      <saml2:AudienceRestriction>
        <saml2:Audience>http://localhost:4000/saml/metadata</saml2:Audience>
      </saml2:AudienceRestriction>
    </saml2:Conditions>
    <saml2:AuthnStatement/>
    <saml2:AttributeStatement>
      <saml2:Attribute Name="email">john@example.com</saml2:Attribute>
      <saml2:Attribute Name="displayName">John Doe</saml2:Attribute>
    </saml2:AttributeStatement>
  </saml2:Assertion>
  <ds:Signature><!-- RSA-SHA256 --></ds:Signature>
</saml2p:Response>
```

SP backends validate: signature (using IdP cert), issuer, status, audience, and time conditions.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI Frontends | Node.js 18+, Express 4, EJS templates |
| Backends | Java 17, Spring Boot 3.4.3 |
| SAML Library | OpenSAML 4.3.2 (Shibboleth) |
| Signing | RSA-SHA256, X.509 certificates |
| Build | Maven (backends), npm (frontends) |

## Quick Start

### Prerequisites

- Java 17+
- Node.js 18+
- Maven 3.8+

### Build

```bash
# Build Spring Boot backends
cd edith-core-backend && mvn clean package -DskipTests && cd ..
cd edith-rdc-backend && mvn clean package -DskipTests && cd ..
cd edith-ach-backend && mvn clean package -DskipTests && cd ..
cd edith-bank-backend && mvn clean package -DskipTests && cd ..
cd jarvis-bank-backend && mvn clean package -DskipTests && cd ..

# Install Node.js dependencies
cd edith-core-ui && npm install && cd ..
cd edith-rdc-ui && npm install && cd ..
cd edith-ach-ui && npm install && cd ..
cd edith-bank-ui && npm install && cd ..
cd jarvis-bank-ui && npm install && cd ..
```

### Run

```bash
# Start all 10 services
./start-all.sh

# Stop all services
./stop-all.sh
```

### Test

**Direct login flow:**
1. Open http://localhost:3000
2. Login with `john` / `password123` (or `jane` / `password123`)
3. Click **Open RDC** to SSO into Remote Deposit Capture at :4000
4. Click **Open ACH** to SSO into ACH Payments at :4001

**Edith Bank SSO flow (chained federation):**
1. Open http://localhost:5000
2. Login with `bankuser1` / `password123` (or `bankuser2` / `password123`)
3. Click **Open Edith Core** — SSO into Core dashboard at :3000 (no login needed)
4. Click **Open RDC** or **Open ACH** — chain-SSO into the partner app (no login needed)

**Jarvis Bank SSO flow (chained federation):**
1. Open http://localhost:5001
2. Login with `jarvis1` / `password123` (or `jarvis2` / `password123`)
3. Click **Open Edith Core** — SSO into Core dashboard at :3000 (no login needed)
4. Click **Open RDC** or **Open ACH** — chain-SSO into the partner app (no login needed)

### Logs

Service logs are written to the `logs/` directory:
- `core-backend.log`, `rdc-backend.log`, `ach-backend.log`, `bank-backend.log`, `jarvis-backend.log`
- `core-ui.log`, `rdc-ui.log`, `ach-ui.log`, `bank-ui.log`, `jarvis-ui.log`

## Adding a New Service Provider

To add a new SP (e.g., `edith-wire` for wire transfers):

### 1. Register the SP in edith-core-backend

Add to `application.properties`:
```properties
saml.sp.wire.entity-id=http://localhost:4002/saml/metadata
saml.sp.wire.acs-url=http://localhost:4002/saml/acs
```

Add to `SamlIdpService.init()`:
```java
spConfigs.put("wire", new SpConfig(wireSpEntityId, wireAcsUrl));
```

### 2. Update edith-core-ui

Add a service card in `dashboard.ejs` and the display name in `server.js`:
```javascript
const SP_NAMES = { rdc: 'Edith RDC', ach: 'Edith ACH', wire: 'Edith Wire' };
```

### 3. Share the IdP certificate

Provide `idp-cert.pem` to the new SP team for signature verification.

## Architecture Decision: SAML Gateway

### Current setup (recommended for this scenario)

SAML IdP logic lives inside edith-core-backend. This is appropriate because:
- We own edith-core — the code is modern Spring Boot, easy to maintain
- Adding new SPs is config-only (no code changes needed)
- One less service to deploy and monitor

### When to introduce a separate SAML Gateway

A dedicated `edith-saml-gateway` microservice is recommended when:

| Scenario | Why Gateway Helps |
|----------|-------------------|
| **Legacy IdP app** (e.g., old Groovy/Grails) that can't be easily modified | Extracts vulnerable XML/crypto code into a modern, patchable service |
| **Multiple IdP applications** need to generate SAML assertions | Centralizes signing logic and cert management |
| **Strict security requirements** demand isolated cert storage | Private keys live only in the gateway, never in the legacy app |
| **You own both IdP and SP sides** | Eliminates duplicate SamlSpService across multiple SP backends |

If you don't own the SP applications (they manage their own SAML validation), and your IdP code is in a modern stack you control, the gateway adds operational overhead without meaningful benefit.

```
Gateway architecture (for legacy IdP scenario):

Legacy App ──REST──> edith-saml-gateway ──SAMLResponse──> SP apps
(auth only)          (signs assertions)                   (validate)
                     (manages certs)
                     (SP registry)
```

The gateway approach is specifically valuable for reducing vulnerabilities when the IdP application uses outdated SAML libraries (old XML parsers susceptible to XXE, weak signing algorithms, unpatched dependencies) that cannot be updated in place.
