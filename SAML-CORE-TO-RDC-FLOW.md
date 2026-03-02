# SAML SSO Flow: edith-core to edith-rdc

## How Communication Happens at the Library Level

This document traces the complete SAML 2.0 IdP-initiated SSO flow from edith-core (Identity Provider) to edith-rdc (Service Provider), showing exactly which libraries, classes, and methods are involved at each step.

---

## The 8-Step Flow

```
┌─────────┐    ┌──────────────┐    ┌───────────────────┐    ┌──────────────┐    ┌───────────────────┐    ┌─────────┐
│ Browser  │───>│ edith-core-ui│───>│edith-core-backend │    │ edith-rdc-ui │───>│ edith-rdc-backend │    │ Browser │
│ (User)   │    │ Node :3000   │    │ Spring Boot :9090 │    │ Node :4000   │    │ Spring Boot :9091 │    │ (User)  │
└─────────┘    └──────────────┘    └───────────────────┘    └──────────────┘    └───────────────────┘    └─────────┘
     │                │                      │                      │                      │                   │
     │  1. Click      │                      │                      │                      │                   │
     │  "Open RDC"    │                      │                      │                      │                   │
     │───────────────>│                      │                      │                      │                   │
     │                │  2. POST /api/saml/sso                      │                      │                   │
     │                │  {spName,email,name}  │                      │                      │                   │
     │                │─────────────────────>│                      │                      │                   │
     │                │                      │  3. Build SAML XML   │                      │                   │
     │                │                      │     Sign with RSA    │                      │                   │
     │                │                      │     Base64 encode    │                      │                   │
     │                │  {samlResponse,acsUrl}│                      │                      │                   │
     │                │<─────────────────────│                      │                      │                   │
     │  4. HTML form  │                      │                      │                      │                   │
     │  auto-submit   │                      │                      │                      │                   │
     │<───────────────│                      │                      │                      │                   │
     │                                                              │                      │                   │
     │  5. POST http://localhost:4000/saml/acs                      │                      │                   │
     │     Body: SAMLResponse=<base64>                              │                      │                   │
     │─────────────────────────────────────────────────────────────>│                      │                   │
     │                                                              │  6. POST /api/saml/acs                   │
     │                                                              │  {samlResponse}       │                   │
     │                                                              │─────────────────────>│                   │
     │                                                              │                      │  7. Base64 decode │
     │                                                              │                      │     Parse XML     │
     │                                                              │                      │     Verify sig    │
     │                                                              │                      │     Extract user  │
     │                                                              │  {nameID,email,...}   │                   │
     │                                                              │<─────────────────────│                   │
     │                                                              │  8. Set session       │                   │
     │  302 Redirect to /upload                                     │     Redirect          │                   │
     │<─────────────────────────────────────────────────────────────│                      │                   │
     │                                                                                                         │
     │  User is now authenticated on edith-rdc                                                                 │
```

---

## Step 1 — User Clicks "Open RDC" (Browser)

**File:** `edith-core-ui/views/dashboard.ejs`

The user is already logged into edith-core (via username/password). The dashboard shows a service card with a plain HTML link:

```html
<a href="/saml/sso/rdc" class="sso-btn">Open RDC</a>
```

This triggers a `GET http://localhost:3000/saml/sso/rdc` to edith-core-ui.

**Libraries involved:** None — this is a standard browser navigation.

---

## Step 2 — edith-core-ui Requests a SAML Assertion (Node.js)

**File:** `edith-core-ui/server.js` — `GET /saml/sso/:spName` route

edith-core-ui reads the authenticated user from the Express session and calls the Spring Boot backend:

```javascript
const response = await fetch(`${BACKEND_URL}/api/saml/sso`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
        email: req.session.user.email,          // "john@edith.com"
        displayName: req.session.user.displayName,  // "John Doe"
        spName: req.params.spName               // "rdc"
    })
});
const data = await response.json();
// data = { samlResponse: "<base64>", acsUrl: "http://localhost:4000/saml/acs" }
```

**Libraries involved:**
| Library | Purpose |
|---------|---------|
| `express` (Node.js) | HTTP routing, session middleware |
| `express-session` (Node.js) | `req.session.user` — stores the logged-in user |
| `node-fetch` / built-in `fetch` (Node.js) | HTTP POST to Spring Boot backend |

**What this step does:** Translates the browser click into a REST call, passing the user's identity and the target SP name to the backend. The Node.js layer has no SAML knowledge — it just proxies.

---

## Step 3 — edith-core-backend Builds and Signs the SAML Response (Java/OpenSAML)

This is the most complex step. The Spring Boot backend constructs the entire SAML 2.0 XML document, cryptographically signs it, and returns it as a Base64 string.

### 3a. Controller Entry Point

**File:** `edith-core-backend/.../controller/SamlIdpController.java` — `POST /api/saml/sso`

```java
@PostMapping("/sso")
public ResponseEntity<?> generateSsoResponse(@RequestBody Map<String, String> payload) {
    String spName = payload.get("spName");       // "rdc"
    String email = payload.get("email");          // "john@edith.com"
    String displayName = payload.get("displayName"); // "John Doe"

    UserInfo user = new UserInfo(email, displayName, email);
    Map<String, String> result = samlIdpService.generateSamlResponse(user, spName);
    return ResponseEntity.ok(result);
}
```

### 3b. OpenSAML Initialization

**File:** `edith-core-backend/.../service/SamlIdpService.java` — `@PostConstruct init()`

```java
InitializationService.initialize();
```

**Library:** `org.opensaml.core.config.InitializationService`

This single call bootstraps the entire OpenSAML 4.x framework:
- Registers all XML object providers (builders, marshallers, unmarshallers) for every SAML 2.0 type
- Initializes the `XMLObjectProviderRegistrySupport` singleton
- Sets up the security configuration for signature algorithms
- Must be called once before any OpenSAML operation

### 3c. Loading the Signing Credential (Private Key + Certificate)

**File:** `edith-core-backend/.../service/SamlIdpService.java` — `loadCredential()`

The IdP needs its **private key** (to sign) and its **certificate** (to include the public key):

```java
// Load the X.509 certificate
CertificateFactory cf = CertificateFactory.getInstance("X.509");
X509Certificate cert = (X509Certificate) cf.generateCertificate(certStream);

// Load the RSA private key from PEM
String keyPem = new String(keyResource.getInputStream().readAllBytes());
keyPem = keyPem.replace("-----BEGIN PRIVATE KEY-----", "")
               .replace("-----END PRIVATE KEY-----", "")
               .replaceAll("\\s", "");
byte[] keyBytes = Base64.getDecoder().decode(keyPem);
PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivateKey(keySpec);

// Bundle into an OpenSAML credential
BasicX509Credential credential = new BasicX509Credential(cert, privateKey);
```

**Libraries involved:**
| Library | Class | Purpose |
|---------|-------|---------|
| `java.security` (JDK) | `CertificateFactory` | Parses X.509 certificate from PEM/DER |
| `java.security` (JDK) | `KeyFactory` | Reconstructs RSA private key from PKCS#8 bytes |
| `java.security.spec` (JDK) | `PKCS8EncodedKeySpec` | Wraps raw key bytes in PKCS#8 format |
| `java.util.Base64` (JDK) | `Base64.getDecoder()` | Decodes PEM Base64 content to raw bytes |
| `org.opensaml.security.x509` (OpenSAML) | `BasicX509Credential` | Bundles cert + private key into OpenSAML's credential interface |

**PEM files loaded:**
- `classpath:saml/idp-cert.pem` — the IdP's X.509 certificate (RSA 2048-bit, self-signed)
- `classpath:saml/idp-key.pem` — the IdP's RSA private key (PKCS#8 format)

### 3d. SP Lookup

The service maintains a registry of known Service Providers:

```java
Map<String, SpConfig> spRegistry = new HashMap<>();
spRegistry.put("rdc", new SpConfig(rdcEntityId, rdcAcsUrl));
// rdcEntityId = "http://localhost:4000/saml/metadata"
// rdcAcsUrl   = "http://localhost:4000/saml/acs"
```

When `spName = "rdc"`, it resolves to:
- **Entity ID:** `http://localhost:4000/saml/metadata` (used in `<Audience>`)
- **ACS URL:** `http://localhost:4000/saml/acs` (used in `Destination` and `Recipient`)

### 3e. Building the SAML 2.0 Response XML (OpenSAML Builders)

**File:** `edith-core-backend/.../service/SamlIdpService.java` — `buildResponse()`

OpenSAML uses the **Builder Pattern** — every SAML XML element has a corresponding builder class from `org.opensaml.saml.saml2.core.impl`:

```
Response                        ← ResponseBuilder
├── Issuer                      ← IssuerBuilder
│   └── value = "http://localhost:3000/saml/metadata"
├── Status                      ← StatusBuilder
│   └── StatusCode              ← StatusCodeBuilder
│       └── value = SUCCESS ("urn:oasis:names:tc:SAML:2.0:status:Success")
└── Assertion                   ← AssertionBuilder
    ├── Issuer                  ← IssuerBuilder
    │   └── value = "http://localhost:3000/saml/metadata"
    ├── Subject                 ← SubjectBuilder
    │   ├── NameID              ← NameIDBuilder
    │   │   ├── format = EMAIL ("urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress")
    │   │   └── value = "john@edith.com"
    │   └── SubjectConfirmation         ← SubjectConfirmationBuilder
    │       ├── method = BEARER ("urn:oasis:names:tc:SAML:2.0:cm:bearer")
    │       └── SubjectConfirmationData ← SubjectConfirmationDataBuilder
    │           ├── Recipient = "http://localhost:4000/saml/acs"
    │           └── NotOnOrAfter = now + 300 seconds
    ├── Conditions                      ← ConditionsBuilder
    │   ├── NotBefore = now
    │   ├── NotOnOrAfter = now + 300 seconds
    │   └── AudienceRestriction         ← AudienceRestrictionBuilder
    │       └── Audience                ← AudienceBuilder
    │           └── URI = "http://localhost:4000/saml/metadata"
    ├── AuthnStatement                  ← AuthnStatementBuilder
    │   └── AuthnContext                ← AuthnContextBuilder
    │       └── AuthnContextClassRef    ← AuthnContextClassRefBuilder
    │           └── URI = PASSWORD_AUTHN_CTX
    │               ("urn:oasis:names:tc:SAML:2.0:ac:classes:Password")
    └── AttributeStatement             ← AttributeStatementBuilder
        ├── Attribute (name="email")
        │   └── AttributeValue          ← XSStringBuilder
        │       └── value = "john@edith.com"
        └── Attribute (name="displayName")
            └── AttributeValue          ← XSStringBuilder
                └── value = "John Doe"
```

**Libraries involved:**
| Library | Classes | Purpose |
|---------|---------|---------|
| `org.opensaml.saml.saml2.core.impl` (OpenSAML) | `ResponseBuilder`, `AssertionBuilder`, `IssuerBuilder`, `SubjectBuilder`, `NameIDBuilder`, `SubjectConfirmationBuilder`, `SubjectConfirmationDataBuilder`, `ConditionsBuilder`, `AudienceRestrictionBuilder`, `AudienceBuilder`, `AuthnStatementBuilder`, `AuthnContextBuilder`, `AuthnContextClassRefBuilder`, `StatusBuilder`, `StatusCodeBuilder`, `AttributeStatementBuilder`, `AttributeBuilder` | Construct each SAML XML element as a typed Java object |
| `org.opensaml.saml.common` (OpenSAML) | `SAMLVersion` | Sets SAML version to 2.0 |
| `org.opensaml.saml.saml2.core` (OpenSAML) | `NameIDType`, `SubjectConfirmation`, `StatusCode`, `AuthnContext` | Constants for SAML URIs (EMAIL format, BEARER method, SUCCESS status, PASSWORD context) |
| `org.opensaml.core.xml.schema.impl` (OpenSAML) | `XSStringBuilder` | Builds typed `xs:string` attribute values |
| `org.opensaml.core.xml.config` (OpenSAML) | `XMLObjectProviderRegistrySupport` | Global registry for retrieving builders, marshallers, and unmarshallers |
| `java.util` (JDK) | `UUID` | Generates random IDs for Response and Assertion (`_` + UUID) |
| `java.time` (JDK) | `Instant` | Generates `IssueInstant`, `NotBefore`, `NotOnOrAfter` timestamps |

### 3f. Creating the XML Digital Signature

```java
Signature signature = new SignatureBuilder().buildObject();
signature.setSigningCredential(credential);           // RSA private key + cert
signature.setSignatureAlgorithm(
    SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256);  // http://www.w3.org/2001/04/xmldsig-more#rsa-sha256
signature.setCanonicalizationAlgorithm(
    SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS); // http://www.w3.org/2001/10/xml-exc-c14n#
response.setSignature(signature);
```

**Libraries involved:**
| Library | Class | Purpose |
|---------|-------|---------|
| `org.opensaml.xmlsec.signature.impl` (OpenSAML) | `SignatureBuilder` | Creates the `<ds:Signature>` element |
| `org.opensaml.xmlsec.signature.support` (OpenSAML) | `SignatureConstants` | Constants for `RSA-SHA256` algorithm URI and `Exclusive C14N` canonicalization URI |

**What the algorithms mean:**
- **RSA-SHA256**: Hash the canonicalized XML with SHA-256, then encrypt the hash with the RSA private key. This produces the `<ds:SignatureValue>`.
- **Exclusive C14N (omit comments)**: Before hashing, normalize the XML to a canonical form — sorted attributes, normalized whitespace, resolved namespaces. This ensures the same logical XML always produces the same hash, regardless of formatting differences.

### 3g. Marshalling, Signing, and Serializing

```java
// 1. Marshall: Convert OpenSAML objects → DOM Element
Marshaller marshaller = XMLObjectProviderRegistrySupport
    .getMarshallerFactory()
    .getMarshaller(response);
Element element = marshaller.marshall(response);

// 2. Sign: Compute RSA-SHA256 signature and write into the DOM
Signer.signObject(signature);

// 3. Serialize: DOM Element → XML string
String xmlString = SerializeSupport.nodeToString(element);

// 4. Encode: XML string → Base64
String base64Response = Base64.getEncoder().encodeToString(
    xmlString.getBytes("UTF-8"));
```

**Libraries involved:**
| Library | Class | Purpose |
|---------|-------|---------|
| `org.opensaml.core.xml.io` (OpenSAML) | `Marshaller` | Converts the in-memory OpenSAML object tree into a W3C DOM `Element` |
| `org.opensaml.xmlsec.signature.support` (OpenSAML) | `Signer` | `signObject()` — performs the actual RSA-SHA256 computation. Canonicalizes the `<ds:SignedInfo>`, hashes it, signs with the private key, writes `<ds:SignatureValue>` and `<ds:Reference>` into the DOM |
| `net.shibboleth.utilities.java.support.xml` (Shibboleth) | `SerializeSupport` | `nodeToString()` — serializes the signed DOM tree back to an XML string |
| `java.util.Base64` (JDK) | `Base64.getEncoder()` | Encodes the XML string to Base64 for transport |

**The output** returned to the controller:
```json
{
    "samlResponse": "PHNhbWwycDpSZXNwb25zZS...<base64 of signed XML>...",
    "acsUrl": "http://localhost:4000/saml/acs"
}
```

---

## Step 4 — edith-core-ui Renders the HTTP-POST Binding Page (Browser)

**File:** `edith-core-ui/server.js`

```javascript
res.render('sso-post', {
    acsUrl: data.acsUrl,           // "http://localhost:4000/saml/acs"
    samlResponse: data.samlResponse, // Base64-encoded signed XML
    serviceName: 'Edith RDC'
});
```

**File:** `edith-core-ui/views/sso-post.ejs`

The server renders an HTML page with a hidden form and an auto-submit script:

```html
<form id="samlForm" method="POST" action="http://localhost:4000/saml/acs" style="display:none;">
    <input type="hidden" name="SAMLResponse" value="PHNhbWwycDpSZXNwb25zZS..." />
</form>
<script>
    document.getElementById('samlForm').submit();
</script>
```

**Libraries involved:**
| Library | Purpose |
|---------|---------|
| `ejs` (Node.js) | Server-side HTML template engine — injects `acsUrl` and `samlResponse` into the HTML |
| `express` (Node.js) | `res.render()` — renders the EJS template and sends the HTML response |

**What happens in the browser:**
1. The browser receives the HTML page
2. The page displays a spinner ("Signing you into Edith RDC...")
3. JavaScript immediately calls `document.getElementById('samlForm').submit()`
4. The browser performs a standard `POST` to `http://localhost:4000/saml/acs` with `Content-Type: application/x-www-form-urlencoded`
5. The form field `SAMLResponse=<base64>` is sent in the POST body

This is the **SAML 2.0 HTTP-POST Binding** (section 3.5 of the SAML Bindings spec). The browser acts as a dumb transport — it carries the signed assertion from the IdP's domain (`localhost:3000`) to the SP's domain (`localhost:4000`) via a client-side POST. The browser never reads, modifies, or validates the assertion.

---

## Step 5 — Browser POSTs the SAMLResponse to edith-rdc-ui

```
POST http://localhost:4000/saml/acs
Content-Type: application/x-www-form-urlencoded

SAMLResponse=PHNhbWwycDpSZXNwb25zZS...
```

This is a standard browser form submission. No libraries are involved — the browser's built-in form handling does the work.

---

## Step 6 — edith-rdc-ui Forwards the Assertion to Its Backend (Node.js)

**File:** `edith-rdc-ui/server.js` — `POST /saml/acs`

```javascript
app.post('/saml/acs', async (req, res) => {
    const samlResponse = req.body.SAMLResponse;

    const response = await fetch(`${BACKEND_URL}/api/saml/acs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ samlResponse })
    });

    const data = await response.json();
    // data = { nameID, email, displayName, attributes }
    ...
});
```

**Libraries involved:**
| Library | Purpose |
|---------|---------|
| `express` (Node.js) | HTTP routing, receives the form POST |
| `body-parser` / `express.urlencoded()` (Node.js) | Parses `application/x-www-form-urlencoded` body → `req.body.SAMLResponse` |
| `node-fetch` / built-in `fetch` (Node.js) | HTTP POST to Spring Boot backend (converts form field to JSON) |

**What this step does:** The Node.js layer extracts the raw Base64 string from the form body and forwards it as JSON to the Spring Boot backend for cryptographic validation. Like the IdP's Node layer, it has zero SAML knowledge.

---

## Step 7 — edith-rdc-backend Validates the SAML Response (Java/OpenSAML)

This is the SP-side counterpart to Step 3 — it reverses the process: decode, parse, verify signature, extract user identity.

### 7a. Controller Entry Point

**File:** `edith-rdc-backend/.../controller/SamlSpController.java` — `POST /api/saml/acs`

```java
@PostMapping("/acs")
public ResponseEntity<?> assertionConsumerService(@RequestBody Map<String, String> payload) {
    String samlResponse = payload.get("samlResponse");
    SamlUserInfo userInfo = samlSpService.validateAndParse(samlResponse);
    return ResponseEntity.ok(Map.of(
        "nameID", userInfo.getNameID(),
        "email", userInfo.getEmail(),
        "displayName", userInfo.getDisplayName(),
        "attributes", userInfo.getAttributes()
    ));
}
```

### 7b. Loading the Verification Credential (Public Certificate Only)

**File:** `edith-rdc-backend/.../service/SamlSpService.java` — `@PostConstruct init()`

The SP only needs the IdP's **public certificate** (no private key):

```java
CertificateFactory cf = CertificateFactory.getInstance("X.509");
X509Certificate cert = (X509Certificate) cf.generateCertificate(certStream);
BasicX509Credential idpCredential = new BasicX509Credential(cert);  // public-only
```

**PEM file loaded:**
- `classpath:saml/idp-cert.pem` — the same certificate that the IdP used for signing. Distributed during `generate-certs.sh`.

### 7c. Initializing the XML Parser Pool

```java
BasicParserPool parserPool = new BasicParserPool();
parserPool.setNamespaceAware(true);
parserPool.initialize();
```

**Library:** `net.shibboleth.utilities.java.support.xml.BasicParserPool`

This creates a **thread-safe pool of namespace-aware XML parsers**. Namespace awareness is critical — SAML XML uses multiple namespaces (`saml2p:`, `saml2:`, `ds:`, `xs:`) and the parser must resolve them correctly for signature validation.

### 7d. The Validation Pipeline

**File:** `edith-rdc-backend/.../service/SamlSpService.java` — `validateAndParse()`

```
Input: "PHNhbWwycDpSZXNwb25zZS..." (Base64 string)

Step 7d-1: Base64 Decode
    Library: java.util.Base64
    Base64.getDecoder().decode(samlResponseBase64) → byte[] rawXml

Step 7d-2: XML Parse
    Library: net.shibboleth.utilities.java.support.xml.BasicParserPool
    parserPool.parse(new ByteArrayInputStream(rawXml)) → org.w3c.dom.Document
    Document.getDocumentElement() → org.w3c.dom.Element (the <saml2p:Response> root)

Step 7d-3: Unmarshal XML → OpenSAML Objects
    Library: org.opensaml.core.xml.io.UnmarshallerFactory
    XMLObjectProviderRegistrySupport.getUnmarshallerFactory()
        .getUnmarshaller(element)
        .unmarshall(element) → org.opensaml.saml.saml2.core.Response

    This reverses the marshalling from Step 3g:
    - DOM Element → typed Response object
    - Child elements → nested Assertion, Subject, NameID, etc.

Step 7d-4: Signature Verification  ★ THE CRITICAL SECURITY CHECK ★
    Library: org.opensaml.xmlsec.signature.support.SignatureValidator
    Signature signature = response.getSignature();
    SignatureValidator.validate(signature, idpCredential);

    What happens inside:
    1. Reads the <ds:SignatureMethod> → confirms RSA-SHA256
    2. Reads the <ds:CanonicalizationMethod> → confirms Exclusive C14N
    3. Re-canonicalizes the signed XML content using the same C14N algorithm
    4. Computes SHA-256 hash of the canonical XML
    5. Decrypts the <ds:SignatureValue> using the IdP's public key (from idp-cert.pem)
    6. Compares the computed hash with the decrypted hash
    7. If they match → the XML has not been tampered with, and was signed by the IdP
    8. If they don't match → throws org.opensaml.xmlsec.signature.support.SignatureException

Step 7d-5: Status Validation
    Library: org.opensaml.saml.saml2.core (StatusCode constants)
    Checks: response.getStatus().getStatusCode().getValue()
            must equal "urn:oasis:names:tc:SAML:2.0:status:Success"

Step 7d-6: Issuer Validation
    Checks: response.getIssuer().getValue()
            must equal "http://localhost:3000/saml/metadata" (configured idpEntityId)
    Rejects any assertion from an untrusted issuer.

Step 7d-7: Extract User Identity from Assertion
    Library: org.opensaml.saml.saml2.core (Assertion, Subject, NameID, AttributeStatement)

    Assertion assertion = response.getAssertions().get(0);

    // NameID (the primary user identifier)
    String nameID = assertion.getSubject().getNameID().getValue();
    // → "john@edith.com"

    // Attributes (additional user claims)
    Map<String, String> attributes = new HashMap<>();
    for (AttributeStatement stmt : assertion.getAttributeStatements()) {
        for (Attribute attr : stmt.getAttributes()) {
            XMLObject value = attr.getAttributeValues().get(0);
            if (value instanceof XSString) {
                attributes.put(attr.getName(), ((XSString) value).getValue());
            }
        }
    }
    // → { "email": "john@edith.com", "displayName": "John Doe" }

Output: SamlUserInfo {
    nameID: "john@edith.com",
    email: "john@edith.com",
    displayName: "John Doe",
    attributes: { "email": "john@edith.com", "displayName": "John Doe" }
}
```

**Libraries involved in Step 7 (summary):**
| Library | Class | Purpose |
|---------|-------|---------|
| `java.util.Base64` (JDK) | `Base64.getDecoder()` | Decodes Base64 → raw XML bytes |
| `net.shibboleth.utilities.java.support.xml` (Shibboleth) | `BasicParserPool` | Thread-safe, namespace-aware XML parsing |
| `org.w3c.dom` (JDK) | `Document`, `Element` | Standard DOM representation of parsed XML |
| `org.opensaml.core.xml.io` (OpenSAML) | `UnmarshallerFactory`, `Unmarshaller` | DOM Element → typed OpenSAML objects |
| `org.opensaml.core.xml.config` (OpenSAML) | `XMLObjectProviderRegistrySupport` | Global registry for unmarshallers |
| `org.opensaml.xmlsec.signature.support` (OpenSAML) | `SignatureValidator` | RSA-SHA256 signature verification against IdP's public cert |
| `org.opensaml.saml.saml2.core` (OpenSAML) | `Response`, `Assertion`, `Subject`, `NameID`, `AttributeStatement`, `Attribute`, `StatusCode` | Typed access to SAML elements |
| `org.opensaml.core.xml.schema` (OpenSAML) | `XSString` | Typed access to `xs:string` attribute values |
| `org.opensaml.security.x509` (OpenSAML) | `BasicX509Credential` | Wraps the IdP's X.509 certificate for signature verification |

---

## Step 8 — edith-rdc-ui Establishes a Session (Node.js)

**File:** `edith-rdc-ui/server.js`

```javascript
req.session.samlUser = {
    nameID: data.nameID,           // "john@edith.com"
    email: data.email,             // "john@edith.com"
    displayName: data.displayName, // "John Doe"
    attributes: data.attributes    // { email, displayName }
};
res.redirect('/upload');
```

**Libraries involved:**
| Library | Purpose |
|---------|---------|
| `express-session` (Node.js) | Stores `samlUser` in a server-side session, sets a session cookie |
| `express` (Node.js) | `res.redirect('/upload')` — sends HTTP 302 to the browser |

The user is now authenticated on edith-rdc. All subsequent requests check `req.session.samlUser` via the `requireSAMLAuth` middleware. No further SAML processing occurs — the session cookie is the auth token from this point forward.

---

## Trust Model: How Does edith-rdc Trust edith-core?

The entire trust chain depends on **one file**: `idp-cert.pem`.

```
generate-certs.sh
    │
    ├── Generates RSA 2048-bit keypair
    │   ├── idp-cert.pem  (public certificate)
    │   └── idp-key.pem   (private key)
    │
    ├── Distributes idp-key.pem to:
    │   └── edith-core-backend/src/main/resources/saml/  (ONLY the IdP has the private key)
    │
    └── Distributes idp-cert.pem to:
        ├── edith-core-backend/src/main/resources/saml/  (IdP includes it in metadata)
        ├── edith-rdc-backend/src/main/resources/saml/   (SP uses it to verify signatures)
        └── edith-ach-backend/src/main/resources/saml/   (SP uses it to verify signatures)
```

**The trust logic:**
1. edith-core-backend signs assertions with `idp-key.pem` (private key)
2. edith-rdc-backend verifies signatures with `idp-cert.pem` (public certificate)
3. If `SignatureValidator.validate()` passes, the assertion was:
   - **Created by the holder of the private key** (authenticity)
   - **Not modified in transit** (integrity)
4. edith-rdc also checks the `Issuer` value matches the configured `saml.idp.entity-id`

If an attacker intercepts the Base64 SAMLResponse in the browser and modifies any byte (e.g., changes the email), the SHA-256 hash changes, the RSA signature no longer validates, and `SignatureValidator` throws an exception. The SSO is rejected.

---

## Complete Library Stack Summary

### IdP Side (edith-core)

| Layer | Technology | Role |
|-------|-----------|------|
| Browser | HTML/JS | User clicks link, auto-submits form |
| Frontend | Node.js + Express + EJS | HTTP routing, session management, template rendering |
| Backend | Spring Boot 3.4.3 + Java 17 | REST API, delegates to SAML service |
| SAML Library | OpenSAML 4.3.2 (org.opensaml) | SAML object construction, XML marshalling, signature creation |
| Support Library | Shibboleth Java Support (net.shibboleth) | XML serialization (`SerializeSupport`) |
| Crypto | JDK `java.security` | RSA key loading, X.509 certificate parsing |
| Signing | OpenSAML `Signer` + JDK `java.security.Signature` | RSA-SHA256 XML digital signature |

### SP Side (edith-rdc)

| Layer | Technology | Role |
|-------|-----------|------|
| Browser | HTML form POST | Transports SAMLResponse from IdP domain to SP domain |
| Frontend | Node.js + Express | HTTP routing, session management, backend proxy |
| Backend | Spring Boot 3.4.3 + Java 17 | REST API, delegates to SAML service |
| SAML Library | OpenSAML 4.3.2 (org.opensaml) | XML unmarshalling, signature verification, assertion parsing |
| Support Library | Shibboleth Java Support (net.shibboleth) | XML parser pool (`BasicParserPool`) |
| Crypto | JDK `java.security` | X.509 certificate parsing |
| Verification | OpenSAML `SignatureValidator` + JDK `java.security.Signature` | RSA-SHA256 XML signature verification |

### Maven Dependencies (Both Sides)

```xml
<dependency>
    <groupId>org.opensaml</groupId>
    <artifactId>opensaml-core</artifactId>            <!-- OpenSAML initialization, XML object registry -->
    <version>4.3.2</version>
</dependency>
<dependency>
    <groupId>org.opensaml</groupId>
    <artifactId>opensaml-saml-api</artifactId>         <!-- SAML 2.0 interfaces (Response, Assertion, etc.) -->
    <version>4.3.2</version>
</dependency>
<dependency>
    <groupId>org.opensaml</groupId>
    <artifactId>opensaml-saml-impl</artifactId>        <!-- Builder implementations (ResponseBuilder, etc.) -->
    <version>4.3.2</version>
</dependency>
<dependency>
    <groupId>org.opensaml</groupId>
    <artifactId>opensaml-xmlsec-api</artifactId>       <!-- Signature interfaces -->
    <version>4.3.2</version>
</dependency>
<dependency>
    <groupId>org.opensaml</groupId>
    <artifactId>opensaml-xmlsec-impl</artifactId>      <!-- Signature implementations (Signer, SignatureValidator) -->
    <version>4.3.2</version>
</dependency>
```

These are resolved from the **Shibboleth Maven Repository** (`https://build.shibboleth.net/maven/releases/`), not Maven Central.

---

## What the Signed SAML XML Looks Like

For reference, here is the approximate structure of the XML that travels through the browser:

```xml
<saml2p:Response xmlns:saml2p="urn:oasis:names:tc:SAML:2.0:protocol"
                 Destination="http://localhost:4000/saml/acs"
                 ID="_a1b2c3d4-..."
                 IssueInstant="2026-03-02T10:00:00Z"
                 Version="2.0">
    <saml2:Issuer xmlns:saml2="urn:oasis:names:tc:SAML:2.0:assertion">
        http://localhost:3000/saml/metadata
    </saml2:Issuer>
    <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
        <ds:SignedInfo>
            <ds:CanonicalizationMethod Algorithm="http://www.w3.org/2001/10/xml-exc-c14n#"/>
            <ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"/>
            <ds:Reference URI="#_a1b2c3d4-...">
                <ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"/>
                <ds:DigestValue>abc123...</ds:DigestValue>
            </ds:Reference>
        </ds:SignedInfo>
        <ds:SignatureValue>RSA-SHA256-SIGNATURE-BYTES-BASE64...</ds:SignatureValue>
    </ds:Signature>
    <saml2p:Status>
        <saml2p:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>
    </saml2p:Status>
    <saml2:Assertion xmlns:saml2="urn:oasis:names:tc:SAML:2.0:assertion"
                     ID="_e5f6g7h8-..."
                     IssueInstant="2026-03-02T10:00:00Z"
                     Version="2.0">
        <saml2:Issuer>http://localhost:3000/saml/metadata</saml2:Issuer>
        <saml2:Subject>
            <saml2:NameID Format="urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress">
                john@edith.com
            </saml2:NameID>
            <saml2:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">
                <saml2:SubjectConfirmationData
                    NotOnOrAfter="2026-03-02T10:05:00Z"
                    Recipient="http://localhost:4000/saml/acs"/>
            </saml2:SubjectConfirmation>
        </saml2:Subject>
        <saml2:Conditions NotBefore="2026-03-02T10:00:00Z"
                          NotOnOrAfter="2026-03-02T10:05:00Z">
            <saml2:AudienceRestriction>
                <saml2:Audience>http://localhost:4000/saml/metadata</saml2:Audience>
            </saml2:AudienceRestriction>
        </saml2:Conditions>
        <saml2:AuthnStatement AuthnInstant="2026-03-02T10:00:00Z">
            <saml2:AuthnContext>
                <saml2:AuthnContextClassRef>
                    urn:oasis:names:tc:SAML:2.0:ac:classes:Password
                </saml2:AuthnContextClassRef>
            </saml2:AuthnContext>
        </saml2:AuthnStatement>
        <saml2:AttributeStatement>
            <saml2:Attribute Name="email">
                <saml2:AttributeValue xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                      xsi:type="xs:string">
                    john@edith.com
                </saml2:AttributeValue>
            </saml2:Attribute>
            <saml2:Attribute Name="displayName">
                <saml2:AttributeValue xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                      xsi:type="xs:string">
                    John Doe
                </saml2:AttributeValue>
            </saml2:Attribute>
        </saml2:AttributeStatement>
    </saml2:Assertion>
</saml2p:Response>
```
