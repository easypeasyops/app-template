"""A minimal service with what the platform's service chart expects of an app:

* port 8000: the app's routes, `/healthz` (liveness: the process runs) and `/readyz`
  (readiness: it can serve);
* port METRICS_PORT (9100): `/metrics` for Prometheus, on a port of its own so the public
  port carries none of it. `http_requests_total` and `http_request_duration_seconds` are
  the names the platform's SLO rules, canary analysis and dashboard read;
* logs: JSON lines on stdout (app/logs.py), each request's carrying its ID: the
  X-Request-ID the platform's front door gave it, which the response sends back.

`python -m app.main`. Replace `hello` with the app; keep the probes and the metrics.
"""

import logging
import os
import re
import time
import uuid
from typing import Final

import uvicorn
from prometheus_client import Counter, Histogram, start_http_server
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, PlainTextResponse, Response
from starlette.routing import Route
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.logs import configure_logging, request_id_var

REQUESTS = Counter("http_requests_total", "Requests served", ["method", "route", "status"])
# The default buckets; an SLO's latency threshold must be one of their edges
# (deploy/prometheus/slos.yaml).
LATENCY = Histogram("http_request_duration_seconds", "Time to answer", ["method", "route"])


async def hello(request: Request) -> Response:
    return JSONResponse({"hello": "world"})


async def healthz(request: Request) -> Response:
    return PlainTextResponse("ok")


async def readyz(request: Request) -> Response:
    # Answer 503 here while something the app can't serve without (its database, a broker)
    # is unreachable: the pod then gets no traffic, and is not restarted.
    return PlainTextResponse("ok")


ROUTES = [Route("/", hello), Route("/healthz", healthz), Route("/readyz", readyz)]
_PATHS = frozenset(route.path for route in ROUTES)

REQUEST_ID_HEADER: Final = b"x-request-id"
# A caller's request ID is kept only if it's short and plain: anything else could inject
# log lines or bloat log storage, so a new one replaces it.
_VALID_REQUEST_ID: Final = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
# Probes are frequent and uninteresting: logged at DEBUG, not INFO.
_QUIET_PATHS: Final = frozenset({"/healthz", "/readyz"})
access_log = logging.getLogger("app.access")


class RequestContext:
    """For every request: its ID (the incoming X-Request-ID, else a new one), sent back in
    the response's X-Request-ID and on every log line logged while it is served; then one
    access log line, and its count and time, labelled by route, never by raw path: one
    series per ID or per scanner's guess would grow without bound. A route with a path
    parameter is labelled by its template (`/items/{id}`)."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        incoming = dict(scope["headers"]).get(REQUEST_ID_HEADER, b"").decode("latin-1")
        request_id = incoming if _VALID_REQUEST_ID.match(incoming) else uuid.uuid4().hex
        token = request_id_var.set(request_id)
        status = 500  # what the client sees if the app raises before responding
        start = time.perf_counter()

        async def send_and_note(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                message["headers"] = [
                    *message.get("headers", []),
                    (REQUEST_ID_HEADER, request_id.encode()),
                ]
            await send(message)

        try:
            await self.app(scope, receive, send_and_note)
        finally:
            elapsed = time.perf_counter() - start
            method = scope["method"]
            route = scope["path"] if scope["path"] in _PATHS else "unmatched"
            REQUESTS.labels(method, route, str(status)).inc()
            LATENCY.labels(method, route).observe(elapsed)
            access_log.log(
                logging.DEBUG if scope["path"] in _QUIET_PATHS else logging.INFO,
                "request",
                extra={
                    "method": method,
                    "route": route,
                    "status": status,
                    "duration_ms": round(elapsed * 1000, 2),
                },
            )
            request_id_var.reset(token)


app = RequestContext(Starlette(routes=ROUTES))


def main() -> None:  # pragma: no cover  # scripts/smoke.sh runs it, in the image
    configure_logging(os.environ.get("LOG_LEVEL", "INFO"))
    start_http_server(int(os.environ.get("METRICS_PORT", "9100")))
    # The kubelet and the Service reach the pod's IP. uvicorn handles SIGTERM itself: it
    # finishes the requests in hand, then exits 0. log_config=None: configure_logging's.
    uvicorn.run(app, host="0.0.0.0", port=8000, log_config=None)  # noqa: S104


if __name__ == "__main__":  # pragma: no cover
    main()
