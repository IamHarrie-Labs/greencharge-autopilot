#!/usr/bin/env bash
# Sends a traffic burst at GreenCharge for the monitoring step of the brief.
# The curl loops run inside the kind control plane node, so nothing needs to be
# installed or pulled. Watch Grafana (http://localhost:3000) or Prometheus while
# it runs.
set -euo pipefail
NS="${NS:-enact}"
SECONDS_TO_RUN="${1:-180}"
WORKERS="${WORKERS:-12}"

IP=$(kubectl -n "$NS" get svc greencharge -o jsonpath='{.spec.clusterIP}')
echo "Sending traffic to $IP for $SECONDS_TO_RUN s with $WORKERS workers"
docker exec enact-dev-control-plane sh -c "
end=\$((\$(date +%s) + $SECONDS_TO_RUN))
for i in \$(seq $WORKERS); do
  ( while [ \$(date +%s) -lt \$end ]; do
      curl -s -o /dev/null -X POST -H 'Content-Type: application/json' -d '{}' http://$IP:8080/route
      curl -s -o /dev/null http://$IP:8080/chargers
    done ) &
done
wait"
echo "Done"
