# Wiring

How to actually run Ekbatan in an application. Start here:

- **[Domain classes & the `@Ekbatan*` annotations](annotations.md)** — the five domain classes and the four annotations that hand them to a DI container. Framework-agnostic; the per-framework pages below all build on this.
- **[Wiring without DI](without-di.md)** — full plain-Java end-to-end snippet. Every class, every builder, every registration spelled out. Runs on plain `main()` or any plain-JVM container.

Then pick your DI framework:

- **[Wiring with Spring Boot](spring.md)** — one starter dependency, an `application.yml`, plus Spring auto-config / AOT / native specifics.
- **[Wiring with Quarkus](quarkus.md)** — extension dependency, `application.properties`, plus build-step / Jandex / classloader / HikariCP-on-native specifics.
- **[Wiring with Micronaut](micronaut.md)** — integration jar + annotation processor, plus `EkbatanStereotypeVisitor` / transitive-jar processing specifics.

Read [annotations.md](annotations.md) and [without-di.md](without-di.md) first if you want to understand what the framework actually does. Read whichever framework page matches your stack when you want the shortest path to a running app.

← Back to [docs index](../README.md) · [Top README](../../README.md)
