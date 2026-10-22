"""Retry with exponential backoff, error logging, and "needs a human" escalation."""
import random
import time
from datetime import datetime

from .config import RETRY_ATTEMPTS, RETRY_BASE_SEC, RETRY_MAX_SEC


class TransientError(RuntimeError):
    """Network failure, timeout, 5xx - worth retrying."""


class RateLimited(TransientError):
    def __init__(self, msg, retry_after=None):
        super().__init__(msg)
        self.retry_after = retry_after


class PermanentError(RuntimeError):
    """Bad credentials, rejected content, missing config - retrying won't help."""


def backoff_delays(attempts=RETRY_ATTEMPTS, base=RETRY_BASE_SEC, cap=RETRY_MAX_SEC, jitter=True):
    for i in range(attempts - 1):
        d = min(cap, base * 2 ** i)
        yield d * random.uniform(0.8, 1.2) if jitter else d


def with_retry(fn, *, where, conn=None, notify=None, attempts=RETRY_ATTEMPTS, sleep=time.sleep, jitter=True):
    """Run fn(); retry TransientError with exponential backoff.

    Successful retries are only logged. The owner is notified only when manual
    intervention is required: a PermanentError, or retries exhausted.
    """
    delays = list(backoff_delays(attempts, jitter=jitter))
    for i in range(attempts):
        try:
            return fn()
        except TransientError as e:
            last, needs_human = e, i == attempts - 1
            _log(conn, where, e, i + 1, needs_human)
            if needs_human:
                break
            delay = delays[i]
            if isinstance(e, RateLimited) and e.retry_after:
                delay = max(delay, float(e.retry_after))
            sleep(delay)
        except PermanentError as e:
            last = e
            _log(conn, where, e, i + 1, True)
            break
    if notify:
        notify(f"⚠️ Manual intervention needed - {where}: {last.__class__.__name__}: {last}")
    raise last


def _log(conn, where, err, attempt, needs_human):
    if conn is None:
        return
    conn.execute("INSERT INTO error_log (ts, where_, error, attempts, needs_human) VALUES (?,?,?,?,?)",
                 (datetime.now().isoformat(timespec="seconds"), where,
                  f"{err.__class__.__name__}: {err}", attempt, int(needs_human)))
    conn.commit()
