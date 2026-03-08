#!/bin/bash

# Edith Platform - Start All Services
# Usage: ./start-all.sh

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo "========================================"
echo "  Edith Platform - Service Manager"
echo "========================================"
echo ""

# Kill any existing processes on our ports
echo -e "${YELLOW}Stopping any existing services...${NC}"
for PORT in 3000 3001 4000 4001 5000 5001 6000 9090 9091 9092 9093 9094; do
  PID=$(lsof -ti :$PORT 2>/dev/null)
  if [ -n "$PID" ]; then
    kill -9 $PID 2>/dev/null
    echo "  Killed process on port $PORT (PID: $PID)"
  fi
done
sleep 1

# Create logs directory
mkdir -p "$BASE_DIR/logs"

# Start Spring Boot backends
echo ""
echo -e "${GREEN}Starting backends...${NC}"

java -jar "$BASE_DIR/edith-core-backend/target/edith-core-backend-1.0.0.jar" \
  > "$BASE_DIR/logs/core-backend.log" 2>&1 &
echo "  edith-core-backend (port 9090) - PID: $!"

java -jar "$BASE_DIR/edith-rdc-backend/target/edith-rdc-backend-1.0.0.jar" \
  > "$BASE_DIR/logs/rdc-backend.log" 2>&1 &
echo "  edith-rdc-backend  (port 9091) - PID: $!"

java -jar "$BASE_DIR/edith-ach-backend/target/edith-ach-backend-1.0.0.jar" \
  > "$BASE_DIR/logs/ach-backend.log" 2>&1 &
echo "  edith-ach-backend  (port 9092) - PID: $!"

java -jar "$BASE_DIR/edith-bank-backend/target/edith-bank-backend-1.0.0.jar" \
  > "$BASE_DIR/logs/bank-backend.log" 2>&1 &
echo "  edith-bank-backend   (port 9093) - PID: $!"

java -jar "$BASE_DIR/jarvis-bank-backend/target/jarvis-bank-backend-1.0.0.jar" \
  > "$BASE_DIR/logs/jarvis-backend.log" 2>&1 &
echo "  jarvis-bank-backend  (port 9094) - PID: $!"

# Wait for backends to start
echo ""
echo -e "${YELLOW}Waiting for backends to start...${NC}"
for PORT in 9090 9091 9092 9093 9094; do
  TRIES=0
  while ! curl -s "http://localhost:$PORT" > /dev/null 2>&1; do
    TRIES=$((TRIES + 1))
    if [ $TRIES -ge 30 ]; then
      echo -e "  ${RED}Timeout waiting for port $PORT${NC}"
      break
    fi
    sleep 1
  done
  if [ $TRIES -lt 30 ]; then
    echo -e "  ${GREEN}Port $PORT is ready${NC}"
  fi
done

# Start Node.js UIs
echo ""
echo -e "${GREEN}Starting UIs...${NC}"

# Start core-ui FIRST on internal port 3001 (gateway proxies to it)
cd "$BASE_DIR/edith-core-ui" && node server.js \
  > "$BASE_DIR/logs/core-ui.log" 2>&1 &
echo "  edith-core-ui      (port 3001, internal) - PID: $!"

cd "$BASE_DIR/edith-rdc-ui" && node server.js \
  > "$BASE_DIR/logs/rdc-ui.log" 2>&1 &
echo "  edith-rdc-ui       (port 4000) - PID: $!"

cd "$BASE_DIR/edith-ach-ui" && node server.js \
  > "$BASE_DIR/logs/ach-ui.log" 2>&1 &
echo "  edith-ach-ui       (port 4001) - PID: $!"

cd "$BASE_DIR/edith-bank-ui" && node server.js \
  > "$BASE_DIR/logs/bank-ui.log" 2>&1 &
echo "  edith-bank-ui      (port 5000) - PID: $!"

cd "$BASE_DIR/jarvis-bank-ui" && node server.js \
  > "$BASE_DIR/logs/jarvis-ui.log" 2>&1 &
echo "  jarvis-bank-ui     (port 5001) - PID: $!"

# Start gateway LAST — it proxies port 3000 to core-ui and serves admin on 6000
cd "$BASE_DIR/edith-saml-gateway" && node server.js \
  > "$BASE_DIR/logs/gateway.log" 2>&1 &
echo "  edith-saml-gateway (port 3000 proxy + port 6000 admin) - PID: $!"

sleep 1

echo ""
echo "========================================"
echo -e "  ${GREEN}All services started!${NC}"
echo "========================================"
echo ""
echo "  edith-core (gateway) http://localhost:3000  (SAML Gateway Proxy)"
echo "  gateway admin        http://localhost:6000  (Gateway Dashboard)"
echo "  edith-core-ui        http://localhost:3001  (Core UI, internal)"
echo "  edith-bank-ui        http://localhost:5000  (Bank IdP)"
echo "  jarvis-bank-ui       http://localhost:5001  (Jarvis IdP)"
echo "  edith-rdc-ui         http://localhost:4000  (RDC SP)"
echo "  edith-ach-ui         http://localhost:4001  (ACH SP)"
echo "  edith-bank-backend   http://localhost:9093"
echo "  jarvis-bank-backend  http://localhost:9094"
echo "  edith-core-backend   http://localhost:9090"
echo "  edith-rdc-backend    http://localhost:9091"
echo "  edith-ach-backend    http://localhost:9092"
echo ""
echo "  Logs: $BASE_DIR/logs/"
echo "  Core login:   john/password123 or jane/password123"
echo "  Bank login:   bankuser1/password123 or bankuser2/password123"
echo "  Jarvis login: jarvis1/password123 or jarvis2/password123"
echo ""
echo "  To stop all: ./stop-all.sh"
