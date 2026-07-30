#!/usr/bin/env bash
# Start CodeWiki + backend + frontend for local development (Linux).
# Usage: ./start-dev.sh
# Mirrors start-dev.ps1

set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "${ROOT}"

stop_listen_port() {
  local port="$1"
  local pids=""
  if command -v ss >/dev/null 2>&1; then
    pids=$(ss -ltnp "sport = :${port}" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u || true)
  fi
  if [[ -z "${pids}" ]] && command -v lsof >/dev/null 2>&1; then
    pids=$(lsof -tiTCP:"${port}" -sTCP:LISTEN 2>/dev/null || true)
  fi

  local pid
  for pid in ${pids}; do
    [[ -z "${pid}" || "${pid}" == "0" ]] && continue
    local name
    name=$(ps -p "${pid}" -o comm= 2>/dev/null || echo "pid")
    echo "Stopping ${name} (PID ${pid}) on port ${port}..."
    kill -TERM "${pid}" 2>/dev/null || true
  done
  sleep 1
  for pid in ${pids}; do
    [[ -z "${pid}" || "${pid}" == "0" ]] && continue
    if kill -0 "${pid}" 2>/dev/null; then
      kill -KILL "${pid}" 2>/dev/null || true
    fi
  done
}

codewiki_healthy() {
  curl -fsS --max-time 3 "http://127.0.0.1:8001/api/health" 2>/dev/null \
    | grep -q '"status"[[:space:]]*:[[:space:]]*"ok"'
}

# Load env vars from backend/.env into this process (and children).
env_file="${ROOT}/backend/.env"
if [[ -f "${env_file}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source <(grep -vE '^\s*(#|$)' "${env_file}" | sed 's/\r$//')
  set +a
  echo "Loaded environment from backend/.env"
fi

echo "Stopping previous backend/frontend (ports 8000 / 5173)..."
stop_listen_port 8000
stop_listen_port 5173
sleep 1

# Skip docker recreate when CodeWiki is already healthy (avoids killing in-flight analyze)
if codewiki_healthy; then
  echo "CodeWiki already healthy - skip docker recreate."
else
  echo "Starting postgres + codewiki..."
  if ! docker compose up -d postgres codewiki; then
    echo "Docker Compose failed. Install/start Docker, then retry." >&2
    exit 1
  fi

  deadline=$((SECONDS + 180))
  until codewiki_healthy; do
    if (( SECONDS >= deadline )); then
      echo "CodeWiki did not become healthy in time. Check: docker compose logs -f codewiki" >&2
      exit 1
    fi
    sleep 3
  done
  echo "CodeWiki is healthy."
fi

mkdir -p "${ROOT}/backend/data/repos" "${ROOT}/logs"
chmod +x "${ROOT}/backend/run.sh" 2>/dev/null || true
chmod +x "${ROOT}/backend/mvnw" 2>/dev/null || true

open_or_bg() {
  local title="$1"
  local workdir="$2"
  local cmd="$3"
  local logfile="$4"

  if [[ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]]; then
    if command -v gnome-terminal >/dev/null 2>&1; then
      gnome-terminal --title="${title}" --working-directory="${workdir}" -- bash -lc "${cmd}; exec bash"
      return
    fi
    if command -v konsole >/dev/null 2>&1; then
      konsole --new-tab -p tabtitle="${title}" -e bash -lc "cd '${workdir}'; ${cmd}; exec bash" &
      return
    fi
    if command -v xfce4-terminal >/dev/null 2>&1; then
      xfce4-terminal --title="${title}" --working-directory="${workdir}" -e "bash -lc '${cmd}; exec bash'" &
      return
    fi
    if command -v xterm >/dev/null 2>&1; then
      xterm -T "${title}" -e bash -lc "cd '${workdir}'; ${cmd}; exec bash" &
      return
    fi
  fi

  # Headless / remote SSH: run in background with logs (like Start-Process without a GUI).
  echo "No GUI terminal found — running ${title} in background (log: ${logfile})"
  (
    cd "${workdir}"
    # shellcheck disable=SC2086
    nohup bash -lc "${cmd}" >"${logfile}" 2>&1 &
    echo $! >"${logfile}.pid"
  )
  echo "  PID $(cat "${logfile}.pid")"
}

echo "Starting backend..."
open_or_bg "RepoPilot backend" "${ROOT}/backend" "./run.sh" "${ROOT}/logs/backend.log"

echo "Starting frontend..."
open_or_bg "RepoPilot frontend" "${ROOT}/frontend" \
  'if [[ ! -d node_modules ]]; then npm install; fi; npm run dev' \
  "${ROOT}/logs/frontend.log"

echo ""
echo "Frontend: http://localhost:5173"
echo "Backend:  http://localhost:8000"
echo "CodeWiki: http://127.0.0.1:8001"
echo ""
echo "Note: during knowledge build, do NOT re-run this script or docker compose up."
echo "Headless logs: logs/backend.log  logs/frontend.log"
echo "After start, verify backend:  curl -sI http://127.0.0.1:8000/auth/github"
