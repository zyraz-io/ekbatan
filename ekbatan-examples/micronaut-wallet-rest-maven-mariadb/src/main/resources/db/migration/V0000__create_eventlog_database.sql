-- In MariaDB / MySQL, "schema" and "database" are synonyms, so what Postgres calls a schema
-- becomes a separate database here. The framework writes its outbox rows into eventlog.*,
-- which must therefore be its own database. Created from the main DB connection — only works
-- because the connecting user has cross-database GRANTs (set up via mariadb_init.sql in the
-- compose / Testcontainers setup; the codegen container connects as root so it doesn't need it).
CREATE DATABASE IF NOT EXISTS eventlog;
