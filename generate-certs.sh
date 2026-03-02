#!/bin/bash

# Edith Platform - Generate & Distribute SAML Certificates
# Generates self-signed X.509 certs (RSA 2048, 365 days) for all IdPs and SPs,
# then copies them to the correct project directories.

set -e

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
CERT_DIR="$BASE_DIR/.certs"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "========================================"
echo "  Edith Platform - Certificate Generator"
echo "========================================"
echo ""

# Create temp working directory
rm -rf "$CERT_DIR"
mkdir -p "$CERT_DIR"

generate_keypair() {
  local name="$1"
  local cn="$2"
  echo -e "${YELLOW}Generating keypair:${NC} $name (CN=$cn)"
  openssl req -x509 -newkey rsa:2048 \
    -keyout "$CERT_DIR/${name}-key.pem" \
    -out "$CERT_DIR/${name}-cert.pem" \
    -days 365 -nodes -subj "/CN=$cn" 2>/dev/null
}

# ── Generate all keypairs ──────────────────────────────────

generate_keypair "idp"        "edith-core-idp"
generate_keypair "rdc-sp"     "edith-rdc-sp"
generate_keypair "ach-sp"     "edith-ach-sp"
generate_keypair "bank-idp"   "edith-bank-idp"
generate_keypair "jarvis-idp" "jarvis-bank-idp"

echo ""
echo -e "${GREEN}All keypairs generated.${NC}"
echo ""

# ── Distribute certificates ────────────────────────────────

echo -e "${YELLOW}Distributing certificates...${NC}"

# Helper: ensure directory exists and copy file
distribute() {
  local src="$1"
  local dest="$2"
  mkdir -p "$(dirname "$dest")"
  cp "$src" "$dest"
  echo "  $(basename "$src") -> $(echo "$dest" | sed "s|$BASE_DIR/||")"
}

# ── Core IdP (idp-cert.pem / idp-key.pem) ──
# Origin: edith-core
# Shared to: edith-rdc-backend, edith-ach-backend (for signature verification)
distribute "$CERT_DIR/idp-cert.pem" "$BASE_DIR/edith-core-ui/saml/idp-cert.pem"
distribute "$CERT_DIR/idp-key.pem"  "$BASE_DIR/edith-core-ui/saml/idp-key.pem"
distribute "$CERT_DIR/idp-cert.pem" "$BASE_DIR/edith-core-backend/src/main/resources/saml/idp-cert.pem"
distribute "$CERT_DIR/idp-key.pem"  "$BASE_DIR/edith-core-backend/src/main/resources/saml/idp-key.pem"
distribute "$CERT_DIR/idp-cert.pem" "$BASE_DIR/edith-rdc-backend/src/main/resources/saml/idp-cert.pem"
distribute "$CERT_DIR/idp-cert.pem" "$BASE_DIR/edith-ach-backend/src/main/resources/saml/idp-cert.pem"

# ── RDC SP (sp-cert.pem / sp-key.pem) ──
# Origin: edith-rdc
# Shared to: edith-core-backend (for SP metadata)
distribute "$CERT_DIR/rdc-sp-cert.pem" "$BASE_DIR/edith-rdc-ui/saml/sp-cert.pem"
distribute "$CERT_DIR/rdc-sp-key.pem"  "$BASE_DIR/edith-rdc-ui/saml/sp-key.pem"
distribute "$CERT_DIR/rdc-sp-cert.pem" "$BASE_DIR/edith-rdc-backend/src/main/resources/saml/sp-cert.pem"
distribute "$CERT_DIR/rdc-sp-key.pem"  "$BASE_DIR/edith-rdc-backend/src/main/resources/saml/sp-key.pem"
distribute "$CERT_DIR/rdc-sp-cert.pem" "$BASE_DIR/edith-core-backend/src/main/resources/saml/sp-cert.pem"

# ── ACH SP (sp-cert.pem / sp-key.pem) ──
# Origin: edith-ach
# Shared to: edith-core-backend (for SP metadata)
distribute "$CERT_DIR/ach-sp-cert.pem" "$BASE_DIR/edith-ach-ui/saml/sp-cert.pem"
distribute "$CERT_DIR/ach-sp-key.pem"  "$BASE_DIR/edith-ach-ui/saml/sp-key.pem"
distribute "$CERT_DIR/ach-sp-cert.pem" "$BASE_DIR/edith-ach-backend/src/main/resources/saml/sp-cert.pem"
distribute "$CERT_DIR/ach-sp-key.pem"  "$BASE_DIR/edith-ach-backend/src/main/resources/saml/sp-key.pem"
distribute "$CERT_DIR/ach-sp-cert.pem" "$BASE_DIR/edith-core-backend/src/main/resources/saml/ach-sp-cert.pem"

# ── Edith Bank IdP (bank-idp-cert.pem / bank-idp-key.pem) ──
# Origin: edith-bank
# Shared to: edith-core-backend (for SP validation of bank assertions)
distribute "$CERT_DIR/bank-idp-cert.pem" "$BASE_DIR/edith-bank-ui/saml/bank-idp-cert.pem"
distribute "$CERT_DIR/bank-idp-key.pem"  "$BASE_DIR/edith-bank-ui/saml/bank-idp-key.pem"
distribute "$CERT_DIR/bank-idp-cert.pem" "$BASE_DIR/edith-bank-backend/src/main/resources/saml/bank-idp-cert.pem"
distribute "$CERT_DIR/bank-idp-key.pem"  "$BASE_DIR/edith-bank-backend/src/main/resources/saml/bank-idp-key.pem"
distribute "$CERT_DIR/bank-idp-cert.pem" "$BASE_DIR/edith-core-backend/src/main/resources/saml/bank-idp-cert.pem"

# ── Jarvis Bank IdP (jarvis-idp-cert.pem / jarvis-idp-key.pem) ──
# Origin: jarvis-bank
# Shared to: edith-core-backend (for SP validation of jarvis assertions)
distribute "$CERT_DIR/jarvis-idp-cert.pem" "$BASE_DIR/jarvis-bank-ui/saml/jarvis-idp-cert.pem"
distribute "$CERT_DIR/jarvis-idp-key.pem"  "$BASE_DIR/jarvis-bank-ui/saml/jarvis-idp-key.pem"
distribute "$CERT_DIR/jarvis-idp-cert.pem" "$BASE_DIR/jarvis-bank-backend/src/main/resources/saml/jarvis-idp-cert.pem"
distribute "$CERT_DIR/jarvis-idp-key.pem"  "$BASE_DIR/jarvis-bank-backend/src/main/resources/saml/jarvis-idp-key.pem"
distribute "$CERT_DIR/jarvis-idp-cert.pem" "$BASE_DIR/edith-core-backend/src/main/resources/saml/jarvis-idp-cert.pem"

# Clean up temp directory
rm -rf "$CERT_DIR"

echo ""
echo -e "${GREEN}All certificates generated and distributed.${NC}"
echo ""
echo "Summary:"
echo "  Core IdP cert    -> edith-core, edith-rdc-backend, edith-ach-backend"
echo "  RDC SP cert      -> edith-rdc, edith-core-backend"
echo "  ACH SP cert      -> edith-ach, edith-core-backend"
echo "  Bank IdP cert    -> edith-bank, edith-core-backend"
echo "  Jarvis IdP cert  -> jarvis-bank, edith-core-backend"
echo ""
echo "NOTE: Rebuild backends after regenerating certs:"
echo "  cd edith-core-backend && mvn clean package -DskipTests && cd .."
echo "  cd edith-rdc-backend && mvn clean package -DskipTests && cd .."
echo "  cd edith-ach-backend && mvn clean package -DskipTests && cd .."
echo "  cd edith-bank-backend && mvn clean package -DskipTests && cd .."
echo "  cd jarvis-bank-backend && mvn clean package -DskipTests && cd .."
