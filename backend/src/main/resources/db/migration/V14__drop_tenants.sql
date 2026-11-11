-- V14: drop the unused tenants table (idp-rbac-cutover-plan.md P0.5).
-- Nothing reads it: the Tenant entity, TenantRepository and the TenantType /
-- TenantStatus enums are removed in the same change. RBAC tenants come from the
-- org-tree sync (tenant id = org id) and live in trustplane RBAC, not here.
-- No foreign key references tenants (V1), so no cascade is needed.

drop table if exists tenants;
