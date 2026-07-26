# Tasks

Nothing here has been started. Priority is **low** - see `proposal.md` for the two conditions that
would raise it to medium.

## 0. Decide the open questions first

- [ ] 0.1 Settle the field name: `driverProperties` / `dataSourceProperties` / `properties`. This is
      the external config key, so it is effectively permanent once documented. Note the Jackson
      property name is derived from the **builder method name** because of
      `@JsonPOJOBuilder(withPrefix = "")`.
- [ ] 0.2 Settle the value type. `Map<String, String>` binds cleanly through the strict
      `JavaPropsMapper`; verify a nested map actually round-trips through the flat-property walk all
      three DI integrations use, since every existing field is a scalar and a map is new shape.
- [ ] 0.3 Decide the `user` / `password` policy: reject those two keys, or document precedence.
      Recommendation is reject - two sources of truth for credentials is a trap.

## 1. Core

- [ ] 1.1 Add the field to `DataSourceConfig`: public final field, constructor assignment, builder
      field defaulting to an empty map, builder method. Copy the structure of an existing optional
      field.
- [ ] 1.2 Forward in `ConnectionProvider.hikariConnectionProvider`, next to the existing
      `cfg.leakDetectionThreshold.ifPresent(...)` calls, via
      `HikariConfig.addDataSourceProperty(key, value)`.
- [ ] 1.3 Defensive copy on construction, so the caller cannot mutate the map afterwards - matches
      the immutability the rest of the config objects hold to.

## 2. Tests

- [ ] 2.1 `ShardingConfigYamlTest` hand-rolls `toDataSourceConfig(Map)` with an explicit branch per
      optional field and does **not** use Jackson. Without a branch for the new field the key is
      silently dropped and the test still passes while asserting nothing. Add the branch.
- [ ] 2.2 Binding tests in all three DI suites (kebab and camel spellings). Note the asymmetry:
      Micronaut has three optional-field tests, Quarkus two, Spring none - so Spring will need a
      nested `OptionalFields` class if the coverage is to be even.
- [ ] 2.3 Integration test proving a property actually reaches the driver. `socketTimeout` on
      PostgreSQL is a good probe: set it low, block the connection, assert the timeout fires. A
      binding test alone proves only that the value was stored, not that it was forwarded.
- [ ] 2.4 Test the `user`/`password` policy chosen in 0.3.

## 3. Docs

- [ ] 3.1 There is currently **no page that tabulates `DataSourceConfig` options at all** - the
      fields appear only inside YAML samples, plus two prose sentences at
      `docs/database/keyed-locks.md:182` and its website mirror that enumerate accepted leaf
      spellings. Consider adding a proper reference table as part of this change; it is the natural
      home for the new field.
- [ ] 3.2 Document the per-dialect precedence between URL query parameters and driver properties,
      **after testing it** on each driver rather than assuming.
- [ ] 3.3 Worked examples worth including, all Ekbatan-specific rather than generic:
      - `socketTimeout` (PostgreSQL) - the documented mitigation for the keyed-lock
        network-partition limitation in `docs/database/keyed-locks.md`, currently described as
        something you set on the JDBC URL by hand.
      - `reWriteBatchedInserts=true` (PostgreSQL) / `rewriteBatchedStatements=true` (MySQL,
        MariaDB) - `ActionExecutor` always writes through the batch methods, so these apply to
        nearly every action.
      - `useLocalSessionState=true` (MySQL, MariaDB) - removes the per-transaction
        `SET autocommit=0` / `=1` round-trip pair. This is the safe way to recover that cost; a
        pool-level `autoCommit=false` was investigated and rejected because it silently discards
        every write made outside an explicit transaction.
- [ ] 3.4 Mirror everything to `website/src/pages/`, per the docs-sync rule.

## 4. Follow-on

- [ ] 4.1 Once this lands, revisit `docs/database/keyed-locks.md`'s known-limitation wording: the
      mitigation stops being "edit the JDBC URL" and becomes a first-class config option.
