"""Exercise second shipment after partial receipt and simulated physical stock recovery."""
from pathlib import Path
from runpy import run_path
from time import monotonic, sleep

helpers = run_path(str(Path(__file__).with_name("smoke-replenishment.py")))
Client, requests, together = (helpers[name] for name in ("Client", "REQUESTS", "together"))
store, logistics, other_logistics = Client("store"), Client("approver"), Client("approver")
request, = [r for r in store.call(requests+"?status=FULFILLING",200)[0]["items"] if r.get("outboundOrderId")]
path = f"{requests}/{request['id']}"
original_id = request["outboundOrders"][0]["id"]
first = f"{path}/outbound-orders/{original_id}"
original, = store.call(first,200)[0]["lines"]
assert original["shippedQty"] == 5
resolution = logistics.call(f"{first}/items/{original['id']}/picking-shortage-resolution",200,{
    "expectedVersion":request["version"],"outcome":"FOUND","reason":"실사에서 두 장 발견",
    "evidenceReference":"COUNT-43",
},"rediscovered")[0]
assert resolution["requestStatus"] == "FULFILLING"
approval = logistics.call(path+"/approvals",201,{
    "expectedVersion":resolution["requestVersion"],
    "lines":[{"requestItemId":original["requestItemId"],"approvedQty":2}],
},"approve-remainder")[0]["request"]
assert approval["lines"][0]["reservedQty"] == 2
payload = {"expectedVersion":approval["version"]}
a,b = together(
    lambda: logistics.call(path+"/outbound-orders",201,payload,"second-outbound"),
    lambda: other_logistics.call(path+"/outbound-orders",201,payload,"second-outbound"),
)
assert a == b
second, location = a
assert second["id"] != original_id
assert second["externalOrderId"] != store.call(first,200)[0]["externalOrderId"]
deadline = monotonic()+15
while monotonic()<deadline:
    shipment = store.call(location,200)[0]
    if shipment["status"] == "SHIPPED":
        break
    sleep(.15)
assert shipment["status"] == "SHIPPED"
assert shipment["lines"][0]["shippedQty"] == 2
packing = store.call(location+"/cartons",200)[0]
carton, = packing["cartons"]
line, = carton["lines"]
receipt_path = location+f"/cartons/{carton['id']}/receipt"
receipt = {"expectedVersion":packing["requestVersion"],"lines":[
    {"outboundOrderItemId":line["outboundOrderItemId"],"receivedQty":2},
]}
confirmed = store.call(receipt_path,201,receipt,"receive-two")[0]
assert confirmed["requestStatus"] == "COMPLETED"
assert store.call(receipt_path,201,receipt,"receive-two")[0] == confirmed
completed = store.call(path,200)[0]
assert completed["receivedQty"] == 7
assert completed["outboundOrders"] == [
    {"id":original_id,"status":"SHIPPED"}, {"id":second["id"],"status":"SHIPPED"},
]
line, = completed["lines"]
assert (line["openQty"],line["reservedQty"],line["shippedQty"],line["closedQty"]) == (0,0,7,0)
assert store.call(first+"/cartons",200)[0]["cartons"][0]["lines"][0]["receivedQty"] == 5
print("PASS: two shipments with independent events and receipts, replay, total seven received")
