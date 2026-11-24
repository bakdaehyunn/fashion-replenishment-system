"""Verify loss confirmation or its race with additional receipt on a fresh shipment."""
import sys
from pathlib import Path
from runpy import run_path

helpers = run_path(str(Path(__file__).with_name("smoke-replenishment.py")))
Client, requests, together = (helpers[name] for name in ("Client", "REQUESTS", "together"))
store, operator = Client("store"), Client("approver")
request, = [r for r in store.call(requests+"?status=FULFILLING",200)[0]["items"] if r.get("outboundOrderId")]
order = f"{requests}/{request['id']}/outbound-orders/{request['outboundOrderId']}"
packing = store.call(order+"/cartons",200)[0]
carton, = packing["cartons"]
line, = carton["lines"]
path = f"{order}/cartons/{carton['id']}"
initial = store.call(path+"/receipt",201,{"expectedVersion":packing["requestVersion"],
    "lines":[{"outboundOrderItemId":line["outboundOrderItemId"],"receivedQty":5}]}, "initial-five")[0]
line, = initial["carton"]["lines"]
shortage = f"{path}/shortages/{line['shortage']['id']}"
body = {"expectedVersion":initial["requestVersion"],"lostQty":1,"reason":"운송사 조사 완료","evidenceReference":"CASE-01"}
losses = shortage+"/losses"
assert store.call(losses,403,body,"store-loss")[0]["code"] == "FORBIDDEN"
assert operator.call(losses,400,{**body,"evidenceReference":" "},"blank-evidence")[0]["code"] == "VALIDATION_ERROR"
assert operator.call(losses,409,{**body,"lostQty":2},"overage-loss")[0]["code"] == "RECEIPT_SHORTAGE_CONFLICT"

if "--race" in sys.argv:
    loss, receipt = together(
        lambda: operator.call(losses,(201,409),body,"loss")[0],
        lambda: store.call(shortage+"/additional-receipts",(201,409),{
            "expectedVersion":initial["requestVersion"],"receivedQty":1,
        },"receipt")[0],
    )
    assert sum(r.get("code") == "VERSION_CONFLICT" for r in (loss,receipt)) == 1
    result = receipt if "additionalReceiptId" in receipt else loss
else:
    result = loss = operator.call(losses,201,body,"loss")[0]
if "lossId" in loss:
    assert operator.call(losses,201,body,"loss")[0] == loss
    assert operator.call(losses,409,{**body,"evidenceReference":"CASE-02"},"loss")[0]["code"] == "IDEMPOTENCY_CONFLICT"
assert result["requestStatus"] == "COMPLETED"
line, = result["carton"]["lines"]
assert line["lostQty"] == int("lossId" in loss)
assert line["receivedQty"] + line["lostQty"] == 6
assert line["inTransitQty"] == 0 and line["shortage"]["status"] == "RESOLVED"
assert store.call(order,200)[0]["lines"][0]["lostQty"] == line["lostQty"]
print("PASS: transit loss access, validation, settlement, replay and completion")
