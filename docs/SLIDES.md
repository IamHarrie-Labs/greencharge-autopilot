# Slide content for the organisers' 3 slide template

## Slide 1, the project

**GreenCharge Autopilot**
ENACT policies that change the running app, with proof that it worked.

Challenge 3 (ENACT), Kubernetes Dynamic Adaptation
GitHub https://github.com/IamHarrie-Labs/greencharge-autopilot
Team Harrie

## Slide 2, summary

ENACT can say what a workload should look like. The Application Controller
recommends `scale_up` or `scale_down` and the policy operator picks a node.
Nothing then changes the running workload, and in the hackathon cluster the
operator could not decide anything because TDCME refused it with 401.

We connected the pieces.

- We fixed the ENACT operator. It now logs in with a Bearer token, no longer
  ranks every node again every 30 s because of a Hard rule bug, and explains
  why it rejects a node. The patch has tests and is ready to go upstream.
- GreenCharge Autopilot detects a problem, takes the decision from the ENACT
  Application Controller and operator, checks it against the Hard rules and a
  cooldown, applies it, verifies the rollout and health, and rolls back if
  needed.
- Every adaptation is logged with what was measured, what ENACT decided, what
  changed and whether the app recovered.

## Slide 3, highlights from the challenge's own 3 node cluster

- App outside its policy (0.5 CPU on the wrong node) was adapted and verified in 105 s
- A move to a node outside `eu-west` was rejected by the Hard rule and the app was left alone
- A change that could never become healthy was rolled back automatically
- When a node left `eu-west` the operator picked again, the app moved, and nothing flipped back afterwards
- Running it for real exposed 3 bugs in our own design (a rollback that undid Helm's change, the wrong rollout check, a refused patch shown as HTTP 500). All three are fixed and tested
- 13 Java tests and 4 Go tests, Apache-2.0
