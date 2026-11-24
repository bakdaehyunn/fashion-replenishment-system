"""Verify a configured cancel-first or ship-first WMS scenario on a fresh database."""
import sys
from pathlib import Path
from runpy import run_path
from time import monotonic, sleep

helpers = run_path(str(Path(__file__).with_name("smoke-replenishment.py")))
Client, submit, approve, requests = (helpers[name] for name in ("Client", "submit", "approve", "REQUESTS"))
ship_first = "--ship-first" in sys.argv
store, operator = Client("store"), Client("approver")
request, _ = submit(store, 2, "cancel-scenario", [(1, 6)])
approved = approve(operator, request, 201, "cancel-approval")["request"]
request_path = f"{requests}/{request['id']}"
created, location = operator.call(request_path+"/outbound-orders",201,
    {"expectedVersion":approved["version"]},"cancel-dispatch")
assert created["cancellationStatus"] == "NONE"


def wait_for(predicate):
    deadline = monotonic()+18
    while monotonic()<deadline:
        order = store.call(location,200)[0]
        if predicate(order):
            return order
        sleep(.15)
    raise AssertionError(order)


before = wait_for(lambda order: order["status"] == ("READY_TO_SHIP" if ship_first else "ACCEPTANCE_UNKNOWN"))
body = {"expectedVersion":before["requestVersion"],"reason":"출고 취소 경합 시연"}
cancel = location+"/cancellation"
assert operator.call(cancel,403,body,"forbidden")[0]["code"] == "FORBIDDEN"
pending = store.call(cancel,202,body,"cancel")[0]
assert pending["cancellationStatus"] == "REQUESTED"
assert store.call(cancel,202,body,"cancel")[0] == pending
expected = "REJECTED" if ship_first else "CONFIRMED"
result = wait_for(lambda order: order["cancellationStatus"] == expected)
assert result["status"] == ("SHIPPED" if ship_first else "CANCELLED")
assert result["lines"][0]["inTransitQty"] == (6 if ship_first else 0)
current = store.call(request_path,200)[0]
assert current["status"] == ("FULFILLING" if ship_first else "CANCELLED")
assert current["lines"][0]["reservedQty"] == 0
assert current["lines"][0]["closedQty"] == (0 if ship_first else 6)
assert store.call(cancel,202,body,"cancel")[0] == pending
print(f"PASS: WMS {expected}, cancellation replay and final stock quantities")
