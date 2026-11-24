"""Exercise the HTTP contract against a fresh database loaded with the demo fixture."""

import json
import sys
from concurrent.futures import ThreadPoolExecutor
from http.cookiejar import CookieJar
from threading import Barrier
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import HTTPCookieProcessor, Request, build_opener

BASE = sys.argv[1].rstrip("/")
REQUESTS = "/api/v1/replenishment-requests"


class Client:
    def __init__(self, username):
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))
        self.token = self.call("/api/v1/csrf", 200)[0]["token"]
        self.call("/api/v1/session", 204, form={"username": username, "password": "demo"})
        self.token = self.call("/api/v1/csrf", 200)[0]["token"]

    def call(self, path, expected, body=None, key=None, form=None):
        headers = {}
        data = None
        if form is not None or body is not None:
            headers["X-CSRF-TOKEN"] = self.token
            if form is None:
                data = json.dumps(body).encode()
                headers["Content-Type"] = "application/json"
            else:
                data = urlencode(form).encode()
                headers["Content-Type"] = "application/x-www-form-urlencoded"
        if key:
            headers["Idempotency-Key"] = key
        try:
            response = self.opener.open(Request(BASE + path, data, headers), timeout=12)
        except HTTPError as error:
            response = error
        with response:
            raw = response.read()
            assert response.code in (expected if isinstance(expected, tuple) else (expected,)), (
                path, response.code, raw
            )
            return json.loads(raw) if raw else None, response.headers.get("Location")


def submit(client, store, key, quantities):
    return client.call(REQUESTS, 201, {
        "sourceLocationId": "1", "destinationLocationId": str(store),
        "desiredReceiptDate": "2027-01-01", "allowPartial": False,
        "lines": [{"variantId": str(i), "requestedQty": qty} for i, qty in quantities],
    }, key)


def approve(client, request, expected, key):
    return client.call(f"{REQUESTS}/{request['id']}/approvals", expected, {
        "expectedVersion": request["version"],
        "lines": [{"requestItemId": line["id"], "approvedQty": line["requestedQty"]}
                  for line in request["lines"]],
    }, key)[0]


def together(first, second):
    gate = Barrier(2, timeout=5)

    def run(action):
        gate.wait()
        return action()

    with ThreadPoolExecutor(2) as pool:
        jobs = [pool.submit(run, action) for action in (first, second)]
        return [job.result() for job in jobs]


if __name__ == "__main__":
    store, duplicate, other = (Client(name) for name in ("store", "store", "other"))
    approver, concurrent = (Client("approver") for _ in range(2))
    assert len(store.call("/api/v1/catalog/variants?q=DOWN", 200)[0]["items"]) == 2
    first, replay = together(
        lambda: submit(store, 2, "duplicate", [(1, 7)]),
        lambda: submit(duplicate, 2, "duplicate", [(1, 7)]),
    )
    assert first == replay
    a = first[0]
    assert other.call(f"{REQUESTS}/{a['id']}", 403)[0]["code"] == "FORBIDDEN"
    assert len(approver.call(REQUESTS + "?status=SUBMITTED", 200)[0]["items"]) == 1

    rollback, _ = submit(store, 2, "rollback", [(1, 10), (2, 2)])
    assert approve(approver, rollback, 409, "rollback-approval")["code"] == "INSUFFICIENT_STOCK"
    assert store.call(f"{REQUESTS}/{rollback['id']}", 200)[0] == rollback

    b, _ = submit(other, 3, "competing", [(1, 6)])
    outcomes = together(
        lambda: approve(approver, a, (201, 409), "approve-a"),
        lambda: approve(concurrent, b, (201, 409), "approve-b"),
    )
    winner = [result for result in outcomes if "approvalId" in result]
    assert len(winner) == 1
    assert sum(result.get("code") == "INSUFFICIENT_STOCK" for result in outcomes) == 1
    result = winner[0]
    request = result["request"]
    original, key, owner = (a, "approve-a", store) if request["id"] == a["id"] else (b, "approve-b", other)
    assert approve(approver, original, 201, key) == result
    assert approve(approver, original, 409, "new-key")["code"] == "VERSION_CONFLICT"
    cancel_path = f"{REQUESTS}/{request['id']}/cancellation"
    cancel_body = {"expectedVersion": request["version"], "reason": "테스트 취소"}
    cancel = owner.call(cancel_path, 200, cancel_body, "cancel")[0]
    assert cancel["status"] == "CANCELLED" and cancel["lines"][0]["reservedQty"] == 0
    assert owner.call(cancel_path, 200, cancel_body, "cancel")[0] == cancel
    print("PASS: session/CSRF, SKU/list, cross-store access, concurrent submit, rollback, concurrent approval, replay, version, cancellation")
