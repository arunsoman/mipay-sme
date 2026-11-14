# Fresh database installation

Flyway runs the two scripts in `db/fresh` against an empty PostgreSQL database.
They create the current schema and published KYC v3 catalogue without inserting
organizations or demo users. Existing databases must be replaced from a fresh
database for this development-stage installation path.
