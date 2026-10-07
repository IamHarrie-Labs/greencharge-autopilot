# Slide content for the organisers' 3-slide template

## Slide 1: Project

**GreenCharge Autopilot**
ENACT policies that actually change the running app, and prove it.

Challenge 3 (ENACT): Kubernetes Dynamic Adaptation
GitHub: `<repo URL>`
Team: Harrie

## Slide 2: Summary

ENACT can say what a workload should look like: the Application Controller
recommends `scale_up`/`scale_down`, the policy operator picks a node. Nothing
then changes the running workload, and in the hackathon cluster the operator
could not decide at all (TDCME answered 401).

We closed the loop:

- **Fixed the ENACT operator**: Bearer-token auth, a Hard-rule bug that made it
  re-rank every 30 s, and explained rejections. Patch + tests, ready upstream.
- **GreenCharge Autopilot**: detect → decide (ENACT AC + operator) → guard
  (Hard rules, cooldown) → apply → verify (rollout + health) → roll back.
- **Evidence timeline** for every adaptation: what was measured, what ENACT
  decided, what changed, whether it recovered.

## Slide 3: Highlights (measured on the challenge's own 3-node cluster)

- Out-of-policy app (0.5 CPU, wrong node) → **adapted and verified in 105 s**
- Move to a node outside `eu-west` → **rejected** by the Hard rule, app untouched
- Change that cannot become healthy → **rolled back** automatically
- Node leaves `eu-west` → operator re-decides, app moves, **no flapping** after
- Running it for real found 3 bugs in our own design (rollback that clobbered
  Helm, wrong rollout test, rejected patch as HTTP 500); all fixed and tested
- 13 Java + 4 Go tests; Apache-2.0
