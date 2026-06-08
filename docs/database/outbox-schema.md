# Outbox schema

This page has moved.

The SQL reference now lives at [Framework tables](tables.md), because the schema reference covers more than the base event table:

- [`eventlog.events`](tables/events.md) — committed action/event rows written by the default event persister.
- [`eventlog.event_notifications`](tables/event-notifications.md) — optional local-event-handler delivery rows.
- [`scheduled_tasks`](tables/scheduled-tasks.md) — optional db-scheduler table used by distributed jobs and local handlers.

Keep using this URL for old links, but use [Framework tables](tables.md) for new references.
