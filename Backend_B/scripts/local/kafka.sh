#!/usr/bin/env bash
# Local single-node Kafka (KRaft) in WSL2 for Backend B development.
# Usage (from Windows):  wsl -d Ubuntu -- bash scripts/local/kafka.sh start|stop|status|topics
# Install first with scripts/local/install-kafka-wsl.sh
set -euo pipefail
export JAVA_HOME="$HOME/opt/jdk21"
export PATH="$JAVA_HOME/bin:$HOME/opt/kafka/bin:$PATH"
CFG="$HOME/opt/kafka/config/loofie-local.properties"
BS="localhost:9092"

# Topic name : partitions. Keep in sync with the architecture doc ("Kafka topics").
TOPICS=(
  core.order.v1:3
  core.job.v1:3
  core.creator.v1:3
  core.events.v1:3
  media.request.v1:3
  media.result.v1:3
  finance.payment.v1:3
  finance.payout.v1:3
)

up() { kafka-broker-api-versions.sh --bootstrap-server "$BS" >/dev/null 2>&1; }

case "${1:-status}" in
  start)
    if up; then echo "Kafka already running on $BS"; exit 0; fi
    kafka-server-start.sh -daemon "$CFG"
    for _ in $(seq 1 30); do up && { echo "Kafka up on $BS"; exit 0; }; sleep 1; done
    echo "Kafka did not start; see ~/opt/kafka/logs/server.log"; exit 1
    ;;
  stop)
    kafka-server-stop.sh || true
    ;;
  status)
    if up; then echo "Kafka running on $BS"; else echo "Kafka not running"; exit 1; fi
    ;;
  topics)
    up || { echo "Kafka not running"; exit 1; }
    for t in "${TOPICS[@]}"; do
      name="${t%%:*}"; parts="${t##*:}"
      kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists --topic "$name" --partitions "$parts" --replication-factor 1 >/dev/null
      kafka-topics.sh --bootstrap-server "$BS" --create --if-not-exists --topic "$name.DLT" --partitions "$parts" --replication-factor 1 >/dev/null
    done
    kafka-topics.sh --bootstrap-server "$BS" --list
    ;;
  *)
    echo "usage: $0 start|stop|status|topics"; exit 2
    ;;
esac
