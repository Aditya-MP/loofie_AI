#!/usr/bin/env bash
# Installs Temurin JDK 21 + Apache Kafka 4.3.2 (KRaft, single node) under ~/opt. No sudo.
set -euo pipefail
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz"
KAFKA_VER=4.3.2
KAFKA_URL="https://downloads.apache.org/kafka/${KAFKA_VER}/kafka_2.13-${KAFKA_VER}.tgz"

mkdir -p ~/opt && cd ~/opt

if [ ! -d ~/opt/jdk21 ]; then
  curl -sfL -o jdk.tgz "$JDK_URL"
  curl -sfL -o jdk.sha "${JDK_URL}.sha256.txt"
  echo "$(awk '{print $1}' jdk.sha)  jdk.tgz" | sha256sum -c -
  mkdir jdk21 && tar -xzf jdk.tgz -C jdk21 --strip-components=1 && rm jdk.tgz jdk.sha
fi

if [ ! -d ~/opt/kafka ]; then
  curl -sfL -o kafka.tgz "$KAFKA_URL"
  curl -sfL -o kafka.sha512 "${KAFKA_URL}.sha512"
  expected=$(tr -d ' \n' < kafka.sha512 | sed 's/^.*://' | tr 'A-F' 'a-f')
  actual=$(sha512sum kafka.tgz | awk '{print $1}')
  [ "$expected" = "$actual" ] || { echo "Kafka checksum mismatch"; exit 1; }
  echo "kafka.tgz: OK"
  mkdir kafka && tar -xzf kafka.tgz -C kafka --strip-components=1 && rm kafka.tgz kafka.sha512
fi

# Env for future shells
grep -q 'opt/jdk21' ~/.bashrc || cat >> ~/.bashrc <<'EOF'
export JAVA_HOME="$HOME/opt/jdk21"
export KAFKA_HOME="$HOME/opt/kafka"
export PATH="$JAVA_HOME/bin:$KAFKA_HOME/bin:$PATH"
EOF
export JAVA_HOME="$HOME/opt/jdk21" KAFKA_HOME="$HOME/opt/kafka"
export PATH="$JAVA_HOME/bin:$KAFKA_HOME/bin:$PATH"

# Single-node KRaft config with data under ~/kafka-data (not /tmp, which WSL wipes)
CFG=~/opt/kafka/config/loofie-local.properties
if [ ! -f "$CFG" ]; then
  cp ~/opt/kafka/config/server.properties "$CFG"
  sed -i 's#^log.dirs=.*#log.dirs='"$HOME"'/kafka-data#' "$CFG"
  # advertise localhost so Windows apps can connect via WSL localhost forwarding
  sed -i 's#^advertised.listeners=.*#advertised.listeners=PLAINTEXT://localhost:9092,CONTROLLER://localhost:9093#' "$CFG"
  # local dev: no automatic topic creation, topics are created explicitly
  echo 'auto.create.topics.enable=false' >> "$CFG"
fi

if [ ! -f ~/kafka-data/meta.properties ]; then
  kafka-storage.sh format --standalone -t "$(kafka-storage.sh random-uuid)" -c "$CFG"
fi

java -version 2>&1 | head -1
kafka-topics.sh --version
echo "INSTALL_OK"
