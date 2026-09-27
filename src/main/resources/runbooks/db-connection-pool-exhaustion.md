# Database connection pool exhaustion

## Symptoms
- `Connection is not available, request timed out` from HikariCP
- `CannotGetJdbcConnectionException`, HTTP 500/503 on write endpoints
- `active` connections equal `maximumPoolSize`, many threads `waiting`
- Often follows a traffic spike or slow queries holding connections

## Diagnosis
1. Check pool metrics: active, idle, waiting (Grafana dashboard "DB pools").
2. Look for slow queries in logs or `pg_stat_statements`; missing indexes are the usual cause.
3. Check whether the database itself is healthy (CPU, max_connections).

## Fix
1. Mitigate: scale the service horizontally only if the database has spare `max_connections`.
2. Kill or optimise long-running queries; add the missing index (e.g. `CREATE INDEX CONCURRENTLY`).
3. Temporarily raise `maximumPoolSize` (for example 10 -> 20) if the database can handle it.
4. Set a sensible `connectionTimeout` and a statement timeout so slow queries fail fast.
5. After recovery, close the circuit breaker and watch error rate for 15 minutes.
