-- V2: DB-level defence-in-depth adopted from the reference UAT implementation.
-- Second-person rule from TC-ONB-03 (UAT SB p.8): an approved bank-account change
-- can never have the same approver as the requester. Service-level checks in
-- OpsService stay in place; this CHECK catches any future path that bypasses them.

alter table supplier_bank_accounts
    drop constraint if exists chk_bank_approver_not_requester;

alter table supplier_bank_accounts
    add constraint chk_bank_approver_not_requester
    check (approved_by is null or approved_by <> requested_by);
