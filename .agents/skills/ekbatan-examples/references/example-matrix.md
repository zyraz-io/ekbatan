# Ekbatan Example Matrix

Use this to reason about example coverage. The filesystem is the source of truth.

## Project Families

Base REST wallet examples are organized by:

- Stack: `spring-boot`, `quarkus`, `micronaut`
- Build tool: `gradle`, `maven`
- Dialect: `pg`, `mysql`, `mariadb`
- Runtime variant: regular JVM and `native`

Specialized examples:

- `spring-boot-wallet-rest-gradle-sharded-pg`
- `spring-boot-wallet-rest-gradle-native-sharded-pg`
- `spring-boot-wallet-saga-gradle-pg`
- `spring-boot-job-worker-gradle-pg`

## Useful Inventory Commands

```bash
find ekbatan-examples -mindepth 2 -maxdepth 2 -name settings.gradle.kts | sort
find ekbatan-examples -mindepth 2 -maxdepth 2 -name pom.xml | sort
find ekbatan-examples -path '*/src/main/java/*' -name 'WalletController.java' -o -name 'WalletResource.java'
```

## Cross-Matrix Audit Pattern

For a source claim, search for positive and negative evidence:

```bash
rg -n 'KeyedLockProvider|lockProvider.acquire' ekbatan-examples --glob '!**/build/**'
rg -n 'WalletDepositMoneyAction|WalletDepositAction' ekbatan-examples --glob '!**/build/**'
```

Then list missing cases explicitly rather than assuming symmetry.

## Wiring Conventions

- Spring Boot examples usually use configuration classes and constructor injection.
- Quarkus examples usually use CDI producer/configuration classes and field or constructor injection.
- Micronaut examples usually use `@Factory` classes and constructor injection.
- Native examples may need reflection/native-image support. Compare with a native sibling before changing.

## Locking Convention

For deposit-style concurrent writes, lock in the caller:

```java
try (var lease = lockProvider.acquire("wallet:" + id, Duration.ofSeconds(10))) {
    return executor.execute(...);
}
```

Do not move this lock into `Action.perform()` when the intent is commit serialization.
