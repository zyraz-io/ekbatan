# DI config binding parity

## Context

The three DI integrations share a "Jackson hybrid" binding approach: walk the framework's flat
property names, canonicalize kebab-case to camelCase, and feed the result to a private strict
`JavaPropsMapper` with `FAIL_ON_UNKNOWN_PROPERTIES` enabled. Spring reads through
`environment.getProperty(name)` and Micronaut through a `v != null` check; the Quarkus
implementation enumerates `config.getPropertyNames()` and reads
`config.getOptionalValue(name, String.class)`.

Those two Quarkus choices diverge from the other two integrations in ways that break real
deployments: SmallRye publishes a synthesized `toLowerCaseAndDotted` alias for every environment
variable, and its `String` converter maps `""` to absent. See `design.md` finding 4.

## ADDED Requirements

### Requirement: Environment variables bind identically across all three integrations

The Quarkus binder SHALL construct Jackson keys from canonical property names, never from names
synthesized by a config source for lookup purposes. Any property that Spring or Micronaut binds
from an environment variable SHALL bind to the same value on Quarkus.

#### Scenario: A database secret is injected as an environment variable

- **GIVEN** a containerized Quarkus application whose shard topology is in
  `application.properties` and whose password is supplied as
  `EKBATAN_SHARDING_GROUPS_0_MEMBERS_0_CONFIGS_PRIMARYCONFIG_PASSWORD`
- **WHEN** the application starts
- **THEN** the password SHALL bind to the corresponding `DataSourceConfig`
- **AND** startup SHALL NOT fail with `Failed to bind 'ekbatan.sharding' configuration`

#### Scenario: Synthesized aliases never reach the mapper

- **WHEN** `config.getPropertyNames()` returns both the raw and the lower-cased dotted form of an
  environment variable
- **THEN** at most one canonical key SHALL be written into the `Properties` handed to the strict
  `JavaPropsMapper`

This applies to both copy loops - the sharding binder and the shared `bindSubtree` helper used by
the jobs and local-event-handler configs.

### Requirement: Empty property values are preserved

A property explicitly set to the empty string SHALL bind as `""`, not as absent, on every DI
integration. `DataSourceConfig` documents its password as "Required (may be the empty string)".

#### Scenario: Empty password

- **GIVEN** `...primaryConfig.password=` in the configuration
- **WHEN** the application starts on Quarkus
- **THEN** `DataSourceConfig.password` SHALL be `""`
- **AND** startup SHALL NOT fail with "password is required"

### Requirement: Build-time gates and runtime binding use the same key form

Where a Quarkus build-time `@IfBuildProperty` gate guards a component whose runtime configuration
is bound through the canonicalizing binder, the gate SHALL accept the same key spellings the
binder accepts.

#### Scenario: camelCase spelling enables the job

- **GIVEN** the local-event-handler enable flag written in the camelCase form the binder accepts
- **WHEN** the application is built and started
- **THEN** the handling job SHALL be active, matching what the bound configuration reports
