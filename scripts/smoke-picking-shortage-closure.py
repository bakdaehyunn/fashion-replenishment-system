"""Verify independent warehouse and unfulfilled remainder closure after partial receipt."""
import sys
from pathlib import Path
from runpy import run_path

helpers = run_path(str(Path(__file__).with_name("smoke-replenishment.py")))
Client, requests = (helpers[name] for name in ("Client", "REQUESTS"))
store, operator = Client("store"), Client("approver")
request, = [r for r in store.call(requests+"?status=FULFILLING",200)[0]["items"] if r.get("outboundOrderId")]
path = f"{requests}/{request['id']}"
order = f"{path}/outbound-orders/{request['outboundOrderId']}"
outbound = operator.call(order,200)[0]
line, = outbound["lines"]
assert line["pickingShortage"] == {"status":"OPEN","quantity":2}
closure = f"{path}/items/{line['requestItemId']}/unfulfilled-closure"
resolution = f"{order}/items/{line['id']}/picking-shortage-resolution"
found = "--found" in sys.argv
resolve_body = {"expectedVersion":request["version"],"outcome":"FOUND" if found else "LOSS",
                "reason":"창고 실사 완료","evidenceReference":"COUNT-42"}
close_body = {"expectedVersion":request["version"],"reason":"추가 보충 불필요"}
assert store.call(resolution,403,resolve_body,"unauthorized")[0]["code"] == "FORBIDDEN"
assert store.call(closure,403,close_body,"unauthorized")[0]["code"] == "FORBIDDEN"
first, first_body, last, last_body = (resolution,resolve_body,closure,close_body) if found else (
    closure,close_body,resolution,resolve_body
)
result = operator.call(first,200,first_body,"first")[0]
assert result["requestStatus"] == "FULFILLING"
assert operator.call(last,409,last_body,"last")[0]["code"] == "VERSION_CONFLICT"
last_body["expectedVersion"] = result["requestVersion"]
terminal = operator.call(last,200,last_body,"last")[0]
assert terminal["requestStatus"] == "COMPLETED"
assert operator.call(first,200,first_body,"first")[0] == result
assert operator.call(last,200,last_body,"last")[0] == terminal
line, = store.call(path,200)[0]["lines"]
assert (line["openQty"],line["shippedQty"],line["closedQty"]) == (0,5,2)
assert store.call(order,200)[0]["lines"][0]["pickingShortage"]["status"] == "RESOLVED"
print("PASS: independent shortage and remainder closure, version conflicts, replay and completion")
