SHELL := /bin/bash

.PHONY: up down clean logs ps e2e smoke test vet

## Build + start every container (pki-init generates all certs/keys first)
up:
	docker compose up -d --build
	@echo
	@echo "Gateway:      https://localhost:8443  (add hosts entries: see README)"
	@echo "  idp vhost:  https://idp.fapi.local:8443/.well-known/openid-configuration"
	@echo "  rbac vhost: https://rbac.fapi.local:8443/rbac/admin/roles"
	@echo "  trust plane: idp + gateway (Go) | rbac (Java 21 / Spring Boot 4)"

down:
	docker compose down

## Stop and remove everything including volumes (PKI + databases)
clean:
	docker compose down -v --remove-orphans

logs:
	docker compose logs -f --tail=50

ps:
	docker compose ps

## End-to-end FAPI suite through the gateway (positive + adversarial)
e2e:
	docker compose --profile e2e build fapi-client
	docker compose --profile e2e run --rm fapi-client suite

## Host-side smoke test (needs /etc/hosts entries, see README)
smoke:
	curl -sk https://localhost:8443/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
	@echo "-- expect traversal to be rejected (400) --"

vet:
	go vet ./...

clean-images:
	docker compose down --rmi local -v --remove-orphans
