# Local performance environment plan

Only the local `kind-payflow` development cluster is in scope. No cloud resources.

1. Save the normal HPA manifest and current replica count. Pause normal PayFlow by removing its HPA and scaling its Deployment to zero. Existing PostgreSQL data and PVCs remain intact.
2. Enable `pg_stat_statements` by restarting the shared development PostgreSQL StatefulSet. Create separate `payflow_performance` database; do not edit normal application rows.
3. Create `payflow-performance` namespace using performance/k8s. Copy only the four existing local app secret values directly between Kubernetes Secrets without printing or saving their contents. Create a distinct load-test merchant/key.
4. Keep two performance replicas initially. Redis uses database 1. The sequential test shares the existing Kafka topics and consumer group: normal and performance apps must never run together, and the outbox must drain before restoring normal apps. No topic deletion.
5. Apply a high create limit only to the performance ConfigMap. Run a separate low-limit test there. The normal ConfigMap remains unchanged.
6. Run bounded loads, 15-minute soak, one 60-second JFR recording, two-versus-three replica comparison, and a brief Kafka outage. No intentional PostgreSQL outage load.
7. Stop performance replicas, restore the normal local HPA/Deployment and PostgreSQL startup arguments. Preserve reports and isolated test database until cleanup is requested. No user files or persistent volumes deleted.

This shares infrastructure and the Docker VM, so results measure this 8 GB Mac, not production capacity. Six application replicas are unsafe locally; a capped three-replica HPA experiment will be used if headroom permits.

Retained Step 15 setting: the local PostgreSQL CPU limit is 1 core after the paired comparison. Restoration removes temporary query-statistics startup arguments and restores normal consumers; it intentionally preserves this measured source setting.
