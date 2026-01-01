rootProject.name = "ekbatan"

include(
    "ekbatan-core",
    "ekbatan-core:ekbatan-core-repo-test",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mariadb",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-mysql",
    "ekbatan-core:ekbatan-core-repo-test:ekbatan-core-repo-test-pg",
    "ekbatan-examples",
    "ekbatan-annotation-processor"
)