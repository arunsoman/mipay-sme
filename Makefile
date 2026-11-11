# MiPay SME Ecosystem — convenience tasks
SHELL := /bin/bash

.PHONY: up down rebuild logs ps backend-build backend-test jar frontend-build admin-build portals-build clean reset-db

## Start the whole stack (db + backend + frontend)
up:
	docker compose up -d --build
	@echo "Frontend:  http://localhost:4200"
	@echo "Admin portal: http://localhost:4201"
	@echo "Manufacturer portal: http://localhost:4202"
	@echo "Distributor portal:  http://localhost:4203"
	@echo "Supplier portal:     http://localhost:4204"
	@echo "Backend:   http://localhost:8080"
	@echo "Swagger:   http://localhost:8080/swagger-ui.html"

## Stop the stack
down:
	docker compose down

## Rebuild images and restart
rebuild:
	docker compose build --no-cache && docker compose up -d

## Tail backend logs
logs:
	docker compose logs -f backend

ps:
	docker compose ps

## Compile + run the backend unit/UAT test pack (H2, no DB needed)
backend-build:
	cd backend && ./gradlew clean build

backend-test:
	cd backend && ./gradlew test

## Build the deployable jar (sme-backend.jar)
jar:
	cd backend && ./gradlew bootJar

## Build the frontend production bundle
frontend-build:
	cd frontend && npm ci && npm run build

## Build the admin portal production bundle
admin-build:
	cd frontend && npm ci && npx ng build admin-portal

## Build the three org portals (manufacturer / distributor / supplier)
portals-build:
	cd frontend && npm ci && for p in manufacturer distributor supplier; do npx ng build $$p-portal || exit 1; done

## Reset database volume (destructive!)
reset-db:
	docker compose down -v
	docker compose up -d db
	@sleep 5
	docker compose up -d

clean:
	docker compose down -v --rmi local
