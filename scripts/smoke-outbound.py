"""Verify one normal shipment against the seeded backend and WMS."""

import sys
from pathlib import Path
from runpy import run_path
from time import monotonic, sleep

helpers = run_path(str(Path(__file__).with_name("smoke-replenishment.py")))
Client, submit, approve, requests = (helpers[name] for name in ("Client", "submit", "approve", "REQUESTS"))

store = Client("store")
operator = Client("approver")
assert operator.call("/api/v1/session", 200)[0]["outboundWarehouseIds"] == ["1"]
request, _ = submit(store, 2, "ship-request", [(1, 6)])
approved = approve(operator, request, 201, "ship-approval")["request"]
path = f"{requests}/{request['id']}/outbound-orders"
body = {"expectedVersion": approved["version"]}
assert store.call(path, 403, body, "unauthorized-dispatch")[0]["code"] == "FORBIDDEN"
created, location = operator.call(path, 201, body, "create-dispatch")
assert created["status"] == "DISPATCH_PENDING"
assert operator.call(path, 201, body, "create-dispatch") == (created, location)
assert store.call(f"{requests}/{request['id']}/cancellation", 409, {
    "expectedVersion": created["requestVersion"], "reason": "출고 지시 후 취소",
}, "cancel-after-dispatch")[0]["code"] in {"DISPATCH_ALREADY_CREATED", "VERSION_CONFLICT"}

if "--timeout" in sys.argv:
    deadline = monotonic() + 10
    while True:
        waiting, _ = store.call(location, 200)
        if waiting["status"] == "ACCEPTANCE_UNKNOWN":
            break
        assert monotonic() < deadline, waiting
        sleep(0.2)
    reserved, _ = store.call(f"{requests}/{request['id']}", 200)
    assert reserved["lines"][0]["reservedQty"] == 6
    assert waiting["lines"][0]["shippedQty"] == 0
    assert waiting["externalOrderId"] == created["externalOrderId"]
    print("PASS: acceptance unknown, reservation retained and original external ID")

deadline = monotonic() + 20
while True:
    result, _ = store.call(location, 200)
    if result["status"] == "SHIPPED":
        break
    assert monotonic() < deadline, result
    sleep(0.2)
assert result["externalOrderId"] == created["externalOrderId"]
assert result["lines"][0]["instructedQty"] == result["lines"][0]["shippedQty"] == result["lines"][0]["inTransitQty"] == 6
updated, _ = store.call(f"{requests}/{request['id']}", 200)
assert updated["outboundOrderId"] == result["id"]
assert updated["status"] == "FULFILLING"
assert updated["lines"][0]["reservedQty"] == 0 and updated["lines"][0]["shippedQty"] == 6
print("PASS: outbound access, idempotent order, cancellation boundary, WMS shipment and transit")
