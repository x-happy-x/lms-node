# PostgreSQL (LMS-10, INFRA-107)

Production on s3 uses database `lms_node` at `pg-db.local:5432` on s1.
`DATABASE_URL_FILE` selects the PostgreSQL backend; the file is mounted read-only
at `/run/homeapps-postgres/database.url`. TLS verifies the managed CA and server
name. Connection failures never switch to SQLite/JSON. Existing local tests keep
their original storage when this variable is absent.

The runtime role cannot change application schemas. Full SQL history in
`db/migrations` runs through the shared homeapps dbmate receiver before CI
restarts the application. Applied migrations are immutable; add a new file for
changes. CI tests use fresh isolated PostgreSQL databases, not production.
For a manual deployment, apply this repository's full migration history first
with `homeapps/postgres/db.ps1 -Action migrate -Service lms_node -MigrationsDir db/migrations`.

The initial import is verified by row counts and content hashes. Original
SQLite/JSON files are retained in private backups on s3 and s1. After PostgreSQL
accepts writes, recovery must keep that PostgreSQL state; restarting an old
SQLite image would discard new changes. Initial CD therefore stops on a failed
health check rather than starting a pre-migration SQLite image.

Files, downloads, media, encryption keys, browser state and service configuration
remain in their existing persistent volumes. The shared PostgreSQL backup runs
daily and is verified on s2; it includes all application databases.

`public.jobs` stores each job snapshot; download and resume files stay local.
