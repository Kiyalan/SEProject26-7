"""Wrapper that intercepts sys.exit / os._exit to trace CodeWiki crashes."""
import sys
import os
import traceback

_real_sys_exit = sys.exit
_real_os_exit = os._exit


def _debug_exit(code=0):
    """Called when sys.exit(code) is invoked – log the stack trace."""
    msg = f"[DEBUG-EXIT] sys.exit({code}) called from:\n"
    msg += "".join(traceback.format_stack())
    try:
        with open("/app/storage/exit_trace.log", "a") as f:
            f.write(msg + "\n")
    except Exception:
        pass
    # also print to stderr so Docker logs pick it up
    sys.stderr.write(msg)
    sys.stderr.flush()
    _real_sys_exit(code)


def _debug_os_exit(code=0):
    """Called when os._exit(code) is invoked – log the stack trace."""
    msg = f"[DEBUG-EXIT] os._exit({code}) called from:\n"
    msg += "".join(traceback.format_stack())
    try:
        with open("/app/storage/exit_trace.log", "a") as f:
            f.write(msg + "\n")
    except Exception:
        pass
    sys.stderr.write(msg)
    sys.stderr.flush()
    _real_os_exit(code)


# Patch exit functions
sys.exit = _debug_exit  # type: ignore[assignment]
os._exit = _debug_os_exit  # type: ignore[assignment]

# Also install a handler for SIGTERM/SIGINT to see if signal causes exit
import signal


def _signal_handler(signum, frame):
    msg = f"[DEBUG-SIGNAL] Received signal {signum} ({signal.Signals(signum).name}) from:\n"
    msg += "".join(traceback.format_stack(frame))
    try:
        with open("/app/storage/exit_trace.log", "a") as f:
            f.write(msg + "\n")
    except Exception:
        pass
    sys.stderr.write(msg)
    sys.stderr.flush()
    _real_sys_exit(0)


signal.signal(signal.SIGTERM, _signal_handler)
signal.signal(signal.SIGINT, _signal_handler)

# Now run the real codewiki entry point
from backend.app.cli import main

if __name__ == "__main__":
    sys.argv[0] = "codewiki"  # preserve original command name
    main()
