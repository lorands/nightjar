# Sourced by devbox's init_hook — environment for local services.
# Integration tests connect to: PostgreSQL localhost:6543 (user postgres, db postgres),
# RabbitMQ localhost:5672 (guest/guest).
# PostgreSQL uses 6543 on purpose: no collision with system/docker postgres
# instances on the standard ports (5432/5433).

export PGPORT=6543

RABBITMQ_HOME="$PWD/.devbox/virtenv/rabbitmq"
export RABBITMQ_MNESIA_BASE="$RABBITMQ_HOME/mnesia"
export RABBITMQ_LOG_BASE="$RABBITMQ_HOME/log"
export RABBITMQ_PID_FILE="$RABBITMQ_HOME/rabbitmq.pid"
export RABBITMQ_ENABLED_PLUGINS_FILE="$RABBITMQ_HOME/enabled_plugins"
export RABBITMQ_NODENAME="rabbit@localhost"
export RABBITMQ_NODE_IP_ADDRESS="127.0.0.1"
mkdir -p "$RABBITMQ_MNESIA_BASE" "$RABBITMQ_LOG_BASE"

# One-time PostgreSQL cluster initialization (PGDATA is set by devbox's postgres
# plugin, which pre-creates the directory — so check for an initialized cluster)
if [ -n "$PGDATA" ] && [ ! -f "$PGDATA/PG_VERSION" ]; then
  initdb -U postgres >/dev/null
fi
