"""Verify normal receipt against the shipment created by smoke-outbound.py."""

from pathlib import Path
from runpy import run_path

helpers = run_path(str(Path(__file__).with_name("smoke-replenishment.py")))
Client, requests, together = (helpers[name] for name in ("Client", "REQUESTS", "together"))

store, second, other = (Client(name) for name in ("store", "store", "other"))
operator = Client("approver")
latest = store.call(f"{requests}?status=FULFILLING", 200)[0]["items"]
request, = [item for item in latest if item.get("outboundOrderId")]
request_path = f"{requests}/{request['id']}"
outbound_path = f"{request_path}/outbound-orders/{request['outboundOrderId']}"
cartons = store.call(outbound_path + "/cartons", 200)[0]
carton, = cartons["cartons"]
assert carton["inspectionStatus"] == "PENDING"
assert carton["lines"][0]["inTransitQty"] == 6
receipt_path = f"{outbound_path}/cartons/{carton['id']}/receipt"
body = {"expectedVersion": cartons["requestVersion"], "lines": [
    {"outboundOrderItemId": line["outboundOrderItemId"], "receivedQty": line["shippedQty"]}
    for line in carton["lines"]
]}
assert other.call(receipt_path, 403, body, "receive-other")[0]["code"] == "FORBIDDEN"
assert operator.call(receipt_path, 403, body, "receive-operator")[0]["code"] == "FORBIDDEN"
assert store.call(receipt_path, 400, {**body, "lines": [
    {**body["lines"][0], "receivedQty": 7},
]}, "receive-short")[0]["code"] == "VALIDATION_ERROR"

outcomes = together(
    lambda: store.call(receipt_path, (201,409), body, "receive-a")[0],
    lambda: second.call(receipt_path, (201,409), body, "receive-b")[0],
)
winner, = [i for i, result in enumerate(outcomes) if "receiptId" in result]
assert outcomes[1-winner]["code"] == "RECEIPT_ALREADY_CONFIRMED"
confirmed = outcomes[winner]
assert store.call(receipt_path, 201, body, ("receive-a", "receive-b")[winner])[0] == confirmed
assert confirmed["requestStatus"] == "COMPLETED"
assert confirmed["carton"]["inspectionStatus"] == "CONFIRMED"
assert confirmed["carton"]["lines"][0]["receivedQty"] == 6
assert confirmed["carton"]["lines"][0]["inTransitQty"] == 0
assert store.call(outbound_path, 200)[0]["lines"][0]["inTransitQty"] == 0
assert store.call(request_path, 200)[0]["status"] == "COMPLETED"
print("PASS: receipt scope, quantity validation, concurrent confirmation, replay, transit and completion")
