# Plugin: KYC Periodic Review

Schedules re-KYC from `kyc_next_review_date` and restricts orgs whose review is overdue.

## Benefits of a plugin over building it into the core
- **Fills a known gap without bloating core:** the KYC spec lists this as "planned".
- **Restricts through the existing hook:** an overdue org is restricted through the same operational check as suspension.
- **Policy can vary by jurisdiction or partner:** without changing onboarding code.
- **Independent schedule:** review jobs run and scale apart from the request path.
