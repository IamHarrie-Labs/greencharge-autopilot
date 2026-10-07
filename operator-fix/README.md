# ENACT policy operator (APPLPM): metrics auth and placement fixes

`applpm-metrics-auth-and-placement.patch` applies to
[application-policy-model](https://gitlab.eclipse.org/eclipse-research-labs/enact-project/application-policy-model)
at the commit in `UPSTREAM_COMMIT`. `make operator-fix` (repository root)
builds it, loads it into the kind cluster and configures the deployment.

## What was wrong

As shipped in the hackathon cluster, the operator cannot place anything:

1. **No metrics configured.** The Helm values leave `METRICS_API_URL` and
   `CLUSTER_NAME` empty, so it starts with `NoopMetrics` and every
   RuntimePolicy reports `no available metrics`.
2. **No way to authenticate.** Once pointed at the TDCME Monitor API, every
   request returns **401**: `getJson` never sends an `Authorization` header and
   there is no setting for a token. Verified on the cluster:

   ```text
   GET /availability/node/enact-dev-worker            -> 401
   GET /availability/node/enact-dev-worker  + Bearer  -> 200 {"node":"enact-dev-worker","availability":1.0}
   GET /availability/cluster/dev            + Bearer  -> 200 {"cluster":"dev","availability":1.0}
   GET /latency/dev                         + Bearer  -> 200 {"cluster":"dev","avg_latency_seconds":0.0,...}
   ```

   The three endpoints the operator calls exist and return the fields its code
   parses; only the token was missing.
3. **A chosen node fails its own Hard rules.** When a node is already chosen,
   the controller re-checks it with metrics that carry no region, zone or green
   ratio (those come from node labels, which were only applied in the
   candidate loop). With the challenge's required `location: {mode: Hard,
   regions: [eu-west]}` the check always fails (`node region "" not in allowed
   regions`), so every reconcile re-ranks all nodes and breaks ties at random.
4. **One node's missing metrics discards the rest, inconsistently.** In the
   candidate loop, a metrics error cleared the candidate list and stopped the
   loop but left earlier winners in place, so the status could say
   `no available metrics` while still naming a chosen node.
5. **Nodes excluded by Hard rules were not explained.** Only ranking losers
   appeared in `status.rejectedNodes`.

## What the patch changes

| File | Change |
|---|---|
| `custom_metrics.go` | Sends `Authorization: Bearer <token>` from `METRICS_API_TOKEN`, or the file named by `METRICS_API_TOKEN_FILE` (a mounted Secret). A 401 without a token now says which setting is missing; other errors include the URL. |
| `runtimepolicy_controller.go` | `enrichFromNode` fills region, zone, green ratio and readiness from the node in both the sticky path and the candidate loop; the sticky path also re-checks the policy's node selector. A node whose metrics fail is recorded as rejected with the error and the others are still ranked. Nodes failing a Hard rule are listed in `status.rejectedNodes` with the rule that excluded them. |
| `custom_metrics_test.go` | 4 tests: token sent, 401 explained, token from file, Hard region rule passes once labels are applied (and fails without, so the test covers the bug). |

```text
$ go test -run 'TestApiMetrics|TestTokenFromEnv|TestEnrichFromNode' ./internal/controller/
--- PASS: TestApiMetricsSendsBearerToken
--- PASS: TestApiMetricsWithoutTokenExplains401
--- PASS: TestTokenFromEnvReadsFile
--- PASS: TestEnrichFromNodeKeepsHardRegionRule
ok
```

Behaviour that is unchanged and worth knowing: the operator only writes
`status.chosenNode`; it never moves a running workload. Applying that decision
is what the GreenCharge autopilot does.

Licence: Apache-2.0, as upstream.
