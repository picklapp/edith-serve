# SAML Gateway — Provider Configuration Guide

How to add new Service Providers (SPs) and Identity Providers (IdPs) to the Edith platform without any Java code changes.

## Architecture Overview

```
                        Inbound (6 IdPs)                    Outbound (17 SPs)
                        ================                    =================

  Edith Bank (:5000)  ─┐                                 ┌─ Edith RDC (:4000)
  Jarvis Bank (:5001) ─┤                                 ├─ Edith ACH (:4001)
  Meridian Bank       ─┤   ┌──────────────────────┐      ├─ Edith Wire
  Summit Credit Union ─┼──>│  SAML Gateway (:6000) │──────├─ Edith BillPay
  Atlas Financial     ─┤   │  validates | signs    │      ├─ Edith Loans
  Pinnacle Bank       ─┘   │  routes  | logs       │      ├─ Edith Treasury
                            └─────────┬────────────┘      ├─ ... (17 total)
                                      │                   └─ Edith Reports
                              Core Backend (:9090)
                              config-driven registry
```

All SAML logic is centralized in **edith-core-backend** and proxied through **edith-saml-gateway**. Adding providers requires only configuration changes — no Java code modifications.

## Configuration Files

| File | Purpose |
|------|---------|
| `edith-core-backend/src/main/resources/application.properties` | Backend registry — defines which SPs can receive assertions and which IdPs are trusted |
| `edith-saml-gateway/config/providers.json` | Gateway dashboard — UI display, health checks, topology visualization |

Both files must be updated when adding a provider.

---

## Adding a New Service Provider (Outbound: Core -> SP)

Use this when edith-core needs to SSO users **into** a new downstream application.

### Step 1: Register the SP in the backend

Add two lines to `edith-core-backend/src/main/resources/application.properties`:

```properties
# Pattern: saml.sp.providers.<name>.entity-id=<SP entity ID>
#          saml.sp.providers.<name>.acs-url=<SP ACS URL>

saml.sp.providers.wire.entity-id=https://wire.edith.com/saml/metadata
saml.sp.providers.wire.acs-url=https://wire.edith.com/saml/acs
```

The `<name>` (e.g., `wire`) becomes the SP identifier used in SSO requests: `/saml/sso/wire`.

### Step 2: Register the SP in the gateway dashboard

Add an entry to the `spProviders` array in `edith-saml-gateway/config/providers.json`:

```json
{
  "id": "wire",
  "name": "Edith Wire",
  "fullName": "Wire Transfers",
  "entityId": "https://wire.edith.com/saml/metadata",
  "acsUrl": "https://wire.edith.com/saml/acs",
  "status": "active",
  "protocol": "SAML 2.0",
  "binding": "HTTP-POST",
  "description": "Domestic and international wire transfer service"
}
```

Add a corresponding route entry to the `routes` array:

```json
{
  "id": "route-core-wire",
  "name": "Core to Wire",
  "from": "edith-core",
  "to": "wire",
  "direction": "outbound",
  "description": "Core users SSO into Wire Transfers",
  "assertionFlow": "gateway signs assertion with core IdP key -> POST to Wire ACS"
}
```

### Step 3: Share the IdP certificate with the new SP

Provide `idp-cert.pem` (the Core IdP public certificate) to the SP team. They need it to verify the signature on SAML assertions.

```bash
# The cert lives here:
edith-core-backend/src/main/resources/saml/idp-cert.pem
```

### Step 4: Rebuild and restart

```bash
cd edith-core-backend && mvn clean package -DskipTests
# Then restart the backend and gateway
```

### Step 5: Update the Core UI (optional)

If users need a button on the Core dashboard to launch SSO, add a service card in `edith-core-ui/views/dashboard.ejs` and the display name in `edith-core-ui/server.js`:

```javascript
const SP_NAMES = { rdc: 'Edith RDC', ach: 'Edith ACH', wire: 'Edith Wire' };
```

---

## Adding a New Trusted Identity Provider (Inbound: IdP -> Core)

Use this when a new external organization (e.g., a partner bank) needs to SSO their users **into** edith-core.

### Step 1: Obtain the IdP's public certificate

Get the X.509 public certificate from the IdP team and place it in the backend resources:

```bash
# Place the certificate here:
edith-core-backend/src/main/resources/saml/meridian-idp-cert.pem
```

### Step 2: Register the trusted IdP in the backend

Add two lines to `edith-core-backend/src/main/resources/application.properties`:

```properties
# Pattern: saml.trusted-idps.providers.<name>.entity-id=<IdP entity ID>
#          saml.trusted-idps.providers.<name>.cert-path=classpath:saml/<cert-file>.pem

saml.trusted-idps.providers.meridian-bank.entity-id=https://sso.meridianbank.com/saml/metadata
saml.trusted-idps.providers.meridian-bank.cert-path=classpath:saml/meridian-idp-cert.pem
```

### Step 3: Register the IdP in the gateway dashboard

Add an entry to the `idpProviders` array in `edith-saml-gateway/config/providers.json`:

```json
{
  "id": "meridian-bank",
  "name": "Meridian Bank",
  "entityId": "https://sso.meridianbank.com/saml/metadata",
  "ssoUrl": "https://sso.meridianbank.com/saml/sso",
  "certFile": "meridian-idp-cert.pem",
  "status": "active",
  "protocol": "SAML 2.0",
  "binding": "HTTP-POST",
  "description": "Meridian Bank SSO — partner bank federation"
}
```

Add a corresponding route entry to the `routes` array:

```json
{
  "id": "route-meridian-core",
  "name": "Meridian SSO to Core",
  "from": "meridian-bank",
  "to": "edith-core",
  "direction": "inbound",
  "description": "Meridian Bank users SSO into Edith Core",
  "assertionFlow": "meridian-bank signs assertion -> gateway validates -> gateway creates core session"
}
```

### Step 4: Share your SP metadata with the new IdP

Provide the following to the IdP team:

| Item | Value |
|------|-------|
| **SP Entity ID** | `http://localhost:3000/saml/metadata` (or your production URL) |
| **SP ACS URL** | `http://localhost:6000/saml/acs` (gateway endpoint) |
| **Expected NameID format** | `urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress` |

### Step 5: Rebuild and restart

```bash
cd edith-core-backend && mvn clean package -DskipTests
# Then restart the backend and gateway
```

---

## Complete Example: Adding Both at Once

Scenario: **Meridian Bank** wants their users to SSO into edith-core, and those users should also be able to SSO into a new **Wire Transfer** service.

### application.properties additions

```properties
# New SP: Wire Transfers
saml.sp.providers.wire.entity-id=https://wire.edith.com/saml/metadata
saml.sp.providers.wire.acs-url=https://wire.edith.com/saml/acs

# New trusted IdP: Meridian Bank
saml.trusted-idps.providers.meridian-bank.entity-id=https://sso.meridianbank.com/saml/metadata
saml.trusted-idps.providers.meridian-bank.cert-path=classpath:saml/meridian-idp-cert.pem
```

### Certificate exchange

```
Meridian Bank gives you:  meridian-idp-cert.pem  ->  place in src/main/resources/saml/
You give Meridian Bank:   idp-cert.pem           ->  they don't need this (they are IdP only)
You give Wire team:       idp-cert.pem           ->  they verify your SAML signatures with this
```

### Result

```
Meridian Bank user logs in at meridianbank.com
  -> Meridian IdP signs SAML assertion
  -> Browser POSTs to gateway :6000/saml/acs
  -> Gateway validates signature using meridian-idp-cert.pem
  -> User lands on Core dashboard
  -> Clicks "Open Wire Transfers"
  -> Gateway generates SAML assertion signed with idp-key.pem
  -> Browser POSTs to wire.edith.com/saml/acs
  -> Wire service validates and creates session
  -> User is in — zero passwords entered at Core or Wire
```

---

## How the Dynamic Configuration Works

### Before (hardcoded — required Java changes)

```java
// SamlIdpService.java — had to add @Value fields per SP
@Value("${saml.sp.rdc.entity-id}")
private String rdcSpEntityId;

@Value("${saml.sp.rdc.acs-url}")
private String rdcAcsUrl;

// ... and register manually in init()
spConfigs.put("rdc", new SpConfig(rdcSpEntityId, rdcAcsUrl));
```

```java
// SamlSpService.java — had to add @Value fields per IdP
@Value("${saml.bank-idp.cert-path}")
private Resource bankIdpCertResource;

// ... and register manually in init()
trustedIdpCredentials.put(bankIdpEntityId, loadCertCredential(bankIdpCertResource));
```

### After (dynamic — properties only)

```java
// SamlSpProperties.java — reads any saml.sp.providers.* entries automatically
@ConfigurationProperties(prefix = "saml.sp")
public class SamlSpProperties {
    private Map<String, SpEntry> providers = new LinkedHashMap<>();
}

// SamlIdpProperties.java — reads any saml.trusted-idps.providers.* entries automatically
@ConfigurationProperties(prefix = "saml.trusted-idps")
public class SamlIdpProperties {
    private Map<String, IdpEntry> providers = new LinkedHashMap<>();
}
```

Spring Boot's `@ConfigurationProperties` binds the map dynamically. The services iterate over whatever entries exist — no per-provider code needed.

---

## Verifying Your Configuration

### REST endpoint

After starting the backend, query the registered providers:

```bash
curl http://localhost:9090/api/saml/providers | jq
```

Returns:

```json
{
  "serviceProviders": {
    "rdc": { "entityId": "http://localhost:4000/saml/metadata", "acsUrl": "http://localhost:4000/saml/acs" },
    "ach": { "entityId": "http://localhost:4001/saml/metadata", "acsUrl": "http://localhost:4001/saml/acs" },
    "wire": { "entityId": "https://wire.edith.com/saml/metadata", "acsUrl": "https://wire.edith.com/saml/acs" }
  },
  "trustedIdpIssuers": [
    "http://localhost:5000/saml/metadata",
    "http://localhost:5001/saml/metadata",
    "https://sso.meridianbank.com/saml/metadata"
  ],
  "idpEntityId": "http://localhost:3000/saml/metadata",
  "spEntityId": "http://localhost:3000/saml/metadata"
}
```

### Gateway dashboard

Open `http://localhost:6000` to see:

- **Dashboard** — stats and architecture overview
- **Providers** — all registered IdPs and SPs with metadata
- **Routes** — assertion routing paths (inbound and outbound)
- **Topology** — visual network map with health checks
- **Assertion Log** — live log of SAML traffic through the gateway

### Backend logs

On startup, the backend logs every registered provider:

```
INFO  Registered SP: rdc -> http://localhost:4000/saml/metadata
INFO  Registered SP: ach -> http://localhost:4001/saml/metadata
INFO  Registered SP: wire -> https://wire.edith.com/saml/metadata
INFO  Total SPs registered: 3
INFO  Trusted IdP: edith-bank -> http://localhost:5000/saml/metadata
INFO  Trusted IdP: jarvis-bank -> http://localhost:5001/saml/metadata
INFO  Trusted IdP: meridian-bank -> https://sso.meridianbank.com/saml/metadata
INFO  Total trusted IdPs: 3
```

---

## Quick Reference

### Add a new SP (outbound)

```
1. application.properties:  saml.sp.providers.<name>.entity-id=...
                             saml.sp.providers.<name>.acs-url=...
2. providers.json:          Add to spProviders[] and routes[]
3. Share idp-cert.pem with the SP team
4. mvn clean package -DskipTests && restart
```

### Add a new trusted IdP (inbound)

```
1. Place <name>-idp-cert.pem in src/main/resources/saml/
2. application.properties:  saml.trusted-idps.providers.<name>.entity-id=...
                             saml.trusted-idps.providers.<name>.cert-path=classpath:saml/<name>-idp-cert.pem
3. providers.json:          Add to idpProviders[] and routes[]
4. Give the IdP team your SP entity ID and ACS URL
5. mvn clean package -DskipTests && restart
```

### Key files

| File | What to edit |
|------|-------------|
| `edith-core-backend/src/main/resources/application.properties` | SP and IdP registrations (backend) |
| `edith-core-backend/src/main/resources/saml/*.pem` | Certificate files |
| `edith-saml-gateway/config/providers.json` | Dashboard UI entries |
| `edith-core-ui/server.js` | SP_NAMES map (optional, for dashboard buttons) |
| `edith-core-ui/views/dashboard.ejs` | Service cards (optional, for dashboard buttons) |
