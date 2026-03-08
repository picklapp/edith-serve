#!/bin/bash

# Edith Platform - Stop All Services

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

echo "Stopping all Edith services..."

for PORT in 3000 3001 4000 4001 5000 5001 6000 9090 9091 9092 9093 9094; do
  PID=$(lsof -ti :$PORT 2>/dev/null)
  if [ -n "$PID" ]; then
    kill -9 $PID 2>/dev/null
    echo -e "  ${RED}Stopped${NC} port $PORT (PID: $PID)"
  else
    echo "  Port $PORT - not running"
  fi
done

echo ""
echo -e "${GREEN}All services stopped.${NC}"
