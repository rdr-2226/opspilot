# Pod OOMKilled / Java OutOfMemoryError

## Symptoms
- `java.lang.OutOfMemoryError: Java heap space`
- Kubernetes `reason=OOMKilled`, exit code 137, `CrashLoopBackOff`
- High GC overhead and long GC pauses before the crash

## Diagnosis
1. Identify the operation running at crash time (batch job, large query, export).
2. Compare JVM max heap with the container memory limit.
3. Capture a heap dump (`-XX:+HeapDumpOnOutOfMemoryError`) to find the objects filling memory.

## Fix
1. Stop or pause the batch job that triggers the crash so the pod can recover.
2. Change the code to stream or paginate data instead of loading everything into memory.
3. If usage is legitimately higher, raise the memory limit and set `-XX:MaxRAMPercentage=75`.
4. Add alerts on heap usage above 85%.
