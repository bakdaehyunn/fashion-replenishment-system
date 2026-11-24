"""Exercise shortage and additional receipt on the shipment from smoke-outbound.py."""

from pathlib import Path
from runpy import run_path

helpers = run_path(str(Path(__file__).with_name("smoke-replenishment.py")))
Client, requests, together = (helpers[name] for name in ("Client", "REQUESTS", "together"))
store, second, other = (Client(name) for name in ("store", "store", "other"))
request, = [r for r in store.call(requests+"?status=FULFILLING",200)[0]["items"] if r.get("outboundOrderId")]
order = f"{requests}/{request['id']}/outbound-orders/{request['outboundOrderId']}"
packing = store.call(order+"/cartons",200)[0]
carton, = packing["cartons"]
line, = carton["lines"]
path = f"{order}/cartons/{carton['id']}"
initial = store.call(path+"/receipt",201,{"expectedVersion":packing["requestVersion"],
    "lines":[{"outboundOrderItemId":line["outboundOrderItemId"],"receivedQty":5}]}, "initial-five")[0]
assert initial["requestStatus"] == "FULFILLING"
assert initial["carton"]["inspectionStatus"] == "CONFIRMED"
line, = initial["carton"]["lines"]
assert line["receivedQty"] == 5 and line["inTransitQty"] == 1
assert line["shortage"]["remainingQty"] == 1 and line["shortage"]["status"] == "OPEN"
assert store.call(order,200)[0]["lines"][0]["inTransitQty"] == 1

add = f"{path}/shortages/{line['shortage']['id']}/additional-receipts"
body = {"expectedVersion":initial["requestVersion"],"receivedQty":1}
assert other.call(add,403,body,"wrong-store")[0]["code"] == "FORBIDDEN"
assert store.call(add,409,{**body,"receivedQty":2},"overage")[0]["code"] == "RECEIPT_SHORTAGE_CONFLICT"
outcomes = together(
    lambda: store.call(add,(201,409),body,"add-a")[0],
    lambda: second.call(add,(201,409),body,"add-b")[0],
)
winner, = [i for i, result in enumerate(outcomes) if "additionalReceiptId" in result]
assert outcomes[1-winner]["code"] == "VERSION_CONFLICT"
result = outcomes[winner]
assert store.call(add,201,body,("add-a","add-b")[winner])[0] == result
assert store.call(add,409,{**body,"expectedVersion":result["requestVersion"]},"closed")[0]["code"] == "RECEIPT_SHORTAGE_CONFLICT"
assert result["requestStatus"] == "COMPLETED"
line, = result["carton"]["lines"]
assert line["receivedQty"] == 6 and line["inTransitQty"] == 0 and line["shortage"]["status"] == "RESOLVED"
assert store.call(order,200)[0]["lines"][0]["inTransitQty"] == 0
print("PASS: shortage, overage prevention, concurrent settlement, replay, completion")
