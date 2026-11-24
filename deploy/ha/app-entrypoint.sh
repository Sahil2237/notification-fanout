#!/bin/sh
set -eu
export REDIS_CA_CERT="$(cat /tls/redis-ca.crt)"
export PG_CA_CERT="$(cat /tls/postgres-ca.crt)"
export KAFKA_CA_CERT="$(cat /tls/kafka-ca.crt)"
export KAFKA_CLIENT_CERT="$(cat /tls/app.crt)"
export KAFKA_CLIENT_KEY="$(cat /tls/app.key)"
exec java -jar /opt/app/notification-fanout.jar "$@" /opt/app/ha.yml
