# SAML Gateway Migration Plan

## Extract SAML Logic from edith-core into edith-saml-gateway

---

## 1. Context & Problem Statement

edith-core is a **legacy Groovy application** that we own but cannot easily modify or upgrade. It currently handles **all SAML processing** — both as an Identity Provider (generating signed assertions for RDC/ACH) and as a Service Provider (validating inbound assertions from edith-bank/jarvis-bank).

This means the legacy codebase contains:
- OpenSAML 4.3.2 library (XML parsing, signature generation/validation)
- RSA private keys for signing SAML assertions
- Trusted IdP certificates for signature verification
- Complex XML canonicalization and marshalling logic

**The proposal:** Extract all SAML logic into a new, dedicated `edith-saml-gateway` microservice, leaving edith-core as a pure authentication/business application with zero SAML dependencies.

---

## 2. Why We Must Create This Gateway

### 2.1 SAML Is the Highest-Risk Code in Our Stack

SAML processing is not ordinary business logic. It performs the most security-critical operations in any enterprise application:

| Operation | What Can Go Wrong |
|-----------|-------------------|
| **XML parsing** | XXE (XML External Entity) injection allows attackers to read arbitrary files from the server. Billion laughs attacks cause denial of service. |
| **Cryptographic signature verification** | A single implementation flaw in RSA-SHA256 or XML canonicalization lets an attacker forge assertions and impersonate any user. |
| **Private key handling** | The RSA private key used to sign assertions is the crown jewel — if compromised, every federated application trusts the attacker completely. |
| **Base64 decoding** | Malformed input can trigger parsing errors that bypass validation entirely, admitting unsigned or tampered assertions. |
| **Assertion lifetime checks** | Incorrect clock skew or expiry handling enables replay attacks — resubmitting a captured assertion hours or days later. |

**This code currently lives inside edith-core — a legacy Groovy application we cannot upgrade.** That is the core problem.

### 2.2 The Legacy Groovy Trap

edith-core is frozen. We own it, but we cannot safely modify it:

- **The JDK is pinned.** The Groovy runtime requires a specific JDK version. We cannot upgrade to Java 17+ for modern crypto defaults without breaking the entire application.
- **Dependencies are locked.** OpenSAML depends on BouncyCastle, Apache XML Security, and Xerces. These share version constraints with the Groovy framework. Upgrading any one of them risks cascading failures across the legacy app.
- **OWASP scans are failing.** Security scanners flag the outdated XML and crypto libraries, but fixing them requires dependency changes that the legacy app cannot absorb. This blocks the deployment pipeline for *all* changes — including critical business fixes.
- **No test coverage.** The legacy Groovy codebase has minimal automated tests. Any change to SAML handling requires extensive manual regression testing of the entire application.
- **The codebase is a single blast radius.** If an attacker exploits the XML parser (XXE), they don't just compromise SSO — they have access to the database, business data, internal APIs, and the private signing key, all in one process.

**We cannot patch SAML vulnerabilities without risking the entire legacy application. And we cannot ignore them — SAML is the front door to our federation.**

### 2.3 What Happens If We Do Nothing

These are not hypothetical risks. They are the documented consequences of running unpatched SAML code in a legacy runtime:

| Scenario | Attack Vector | Business Impact | Severity |
|----------|--------------|-----------------|----------|
| **XXE in XML parser** | Attacker sends crafted SAMLResponse with `<!DOCTYPE>` entity. Unpatched parser resolves it. | Server files exfiltrated (credentials, configs). SSRF into internal network. Full system compromise. | **Critical** |
| **SHA-1 signature bypass** | Legacy OpenSAML version falls back to SHA-1 when SHA-256 fails. Attacker precomputes collision. | Forged SAML assertions accepted. Attacker impersonates any user across all federated apps (RDC, ACH, future SPs). | **Critical** |
| **Signing key compromise** | Private key stored in legacy app's classpath alongside business code. Any file-read vulnerability exposes it. | Attacker signs valid assertions for any user, any SP. All federated trust is broken. Requires emergency key rotation across every partner. | **Critical** |
| **BouncyCastle CVE unpatched** | Known vulnerability in crypto library. Cannot upgrade because Groovy framework pins the version. | Cryptographic operations are weakened. Signature verification can be bypassed. Persists indefinitely. | **High** |
| **OWASP blocks deployment** | Dependency scanner fails on legacy XML/crypto libs. Pipeline refuses to deploy. | Cannot ship business-critical bug fixes or features. Revenue impact. Customer trust eroded. | **High** |
| **Groovy runtime EOL** | No security patches for the runtime itself. JDK version lacks modern TLS/crypto defaults. | The entire application becomes a known-vulnerable target. Compliance audits fail. | **High** |

### 2.4 The Gateway Solves Every One of These Problems

By extracting SAML into a dedicated microservice, we eliminate each risk:

**1. Isolate the vulnerability surface.**
All XML parsing, cryptographic signing, and certificate handling move to a modern Spring Boot 3.x service running on Java 17+. The legacy Groovy app never processes XML, never touches a private key, never loads OpenSAML. If the XML parser has a zero-day, only the gateway is exposed — not the database, not the business data.

**2. Patch independently, instantly.**
When a new OpenSAML CVE is published, we update the gateway's `pom.xml`, rebuild, and redeploy in minutes. No regression testing of the legacy Groovy app. No dependency conflicts. No pipeline blockage.

**3. Contain the private key.**
The RSA private key lives only in the gateway — a small, auditable service (~6 Java files) with no database, no business logic, no other attack surface. It's the smallest possible blast radius for our most sensitive secret.

**4. Unblock the deployment pipeline.**
edith-core-backend ships with zero OpenSAML dependencies. OWASP scans pass. Business fixes deploy without being blocked by SAML-related CVEs.

**5. Run a modern JDK.**
The gateway runs Java 17+ with current crypto providers, TLS 1.3, and secure defaults. The legacy app stays on its required JDK. They are completely decoupled.

**6. Centralize certificate management.**
All trusted IdP certificates and the signing keypair live in one directory (`resources/saml/`). Adding a new bank partner is a single config change. Rotating keys is a single redeployment. Security auditors review one small project, not the entire monolith.

**7. Scale SSO independently.**
SSO traffic spikes (e.g., bank onboarding events) scale the lightweight gateway horizontally, without scaling the heavyweight legacy application.

**8. Future-proof the federation.**
Migrate from OpenSAML 4.x to 5.x, add SAML SP-initiated flow, or integrate OIDC — all in the gateway. The legacy app is untouched. New SPs are added by config, not code.

### 2.5 What Doesn't Change (Zero User Impact)

This extraction is invisible to every user and partner:

- **The SSO experience is identical** — users see the same login pages, dashboards, and auto-redirect flows
- **The SAML protocol flow is unchanged** — HTTP-POST binding with auto-submit forms works exactly the same way
- **Bank partners are unaffected** — edith-bank and jarvis-bank still POST to `http://localhost:3000/saml/acs` (edith-core-ui proxies to gateway transparently)
- **edith-core-ui remains the front door** — it handles sessions, renders pages, and proxies SAML requests to the gateway via simple REST calls
- **No downtime required** — the gateway can be deployed alongside edith-core, routes switched one at a time

---

## 3. Current Architecture (Before Migration)

```
edith-bank (:5000)  ──SAML──┐
jarvis-bank (:5001) ──SAML──┤
                             ▼
                    ┌──────────────────────┐
                    │  edith-core-backend   │
                    │  (legacy Groovy :9090)│
                    │                      │
                    │  Auth + SAML IdP     │  <-- All SAML logic embedded
                    │  + SAML SP + Certs   │      in the legacy app
                    └──────────┬───────────┘
                               │
                  ┌────────────┼────────────┐
                  ▼            ▼            ▼
            edith-rdc    edith-ach    future SPs
            (:4000)      (:4001)
```

**What edith-core-backend currently does (SAML):**
- `SamlIdpService` — Generates signed SAML 2.0 assertions for RDC and ACH (IdP role)
- `SamlSpService` — Validates inbound SAML assertions from edith-bank and jarvis-bank (SP role)
- `SamlIdpController` — `POST /api/saml/sso` and `GET /api/saml/metadata`
- `SamlSpController` — `POST /api/saml/acs`
- Manages 4 PEM files: `idp-cert.pem`, `idp-key.pem`, `bank-idp-cert.pem`, `jarvis-idp-cert.pem`

**What edith-core-backend currently does (non-SAML, keeps):**
- `AuthController` — `POST /api/auth/login` (username/password authentication)

---

## 4. Target Architecture (After Migration)

```
edith-bank (:5000)  ──SAML──┐
jarvis-bank (:5001) ──SAML──┤
                             ▼
              ┌─────────────────────────┐        ┌──────────────────────┐
              │  edith-saml-gateway     │  REST  │  edith-core-backend   │
              │  (Spring Boot 3.x :9095)│<───────│  (legacy Groovy :9090)│
              │                         │        │                      │
              │  SAML IdP + SP          │        │  Auth only           │
              │  All certs consolidated │        │  Zero SAML deps      │
              │  Modern JDK 17+        │        │  No certs, no XML    │
              └────────┬────────────────┘        └──────────────────────┘
                       │                                   ▲
          ┌────────────┼────────────┐                      │
          ▼            ▼            ▼              edith-core-ui :3000
    edith-rdc    edith-ach    future SPs           (talks to both)
    (:4000)      (:4001)
```

**edith-core-ui (:3000)** talks to:
- Gateway (:9095) for all SAML operations (SSO initiation, ACS validation, metadata)
- Core-backend (:9090) for authentication only (`POST /api/auth/login`)

---

## 5. New Project: edith-saml-gateway

### 5.1 Project Structure

```
edith-saml-gateway/
├── pom.xml                              # Spring Boot 3.4.3 + OpenSAML 4.3.2
├── src/main/java/com/edith/gateway/
│   ├── EdithSamlGatewayApplication.java
│   ├── config/
│   │   └── WebConfig.java              # CORS for :3000, :5000, :5001
│   ├── controller/
│   │   ├── SamlIdpController.java      # POST /api/saml/sso, GET /api/saml/metadata
│   │   └── SamlSpController.java       # POST /api/saml/acs
│   ├── service/
│   │   ├── SamlIdpService.java         # Assertion generation + RSA-SHA256 signing
│   │   └── SamlSpService.java          # Multi-IdP signature validation
│   └── model/
│       ├── UserInfo.java               # Input for assertion generation
│       └── SamlUserInfo.java           # Output of assertion validation
├── src/main/resources/
│   ├── application.properties
│   └── saml/                           # ALL certificates consolidated here
│       ├── idp-cert.pem                # Gateway's signing certificate
│       ├── idp-key.pem                 # Gateway's private key (NEVER leaves this service)
│       ├── bank-idp-cert.pem           # Trusted: edith-bank public cert
│       └── jarvis-idp-cert.pem         # Trusted: jarvis-bank public cert
```

### 5.2 Gateway Configuration

```properties
server.port=9095
spring.application.name=edith-saml-gateway

# Gateway's own IdP signing identity
saml.idp.entity-id=http://localhost:9095/saml/metadata
saml.idp.cert-path=classpath:saml/idp-cert.pem
saml.idp.key-path=classpath:saml/idp-key.pem

# Registered downstream Service Providers
saml.sp.rdc.entity-id=http://localhost:4000/saml/metadata
saml.sp.rdc.acs-url=http://localhost:4000/saml/acs
saml.sp.ach.entity-id=http://localhost:4001/saml/metadata
saml.sp.ach.acs-url=http://localhost:4001/saml/acs

# Trusted upstream Identity Providers
saml.core-sp.entity-id=http://localhost:9095/saml/metadata
saml.bank-idp.entity-id=http://localhost:5000/saml/metadata
saml.bank-idp.cert-path=classpath:saml/bank-idp-cert.pem
saml.jarvis-idp.entity-id=http://localhost:5001/saml/metadata
saml.jarvis-idp.cert-path=classpath:saml/jarvis-idp-cert.pem
```

### 5.3 Gateway API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/saml/sso` | Accepts `{spName, email, displayName}`, returns `{samlResponse, acsUrl}` — generates signed SAML assertion |
| `POST` | `/api/saml/acs` | Accepts `{samlResponse}`, returns `{nameID, email, displayName, attributes}` — validates inbound SAML assertion |
| `GET` | `/api/saml/metadata` | Returns XML IdP metadata (EntityDescriptor) |

---

## 6. Migration: What Moves, What Stays, What Changes

### 6.1 Files Moving from edith-core-backend to Gateway

| Source (edith-core-backend) | Destination (gateway) | Changes Required |
|---|---|---|
| `service/SamlIdpService.java` | `service/SamlIdpService.java` | Package rename only |
| `service/SamlSpService.java` | `service/SamlSpService.java` | Package rename only |
| `controller/SamlIdpController.java` | `controller/SamlIdpController.java` | Package rename, update metadata Location URL to :9095 |
| `controller/SamlSpController.java` | `controller/SamlSpController.java` | Package rename only |
| `model/SamlUserInfo.java` | `model/SamlUserInfo.java` | Package rename only |
| `model/UserInfo.java` | `model/UserInfo.java` | Package rename only |
| All `saml.*` properties | `application.properties` | Update entity-ids from :3000 to :9095 |
| `resources/saml/*` (all PEM files) | `resources/saml/*` | Moved, not duplicated |

### 6.2 What Gets Deleted from edith-core-backend

| Item | Action |
|------|--------|
| `service/SamlIdpService.java` | Delete |
| `service/SamlSpService.java` | Delete |
| `controller/SamlIdpController.java` | Delete |
| `controller/SamlSpController.java` | Delete |
| `model/SamlUserInfo.java` | Delete |
| `resources/saml/` (entire directory) | Delete |
| All `saml.*` properties in `application.properties` | Remove |
| OpenSAML dependencies in `pom.xml` | Remove |
| Shibboleth Maven repository in `pom.xml` | Remove |
| CORS origins for `:5000` and `:5001` in `WebConfig.java` | Remove (simplify to `:3000` only) |

### 6.3 What Stays in edith-core-backend

| Item | Reason |
|------|--------|
| `AuthController.java` | Native username/password login — core business logic |
| `UserInfo.java` | Used by AuthController for login validation |
| `WebConfig.java` | Still needed for CORS (simplified to `:3000` only) |
| `application.properties` | Keep `server.port=9090` and `spring.application.name` only |

### 6.4 Changes to edith-core-ui (server.js)

```javascript
// BEFORE
const BACKEND_URL = 'http://localhost:9090';

// AFTER
const BACKEND_URL = 'http://localhost:9090';   // auth only
const GATEWAY_URL = 'http://localhost:9095';   // SAML operations
```

| Route | Currently Calls | After Migration Calls |
|-------|----------------|----------------------|
| `POST /login` | `BACKEND_URL/api/auth/login` | No change |
| `POST /saml/acs` | `BACKEND_URL/api/saml/acs` | `GATEWAY_URL/api/saml/acs` |
| `GET /saml/sso/:spName` | `BACKEND_URL/api/saml/sso` | `GATEWAY_URL/api/saml/sso` |
| `GET /saml/metadata` | `BACKEND_URL/api/saml/metadata` | `GATEWAY_URL/api/saml/metadata` |

### 6.5 Changes to SP Backends (edith-rdc-backend, edith-ach-backend)

Update the trusted IdP entity-id to point to the gateway:

```properties
# BEFORE
saml.idp.entity-id=http://localhost:3000/saml/metadata

# AFTER
saml.idp.entity-id=http://localhost:9095/saml/metadata
```

The signing certificate (`idp-cert.pem`) stays the same — same keypair, now owned by the gateway.

### 6.6 Changes to Bank Backends — None Required

edith-bank-backend and jarvis-bank-backend require **no changes**. They POST SAMLResponse to `http://localhost:3000/saml/acs` (edith-core-ui), which transparently proxies to the gateway. Bank backends are completely unaware of the gateway's existence.

### 6.7 Changes to generate-certs.sh

- Distribute Core IdP cert/key to `edith-saml-gateway/src/main/resources/saml/` (was edith-core-backend)
- Distribute bank/jarvis IdP certs to `edith-saml-gateway/src/main/resources/saml/` (was edith-core-backend)
- Stop distributing any certs to `edith-core-backend/` (no longer needs them)

### 6.8 Changes to start-all.sh / stop-all.sh

- Add port `9095` to the port list
- Add `java -jar edith-saml-gateway/target/edith-saml-gateway-1.0.0.jar` startup command
- Update summary output (11 services total)

---

## 7. Implementation Steps

| Step | Description | Projects Affected |
|------|-------------|-------------------|
| 1 | Create edith-saml-gateway project (pom.xml, application class, all Java files — copy from edith-core-backend, package rename) | New: edith-saml-gateway |
| 2 | Strip all SAML from edith-core-backend (delete files, remove OpenSAML deps, clean properties) | edith-core-backend |
| 3 | Update edith-core-ui/server.js to use `GATEWAY_URL` for SAML routes | edith-core-ui |
| 4 | Simplify edith-core-backend WebConfig (CORS only for `:3000`) | edith-core-backend |
| 5 | Update edith-rdc-backend and edith-ach-backend `saml.idp.entity-id` to `:9095` | edith-rdc-backend, edith-ach-backend |
| 6 | Update generate-certs.sh (distribute to gateway, not core-backend) | generate-certs.sh |
| 7 | Update start-all.sh and stop-all.sh (add port 9095) | start-all.sh, stop-all.sh |
| 8 | Build all changed projects | All backends |
| 9 | Update README.md with gateway architecture | README.md |
| 10 | Commit and push | Git |

---

## 8. Verification Checklist

- [ ] `./generate-certs.sh` — certs distributed to gateway, not core-backend
- [ ] `mvn clean package` on edith-core-backend succeeds with **zero** OpenSAML dependencies
- [ ] `mvn clean package` on edith-saml-gateway succeeds
- [ ] `./start-all.sh` — 11 services start successfully
- [ ] **Direct login test**: http://localhost:3000 → john/password123 → dashboard → Open RDC → SSO works (gateway generates and signs assertion)
- [ ] **Edith Bank SSO test**: http://localhost:5000 → bankuser1/password123 → Open Edith Core → lands on core dashboard → Open ACH → SSO works (gateway validates bank assertion, then generates ACH assertion)
- [ ] **Jarvis Bank SSO test**: http://localhost:5001 → jarvis1/password123 → same chained SSO flow
- [ ] Confirm: edith-core-backend has zero `import org.opensaml` references
- [ ] Confirm: edith-core-backend `resources/saml/` directory does not exist
- [ ] Confirm: edith-core-backend `pom.xml` has no `opensaml` dependencies

---

## 9. Post-Migration Service Map

| Service | Port | Role | SAML Involvement |
|---------|------|------|-----------------|
| edith-saml-gateway | 9095 | SAML Gateway | **All SAML logic** — IdP signing, SP validation, cert management |
| edith-core-backend | 9090 | Auth Service | **None** — pure username/password auth |
| edith-core-ui | 3000 | Core Frontend | Proxies SAML to gateway, auth to core-backend |
| edith-bank-backend | 9093 | Bank IdP | Generates SAML assertions (unchanged) |
| edith-bank-ui | 5000 | Bank Frontend | Unchanged |
| jarvis-bank-backend | 9094 | Jarvis IdP | Generates SAML assertions (unchanged) |
| jarvis-bank-ui | 5001 | Jarvis Frontend | Unchanged |
| edith-rdc-backend | 9091 | RDC SP | Validates assertions (updated entity-id only) |
| edith-rdc-ui | 4000 | RDC Frontend | Unchanged |
| edith-ach-backend | 9092 | ACH SP | Validates assertions (updated entity-id only) |
| edith-ach-ui | 4001 | ACH Frontend | Unchanged |
