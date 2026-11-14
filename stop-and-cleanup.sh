#!/bin/bash
# Stop and Cleanup Script
# Stops all containers, removes images, and reclaims disk space

set -e

echo "========================================="
echo "  MiPay SME - Stop and Cleanup"
echo "========================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Step 1: Stopping Docker Compose...${NC}"

# Stop trustplane
echo -e "${YELLOW}Stopping trustplane services...${NC}"
cd /home/arun/sme/trustplane
docker compose down 2>&1 || echo -e "${YELLOW}Trustplane not running or already stopped${NC}"

# Stop afriqpay
echo -e "${YELLOW}Stopping afriqpay services...${NC}"
cd /home/arun/sme/deploy/afriqpay
docker compose down 2>&1 || echo -e "${YELLOW}Afriqpay not running or already stopped${NC}"

echo -e "${YELLOW}Step 2: Removing Docker Images...${NC}"

# Remove specific project images
echo -e "${YELLOW}Removing trustplane images...${NC}"
docker rmi trustplane/gateway trustplane/idp trustplane/rbac trustplane/comms 2>/dev/null || echo -e "${YELLOW}Some images not found (may be okay)${NC}"

echo -e "${YELLOW}Removing afriqpay images...${NC}"
docker rmi mipay-sme/backend mipay-sme/admin-portal mipay-sme/manufacturer-portal mipay-sme/distributor-portal mipay-sme/supplier-portal mipay-sme/gateway 2>/dev/null || echo -e "${YELLOW}Some images not found (may be okay)${NC}"

echo -e "${YELLOW}Step 3: Pruning Unused Docker Resources...${NC}"

# Remove stopped containers
echo -e "${YELLOW}Removing stopped containers...${NC}"
docker container prune -f 2>&1

# Remove unused networks
echo -e "${YELLOW}Removing unused networks...${NC}"
docker network prune -f 2>&1

# Remove unused volumes
echo -e "${YELLOW}Removing unused volumes...${NC}"
docker volume prune -f 2>&1

# Remove unused images (all dangling)
echo -e "${YELLOW}Removing dangling images...${NC}"
docker image prune -f 2>&1

echo -e "${YELLOW}Step 4: Cleaning Build Artifacts...${NC}"

# Clean Java build artifacts
echo -e "${YELLOW}Cleaning Java build artifacts...${NC}"
cd /home/arun/sme/backend
./gradlew clean 2>&1 | tail -3

# Clean Go build artifacts
echo -e "${YELLOW}Cleaning Go build artifacts...${NC}"
cd /home/arun/sme/trustplane
go clean -cache 2>&1 || true

# Clean npm build artifacts
echo -e "${YELLOW}Cleaning Angular build artifacts...${NC}"
cd /home/arun/sme/frontend
rm -rf node_modules/.cache 2>/dev/null || true

echo -e "${YELLOW}Step 5: Disk Space Reclamation...${NC}"

# Show disk usage before
echo -e "${YELLOW}Disk usage before cleanup:${NC}"
docker system df 2>/dev/null | tail -10 || echo "Docker system df not available"

# Force remove all stopped containers, networks, images, build cache
echo -e "${YELLOW}Running docker system prune --all...${NC}"
docker system prune --all --volumes -f 2>&1

# Show disk usage after
echo -e "${YELLOW}Disk usage after cleanup:${NC}"
docker system df 2>/dev/null | tail -10 || echo "Docker system df not available"

echo -e ""
echo -e "${GREEN}========================================="
echo -e "  Stop and Cleanup Complete!"
echo -e "=========================================${NC}"
echo ""
echo "To redeploy later, run: rebuild-and-deploy.sh"
