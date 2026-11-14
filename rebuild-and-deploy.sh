#!/bin/bash
# Rebuild and Deploy Script
# Rebuilds all code and starts the local stack

set -e

echo "========================================="
echo "  MiPay SME - Rebuild and Deploy"
echo "========================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Step 1: Building Go Gateway...${NC}"
cd /home/arun/sme/trustplane
go build ./cmd/gateway/ 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Gateway built successfully${NC}"
else
    echo -e "${RED}❌ Gateway build failed${NC}"
    exit 1
fi

echo -e "${YELLOW}Step 2: Building IdP Server...${NC}"
cd /home/arun/sme/trustplane/cmd/idp && go build . 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ IdP built successfully${NC}"
else
    echo -e "${RED}❌ IdP build failed${NC}"
    exit 1
fi

echo -e "${YELLOW}Step 3: Building Java Backend...${NC}"
cd /home/arun/sme/backend
./gradlew compileJava 2>&1
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Java compile failed${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Java backend compiled successfully${NC}"

echo -e "${YELLOW}Step 4: Building Angular Frontend...${NC}"
cd /home/arun/sme/frontend
npm run build 2>&1 | tail -5
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Angular build failed${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Angular frontend built successfully${NC}"

echo -e "${YELLOW}Step 5: Starting Docker Services...${NC}"
echo -e "${YELLOW}Starting trustplane (Gateway, IdP, RBAC, Comms)...${NC}"
cd /home/arun/sme/trustplane
docker compose up -d --build 2>&1

echo -e "${YELLOW}Starting afriqpay portals and gateway...${NC}"
cd /home/arun/sme/deploy/afriqpay
docker compose up -d --build 2>&1

echo -e "${YELLOW}Step 6: Waiting for Services to be Ready...${NC}"
sleep 15

# Check health
echo -e "${YELLOW}Checking service health...${NC}"
TRUSTPLANE_HEALTH=$(docker compose -f /home/arun/sme/trustplane/docker-compose.yml ps --filter "health=healthy" 2>/dev/null | wc -l)
AFRIQPAY_HEALTH=$(docker compose -f /home/arun/sme/deploy/afriqpay/docker-compose.yml ps --filter "health=healthy" 2>/dev/null | wc -l)

echo -e "${GREEN}✅ Trustplane services healthy: $TRUSTPLANE_HEALTH${NC}"
echo -e "${GREEN}✅ Afriqpay services healthy: $AFRIQPAY_HEALTH${NC}"

echo -e ""
echo -e "${GREEN}========================================="
echo -e "  Deployment Complete!"
echo -e "=========================================${NC}"
echo ""
echo "Service URLs:"
echo "  • Admin Portal:     https://admin.afriqpay.com"
echo "  • Main Portal:      https://mipay.afriqpay.com"
echo "  • Gateway API:      https://portal.fapi.local:8443"
echo "  • IdP Discovery:    https://idp.fapi.local:8443/.well-known/jwks.json"
echo "  • Gateway Health:   http://127.0.0.1:19120/health"
echo ""
echo "Logs:"
echo "  • Trustplane: docker compose -f /home/arun/sme/trustplane/docker-compose.yml logs -f"
echo "  • Afriqpay:   docker compose -f /home/arun/sme/deploy/afriqpay/docker-compose.yml logs -f"
