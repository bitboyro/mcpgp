# 0015. A PostgreSQL store next to the file store

- Status: Accepted
- Date: 2026-10-11
- Deciders: Roman Botnari

## Context

The platform kept everything in a directory (the file store). That is enough on one machine, but a
shared installation at a company wants its data where its other data is: a database with backups,
monitoring and access control the operations team already runs. A database must not become a
requirement, though: a trial, a laptop and small teams should keep working with a directory.

## Decision

- A second implementation of the store interface, **PostgreSQL**, chosen with `storage.type`. The
  file store stays the default. Both pass the same contract tests, and CI runs every platform test
  on both.
- Records are stored as JSON documents in a few tables of one schema (`json`, which keeps key order),
  not as a normalized relational model: the store interface works with whole records, and the same
  records in both stores keep export, import and `copy-store` trivial.
- File contents (version files, build packages, logs) live in the database as `bytea`: one thing to
  back up. Object storage can come later if packages grow large.
- One platform process per store, enforced with an advisory lock. Builds and **Try it** sessions are
  in-process; several nodes would need a shared build queue first.
- No migration framework before 1.0.0: the tables are created when missing, and the store format is
  recorded and checked as in the file store.
- Dependencies: the PostgreSQL JDBC driver and HikariCP (both versions managed by Spring Boot).

## Consequences

- Every new store method is written twice and covered by the shared tests.
- `copy-store` moves an installation from files to PostgreSQL without an export round trip.
- Horizontal scaling stays out of scope until there is a need; the lock makes a second process fail
  loudly instead of corrupting data.

## Alternatives considered

- PostgreSQL only: drops the zero-setup path.
- An ORM with a relational model (JPA): more code and migrations for records the platform only reads
  and writes whole.
- Files on a volume, records in the database: two things to back up and keep consistent.
