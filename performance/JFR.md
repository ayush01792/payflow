# Targeted JFR recording

Build `docker build -f performance/Dockerfile.profiling -t payflow:profiling .`, load that image into kind, and temporarily use it only in the performance Deployment. It preserves the normal image's user, probes, read-only filesystem and resource limits. The JDK supplies jcmd; no diagnostic tooling is added to the production Dockerfile.

Select one ready performance Pod, then:

```bash
kubectl -n payflow-performance exec "$POD" -- /opt/java/openjdk/bin/jcmd 1 JFR.start name=payflow settings=profile duration=60s filename=/tmp/payflow-profile.jfr
# Run sustained k6 traffic through the ClusterIP while recording.
# After the 60-second recording finishes:
kubectl -n payflow-performance exec "$POD" -- cat /tmp/payflow-profile.jfr > performance/results/payflow-profile.jfr
jfr summary performance/results/payflow-profile.jfr
jfr print --events jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.GCPhasePause,jdk.SocketRead performance/results/payflow-profile.jfr > performance/results/profile.txt
```

Check JVM process ID with `jcmd -l` if your entrypoint differs. Compare sampled CPU and allocation stacks, lock/park/socket durations and GC with Micrometer. Samples are statistical; a small recording cannot prove absence of contention. Restore the ordinary image after diagnostics. Raw recordings and printed traces are ignored.

## Preferred Kubernetes method: restricted ephemeral diagnostic container

A fully warmed normal JRE Pod can be profiled without changing its image or restarting it. Load `payflow:profiling` into kind, then while steady traffic is running:

```bash
kubectl -n payflow-performance debug "pod/$POD" \
  --image=payflow:profiling --image-pull-policy=IfNotPresent \
  --target=payflow --profile=restricted --container=step15-jfr \
  --attach=false -- /opt/java/openjdk/bin/jcmd 1 JFR.start \
  name=steady settings=profile duration=60s filename=/tmp/payflow-steady.jfr
# Once that diagnostic container completes, its logs confirm the recording started.
kubectl -n payflow-performance logs "$POD" -c step15-jfr
# After the full recording duration has finished (not immediately after jcmd returns):
kubectl -n payflow-performance exec "$POD" -c payflow -- cat /tmp/payflow-steady.jfr \
  > performance/results/payflow-steady.jfr
jfr summary performance/results/payflow-steady.jfr
```

Use a new diagnostic container name on subsequent recordings. `--target` depends on the container runtime supporting shared process namespaces. This was verified with the local containerd/kind runtime, UID/GID **10001**, restricted security profile, no added capabilities, and diagnostic container exit code zero. jcmd attached to the normal app's PID 1. The ephemeral container exits after starting the recording; the app JVM records the file in its own writable /tmp. No host filesystem, privileged container or application-image modification is needed. Ephemeral container metadata remains until the Pod is normally replaced. This avoids interpreting fresh JVM startup/classloading as a steady performance hotspot.
