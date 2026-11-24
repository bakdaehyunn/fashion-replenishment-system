"""Verify damaged receipt and disposal on the shipment from smoke-outbound.py."""
from pathlib import Path
from runpy import run_path

helpers = run_path(str(Path(__file__).with_name("smoke-replenishment.py")))
Client, requests, together = (helpers[name] for name in ("Client", "REQUESTS", "together"))
store, operator, second = Client("store"), Client("approver"), Client("approver")
request, = [r for r in store.call(requests+"?status=FULFILLING",200)[0]["items"] if r.get("outboundOrderId")]
order = f"{requests}/{request['id']}/outbound-orders/{request['outboundOrderId']}"
packing = store.call(order+"/cartons",200)[0]
carton, = packing["cartons"]
line, = carton["lines"]
path = f"{order}/cartons/{carton['id']}"
item = {"outboundOrderItemId":line["outboundOrderItemId"],"receivedQty":5,"damagedQty":1}
initial = store.call(path+"/receipt",201,{"expectedVersion":packing["requestVersion"],"lines":[item]}, "damage")[0]
line, = initial["carton"]["lines"]
assert initial["requestStatus"] == "FULFILLING"
assert line["receivedQty"] == 5 and line["damagedQty"] == 1 and line["inTransitQty"] == 0
assert "shortage" not in line and line["damage"]["remainingQty"] == 1
dispose = f"{path}/damages/{line['damage']['id']}/disposals"
body = {"expectedVersion":initial["requestVersion"],"disposedQty":1,"reason":"검수 파손","evidenceReference":"DMG-01"}
assert store.call(dispose,403,body,"store-disposal")[0]["code"] == "FORBIDDEN"
assert operator.call(dispose,409,{**body,"disposedQty":2},"overage")[0]["code"] == "RECEIPT_DAMAGE_CONFLICT"
outcomes = together(
    lambda: operator.call(dispose,(201,409),body,"dispose-a")[0],
    lambda: second.call(dispose,(201,409),body,"dispose-b")[0],
)
winner, = [i for i, result in enumerate(outcomes) if "disposalId" in result]
assert outcomes[1-winner]["code"] == "VERSION_CONFLICT"
result = outcomes[winner]
assert operator.call(dispose,201,body,("dispose-a","dispose-b")[winner])[0] == result
assert result["requestStatus"] == "COMPLETED"
line, = result["carton"]["lines"]
assert line["damage"]["status"] == "RESOLVED" and line["damagedQty"] == 1
assert store.call(order,200)[0]["lines"][0]["inTransitQty"] == 0
print("PASS: damaged receipt, pending dispute, authorized disposal, contention and replay")
