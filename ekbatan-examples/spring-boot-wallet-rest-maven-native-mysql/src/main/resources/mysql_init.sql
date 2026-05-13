-- The named test user (`wallet`) only has rights on the `wallet` database by default. The
-- first Flyway migration needs to CREATE DATABASE eventlog and then write tables into it, so
-- we grant cross-database privileges here. This script runs as root, before the container
-- becomes ready, so subsequent migrations run as `wallet` with full access.
GRANT ALL PRIVILEGES ON *.* TO 'wallet'@'%';
FLUSH PRIVILEGES;
