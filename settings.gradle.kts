rootProject.name = "ekbatan"

include(
    "ekbatan-core",
    "ekbatan-core:ekbatan-core-repo-test",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mariadb",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mariadb:repository",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mariadb:dual-table-events",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mariadb:single-table-events",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mysql",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mysql:repository",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mysql:dual-table-events",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mysql:single-table-events",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-pg",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-pg:repository",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-pg:dual-table-events",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-pg:single-table-events",
    "ekbatan-integration-tests:postgres-dual-table-events",
    "ekbatan-integration-tests:postgres-single-table-events",
    "ekbatan-integration-tests:postgres-sharded",
    "ekbatan-annotation-processor"
)
