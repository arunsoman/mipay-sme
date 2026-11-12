SHELL := /bin/bash

.PHONY: up up-mfa down clean logs ps e2e smoke test vet test-integration role-pack

## Build + start every container (pki-init generates all certs/keys first)
up:
	docker compose up -d --build
	@echo
	@echo "Gateway:      https://localhost:8443  (add hosts entries: see README)"
	@echo "  idp vhost:  https://idp.fapi.local:8443/.well-known/openid-configuration"
	@echo "  rbac vhost: https://rbac.fapi.local:8443/rbac/admin/roles"
	@echo "  trust plane: idp + gateway (Go) | rbac + comms (Java 21 / Spring Boot 4)"

## Dev/sandbox login requires TOTP or password + SMS. This is a separate run mode
## so legacy FAPI e2e tests can still exercise the off mode.
up-mfa:
	IDP_MFA_MODE=enforce docker compose up -d --build
	@echo "IdP MFA: enforce (TOTP or password + SMS through Java comms)"

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

## Compliance role pack (cut-over P2.5a) from the running RBAC, as markdown
role-pack:
	@docker compose --profile e2e run --rm -T fapi-client role-pack

## Go + Java integration tests against disposable Postgres databases.
## These suites drop their tables; create idp_test, comms_test and rbac_test first.
##   make test-integration PG=postgres://user:pw@127.0.0.1:5432
test-integration:
	TEST_IDP_DSN="$(PG)/idp_test?sslmode=disable" TEST_COMMS_DSN="$(PG)/comms_test?sslmode=disable" go test -p 1 ./... -count=1
	cd java && REQUIRE_RBAC_DSN=true TEST_RBAC_DSN="$(PG)/rbac_test?sslmode=disable" ./gradlew :rbac-service:test
	cd java && TEST_COMMS_DSN="$(PG)/comms_test?sslmode=disable" ./gradlew :comms-service:test --rerun-tasks

## Host-side smoke test (needs /etc/hosts entries, see README)
smoke:
	curl -sk https://localhost:8443/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'
	@echo "-- expect traversal to be rejected (400) --"

vet:
	go vet ./...

clean-images:
	docker compose down --rmi local -v --remove-orphans
