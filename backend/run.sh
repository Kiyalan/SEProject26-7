#!/usr/bin/env bash
# RepoPilot backend launcher (Linux)
# Usage: ./run.sh
# Stops previous RepoPilot Java processes, rebuilds via Maven Wrapper, then starts the latest jar.
# Mirrors backend/run.ps1

set -euo pipefail
cd "$(dirname "$0")"

stop_existing_backend() {
  local pids=""
  if command -v ss >/dev/null 2>&1; then
    pids=$(ss -ltnp "sport = :8000" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u || true)
  fi
  if [[ -z "${pids}" ]] && command -v lsof >/dev/null 2>&1; then
    pids=$(lsof -tiTCP:8000 -sTCP:LISTEN 2>/dev/null || true)
  fi

  local pid
  for pid in ${pids}; do
    [[ -z "${pid}" || "${pid}" == "0" ]] && continue
    local cmd
    cmd=$(ps -p "${pid}" -o args= 2>/dev/null || true)
    if [[ "${cmd}" == *repopilot* || "${cmd}" == *RepoPilotApplication* || "${cmd}" == *repopilot-backend-1.0.0.jar* ]]; then
      echo "Stopping port-8000 backend PID=${pid} ..."
      kill -TERM "${pid}" 2>/dev/null || true
      # Give it a moment, then force if needed
      sleep 0.5
      if kill -0 "${pid}" 2>/dev/null; then
        kill -KILL "${pid}" 2>/dev/null || true
      fi
    fi
  done

  local java_pids
  java_pids=$(pgrep -f 'repopilot-backend-1\.0\.0\.jar|RepoPilotApplication|repopilot-backend' 2>/dev/null || true)
  for pid in ${java_pids}; do
    echo "Stopping stale backend PID=${pid} ..."
    kill -TERM "${pid}" 2>/dev/null || true
  done

  # Wait until the jar is unlocked (avoids mvnw repackage failures).
  local jar="target/repopilot-backend-1.0.0.jar"
  local deadline=$((SECONDS + 20))
  while [[ -f "${jar}" ]] && (( SECONDS < deadline )); do
    if lsof "${jar}" >/dev/null 2>&1; then
      sleep 0.5
      continue
    fi
    # Fallback: try opening for write via bash redirection
    if ( : >>"${jar}" ) 2>/dev/null; then
      break
    fi
    sleep 0.5
  done

  java_pids=$(pgrep -f 'repopilot-backend-1\.0\.0\.jar|RepoPilotApplication|repopilot-backend' 2>/dev/null || true)
  for pid in ${java_pids}; do
    kill -KILL "${pid}" 2>/dev/null || true
  done
  sleep 1
}

stop_existing_backend

mkdir -p data/repos

codeWikiUrl="${CODEWIKI_BASE_URL:-http://127.0.0.1:8001}"
codeWikiUrl="${codeWikiUrl%/}"
if curl -fsS --max-time 3 "${codeWikiUrl}/api/health" 2>/dev/null | grep -q '"status"[[:space:]]*:[[:space:]]*"ok"'; then
  echo "CodeWiki OK at ${codeWikiUrl}"
else
  echo "WARNING: CodeWiki is not reachable at ${codeWikiUrl}. Knowledge build/search will fail until you run: docker compose up -d postgres codewiki" >&2
fi

echo "Building backend package (Maven Wrapper)..."
mvnw="./mvnw"
if [[ ! -x "${mvnw}" ]]; then
  if [[ -f "${mvnw}" ]]; then
    chmod +x "${mvnw}"
  else
    echo "Maven Wrapper not found: ${mvnw}" >&2
    exit 1
  fi
fi

if ! "${mvnw}" -q package -DskipTests; then
  echo "Build failed. If the jar is locked, stop other Java processes and retry." >&2
  exit 1
fi

echo "Starting backend at http://localhost:8000 ..."
echo "Press Ctrl+C to stop"
exec java -jar target/repopilot-backend-1.0.0.jar
