"""Exercise a shipment with seven booked units and five physical units."""
import sys
from pathlib import Path
from runpy import run_path
from time import monotonic, sleep

helpers = run_path(str(Path(__file__).with_name("smoke-replenishment.py")))
Client, requests, approve, submit = (helpers[name] for name in ("Client", "REQUESTS", "approve", "submit"))
store, operator = Client("store"), Client("approver")
zero_shipment = "--zero-shipment" in sys.argv
allowed = not zero_shipment and "--disallow" not in sys.argv
request, _ = store.call(requests, 201, {
    "sourceLocationId": "1", "destinationLocationId": "2", "desiredReceiptDate": "2027-01-01",
    "allowPartial": allowed, "lines": [{"variantId": "1", "requestedQty": 7}],
}, "shortage-request")
request_path = f"{requests}/{request['id']}"
approved = approve(operator, request, 201, "approve-seven")["request"]
_, location = operator.call(request_path+"/outbound-orders", 201,
    {"expectedVersion": approved["version"]}, "picking-dispatch")


def wait_for(status):
    deadline = monotonic()+15
    while monotonic() < deadline:
        result = store.call(location,200)[0]
        if result["status"] == status:
            return result
        sleep(.15)
    raise AssertionError(result)


exception = wait_for("PICKING_EXCEPTION")
line, = exception["lines"]
assert (line["pickedQty"],line["shortageQty"],line["shippedQty"]) == (5,2,0)
line, = store.call(request_path,200)[0]["lines"]
assert (line["reservedQty"],line["openQty"]) == (5,2)
competing, _ = submit(store,2,"cannot-reallocate-held-stock",[(1,2)])
failure = approve(operator,competing,409,"held-stock")
assert failure["code"] == "INSUFFICIENT_STOCK" and failure["shortages"][0]["availableQty"] == 0
body = {"expectedVersion":exception["requestVersion"],"pickingRevision":exception["pickingRevision"]}
release = location+"/partial-release"
assert store.call(release,403,body,"unauthorized")[0]["code"] == "FORBIDDEN"
if not allowed:
    assert operator.call(release,409,body,"not-allowed")[0]["code"] == "PARTIAL_RELEASE_NOT_ALLOWED"
    assert store.call(location,200)[0] == exception
    print("PASS: partial shipment denied; five remain reserved and two held")
    if zero_shipment:
        order_line, = exception["lines"]
        closure = request_path+f"/items/{order_line['requestItemId']}/unfulfilled-closure"
        cancel = location+"/cancellation?outboundOnly=true"
        cancel_body = {"expectedVersion":exception["requestVersion"],"reason":"부분 출고 불가"}
        assert operator.call(closure,409,cancel_body,"active-order")[0]["code"] == "INVALID_STATE"
        assert store.call(cancel,403,cancel_body,"forbidden")[0]["code"] == "FORBIDDEN"
        pending = operator.call(cancel,202,cancel_body,"stop-dispatch")[0]
        assert pending["cancellationStatus"] == "REQUESTED"
        assert operator.call(cancel,202,cancel_body,"stop-dispatch")[0] == pending
        cancelled = wait_for("CANCELLED")
        active = store.call(request_path,200)[0]
        line, = active["lines"]
        assert active["status"] == "FULFILLING"
        assert (line["openQty"],line["reservedQty"],line["shippedQty"]) == (7,0,0)
        closed = operator.call(closure,200,{
            "expectedVersion":cancelled["requestVersion"],"reason":"재공급 불가",
        },"close-seven")[0]
        assert (closed["closedQty"],closed["requestStatus"]) == (7,"FULFILLING")
        resolved = operator.call(location+f"/items/{order_line['id']}/picking-shortage-resolution",200,{
            "expectedVersion":closed["requestVersion"],"outcome":"LOSS",
            "reason":"실사 후 손실 확정","evidenceReference":"COUNT-44",
        },"resolve-shortage")[0]
        assert resolved["requestStatus"] == "COMPLETED"
        completed = store.call(request_path,200)[0]
        assert completed["receivedQty"] == 0
        line, = completed["lines"]
        assert (line["openQty"],line["reservedQty"],line["shippedQty"],line["closedQty"]) == (0,0,0,7)
        print("PASS: zero shipped, seven closed unfulfilled, completion after investigation")
    sys.exit()

accepted = operator.call(release,202,body,"release-five")[0]
assert operator.call(release,202,body,"release-five")[0] == accepted
shipped = wait_for("SHIPPED")
assert shipped["lines"][0]["shippedQty"] == shipped["lines"][0]["inTransitQty"] == 5
packing = store.call(location+"/cartons",200)[0]
carton, = packing["cartons"]
line, = carton["lines"]
received = store.call(location+f"/cartons/{carton['id']}/receipt",201,{
    "expectedVersion":packing["requestVersion"],
    "lines":[{"outboundOrderItemId":line["outboundOrderItemId"],"receivedQty":5}],
},"receive-five")[0]
assert received["requestStatus"] == "FULFILLING"
assert received["carton"]["lines"][0]["inTransitQty"] == 0
line, = store.call(request_path,200)[0]["lines"]
assert (line["openQty"],line["reservedQty"],line["shippedQty"]) == (2,0,5)
print("PASS: five picked, two quarantined, approval replay, five shipped and received, remainder open")
