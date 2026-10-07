# Fixes to the ENACT policy operator (APPLPM) for metrics access and placement

`applpm-metrics-auth-and-placement.patch` applies to
[application-policy-model](https://gitlab.eclipse.org/eclipse-research-labs/enact-project/application-policy-model)
at the commit named in `UPSTREAM_COMMIT`. Running `make operator-fix` from the
repository root builds the patched operator, loads it into the kind cluster and
sets up its deployment.

## What was wrong

In the hackathon cluster, the operator as shipped could not place anything.

1. No metrics were configured. The Helm values leave `METRICS_API_URL` and
   `CLUSTER_NAME` empty, so the operator starts with `NoopMetrics` and every
   RuntimePolicy reports `no available metrics`.
2. There was no way to log in. Once the operator is pointed at the TDCME
   Monitor API, every request comes back 401. `getJson` never sends an
   `Authorization` header and there is no setting for a token. We checked this
   on the cluster.

   ```text
   GET /availability/node/enact-dev-worker            -> 401
   GET /availability/node/enact-dev-worker  + Bearer  -> 200 {"node":"enact-dev-worker","availability":1.0}
   GET /availability/cluster/dev            + Bearer  -> 200 {"cluster":"dev","availability":1.0}
   GET /latency/dev                         + Bearer  -> 200 {"cluster":"dev","avg_latency_seconds":0.0,...}
   ```

   The three endpoints the operator calls exist and return the fields its code
   reads. The token was the only thing missing.
3. A node the operator had already chosen failed its own Hard rules. When a
   node is already chosen, the controller checks it again using metrics that
   have no region, zone or green ratio. Those values come from node labels,
   and the labels were only added inside the candidate loop. With the region
   rule the challenge requires (Hard, `eu-west` only) that check always failed
   with `node region "" not in allowed regions`. Every reconcile then ranked
   all nodes again and broke ties at random.
4. Missing metrics on one node threw away the rest, and not consistently. In
   the candidate loop a metrics error emptied the candidate list and stopped
   the loop, yet left earlier winners in place. The status could then say
   `no available metrics` and still name a chosen node.
5. Nodes ruled out by Hard rules were never explained. Only nodes that lost
   the ranking showed up in `status.rejectedNodes`.

## What the patch changes

| File | Change |
|---|---|
| `custom_metrics.go` | Sends a Bearer token in the `Authorization` header. The token comes from `METRICS_API_TOKEN`, or from the file named in `METRICS_API_TOKEN_FILE` (a mounted Secret). A 401 with no token configured now says which setting is missing, and other errors include the URL |
| `runtimepolicy_controller.go` | `enrichFromNode` fills in region, zone, green ratio and readiness from the node, both for an already chosen node and inside the candidate loop. An already chosen node is also checked against the policy's node selector again. A node whose metrics fail is recorded as rejected with the error, and the other nodes are still ranked. Nodes that fail a Hard rule are listed in `status.rejectedNodes` with the rule that excluded them |
| `custom_metrics_test.go` | Four tests. The token is sent, a 401 is explained, the token can be read from a file, and the Hard region rule passes once labels are applied. That last test fails without the patch, so it covers the bug |

```text
$ go test -run 'TestApiMetrics|TestTokenFromEnv|TestEnrichFromNode' ./internal/controller/
--- PASS: TestApiMetricsSendsBearerToken
--- PASS: TestApiMetricsWithoutTokenExplains401
--- PASS: TestTokenFromEnvReadsFile
--- PASS: TestEnrichFromNodeKeepsHardRegionRule
ok
```

One thing the patch does not change is worth knowing. The operator only writes
`status.chosenNode` and never moves a running workload. Carrying out that
decision is the job of the GreenCharge autopilot.

Licensed under Apache-2.0, the same as upstream.
