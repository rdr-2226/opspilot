# High latency from a downstream dependency

## Symptoms
- p95/p99 latency spikes, timeouts calling another service
- Thread pool saturation, retries piling up

## Diagnosis
1. Use traces to find which downstream call is slow.
2. Check that dependency's own dashboards and recent deployments.

## Fix
1. Add or tighten timeouts and enable a circuit breaker with a fallback.
2. Reduce retries with exponential backoff and jitter.
3. Roll back the downstream deployment if it caused the regression.
