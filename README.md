# Edith Platform — SAML SSO Demo

A multi-service platform demonstrating SAML 2.0 Single Sign-On (SSO) with an IdP-initiated flow. Users authenticate once in Edith Core and seamlessly access partner applications (RDC, ACH) without re-entering credentials.

## Architecture

```
┌──────────────────┐         ┌──────────────────────┐
│  edith-core-ui   │  REST   │  edith-core-backend   │
│  (Node.js :3000) │────────>│  (Spring Boot :9090)  │
│  Login, Dashboard │         │  Auth + SAML IdP      │
└────────┬─────────┘         └──────────┬────────────┘
         │                              │
         │  Renders auto-submit form    │  Generates signed
         │  with SAMLResponse           │  SAML assertions
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
| edith-core-ui | Node.js/Express | 3000 | IdP Frontend | Login page, dashboard with SSO links to partner apps |
| edith-core-backend | Spring Boot | 9090 | SAML IdP | User authentication, SAML assertion generation & signing |
| edith-rdc-ui | Node.js/Express | 4000 | SP Frontend | Remote deposit check upload interface |
| edith-rdc-backend | Spring Boot | 9091 | SAML SP | Validates SAML assertions, extracts user info |
| edith-ach-ui | Node.js/Express | 4001 | SP Frontend | ACH payment request interface |
| edith-ach-backend | Spring Boot | 9092 | SAML SP | Validates SAML assertions, extracts user info |

## SAML SSO Flow

### How it works (IdP-Initiated SSO)

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
| `idp-cert.pem` | edith-core (IdP) | Each SP backend | SP uses it to verify SAML assertion signatures |
| SP entity ID | Each SP | edith-core-backend config | IdP sets this as the `Audience` in the assertion |
| SP ACS URL | Each SP | edith-core-backend config | IdP sets this as the `Destination` — where the SAMLResponse is POSTed |

The IdP private key (`idp-key.pem`) **never** leaves edith-core-backend.

## Certificates

Self-signed X.509 certificates (RSA 2048-bit, 365 days) are used for signing and verification.

| File | Location | Purpose |
|------|----------|---------|
| `idp-cert.pem` | edith-core-ui/saml/, all backend resources | IdP public certificate for signature verification |
| `idp-key.pem` | edith-core-ui/saml/, edith-core-backend resources | IdP private key for signing assertions |
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

# Install Node.js dependencies
cd edith-core-ui && npm install && cd ..
cd edith-rdc-ui && npm install && cd ..
cd edith-ach-ui && npm install && cd ..
```

### Run

```bash
# Start all 6 services
./start-all.sh

# Stop all services
./stop-all.sh
```

### Test

1. Open http://localhost:3000
2. Login with `john` / `password123` (or `jane` / `password123`)
3. Click **Open RDC** to SSO into Remote Deposit Capture at :4000
4. Click **Open ACH** to SSO into ACH Payments at :4001
5. Both apps show the SSO badge and logged-in user without asking for credentials

### Logs

Service logs are written to the `logs/` directory:
- `core-backend.log`, `rdc-backend.log`, `ach-backend.log`
- `core-ui.log`, `rdc-ui.log`, `ach-ui.log`

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
