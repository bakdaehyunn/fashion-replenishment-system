# 정상 출고 구현 명세

관련 문서 [업무 OpenAPI](openapi.yaml) · [보충 이행 상태](fulfillment-state-design.md) · [WMS 시뮬레이터](wms-simulator-spec.md) · [테이블 설계](table-design.md)

## 범위와 화면 연결

첫 단계는 요청 전량이 유효 예약된 한 건을 한 출고 지시로 보내는 흐름이다. 여러 승인 회차의 예약을 한 지시에 배정할 수 있다. 부분 출고, 피킹 부족, WMS 취소 및 입고는 후속 단계에서 상세화한다. WMS는 NORMAL/AUTO를 사용한다.

출발 창고에 활성 소속된 물류 운영 담당자가 생성한다. 기존 승인·매장 역할과 함께 보유할 수 있다. 물류 운영 담당자도 담당 창고의 요청 목록과 상세를 조회할 수 있고, 세션 조회에서 받은 담당 창고 ID로 화면에서 생성 버튼을 표시한다. 서버는 생성·조회 각각에서 소속을 다시 검증한다. 화면 연결에 필요한 세션 필드는 `outboundWarehouseIds`다.

요청 행을 잠근 상태에서 성공 키 재생, 버전, 상태, 전체 유효 예약, 기존 지시 여부를 확인한다. 이미 생성된 지시는 동일 성공 키로만 201을 재생한다. 지시 생성은 요청 버전을 올린다. 출고 전 취소는 같은 요청 행을 잠근 뒤 지시 존재를 검사한다. 최초 화면은 생성 응답의 Location으로 상세를 조회한다. 재접속 시에는 요청 상세의 `outboundOrderId`로 지시를 다시 찾는다.

## WMS 메시지

접수는 PUT `/wms/v1/runs/{runId}/orders/{externalOrderId}`로 보낸다. 외부 지시 ID는 서버에서 생성하고 전송 재시도에 그대로 사용한다. WMS의 접수와 첫 이벤트 저장은 같은 트랜잭션에서 확정한다. 동일 본문의 재전송은 기존 접수 결과를 반환한다.

```json
{
  "requestReference": "REQ-12",
  "sourceWarehouseCode": "WH-01",
  "destinationStoreCode": "STORE-02",
  "allowPartial": false,
  "lines": [{"lineId": "41", "skuCode": "DOWN-095", "quantity": 6}]
}
```

`lineId`는 출고 지시 상세 ID 문자열이다. 창고·매장 코드는 내부 숫자 ID와 별도로 매핑하고 WMS의 SKU·거점 데이터와 일치해야 한다. 접수 응답은 200 또는 201과 `externalOrderId`, `status`, `lastSequence`를 반환한다. 업무 상태는 아래 이벤트가 순서대로 반영되면서 전진하며 접수 HTTP 응답은 Outbox 전달 성공을 기록하는 데 사용한다.

모든 콜백은 POST `/internal/wms/events`로 전달한다. 로컬 Compose에서는 설정으로 주입한 시스템 간 인증 토큰을 사용한다. WMS가 이벤트와 전송 대기를 저장한 뒤 콜백을 보내며, 수신 서버는 원본을 Inbox에 저장한 뒤 2xx를 반환한다.

| 순번 예시 | 이벤트 | payload 필수 필드 |
|---|---|---|
| 1 | ORDER_ACCEPTED | 빈 객체 |
| 2 | WORK_STARTED | 빈 객체 |
| 3 | PICKING_FINALIZED | workId, revision, lines[{lineId, skuCode, pickedQty, shortageQty}] |
| 4 | PACKING_COMPLETED | cartons[{cartonNo, lines[{lineId, skuCode, quantity}]}] |
| 5 | SHIPMENT_CONFIRMED | cartons와 unshippedLines. 정상 흐름의 unshippedLines는 빈 배열 |

정상 AUTO는 전량 피킹 확인 후 WMS 내부 정책으로 포장과 출고까지 진행한다. 부족 시 운영자가 승인하는 release와 구별한다. 이 규칙은 전체 시뮬레이터 명세의 정상 AUTO에 적용한다.

```json
{
  "eventId": "7d192df9-4775-4799-9067-f13b5bc5f19d",
  "sourceSystem": "WMS_SIMULATOR",
  "runId": "normal-001",
  "externalOrderId": "DO-normal-001-12",
  "eventType": "SHIPMENT_CONFIRMED",
  "schemaVersion": 1,
  "sequence": 5,
  "occurredAt": "2026-11-23T05:00:00Z",
  "payload": {
    "cartons": [{"cartonNo": "CT-12-01", "lines": [{"lineId": "41", "skuCode": "DOWN-095", "quantity": 6}]}],
    "unshippedLines": []
  }
}
```

확정 이벤트에서 박스 번호와 박스 내 지시 상세는 각각 해당 범위 내 유일해야 한다. 지시 상세 ID와 SKU가 모두 일치해야 하고, 모든 박스의 라인별 합이 포장 확정 수량 및 지시 수량과 일치해야 한다. 중복 이벤트는 기존 처리 결과를 유지한다. 같은 ID에 다른 내용이나 순번 누락은 보류·기록하며 정상 흐름에서도 상태를 건너뛰지 않는다.

## Flyway와 트랜잭션

- 업무 DB의 다음 마이그레이션에서 거점 코드와 물류 역할, 출고 지시·상세·배정, 박스·상세, 출고 상태 이력, Outbox, Inbox 및 순번 커서를 추가한다. 기존 예약에는 소비 수량을 추가하고 소비와 해제의 합을 최초 예약량 이하로 제한한다.
- 생성 시 요청, 예약 상세와 배정 잔량을 검증한다. 한 트랜잭션에서 지시·배정·요청 버전·명령 결과·Outbox를 저장한다. 업무 재고 잔액과 예약 잔량은 그대로 유지한다.
- 워커는 전송할 행을 짧은 트랜잭션에서 선점하고 HTTP는 그 밖에서 호출한다. 저장된 메시지와 동일 외부 ID로만 재전송한다.
- 출고 확정은 요청 → 지시 → 재고 키 → 예약 상세 순으로 잠근다. 같은 트랜잭션에서 창고 정상·예약 집계를 차감하고 예약 및 배정 소비량을 증가시킨다. 요청 상세의 예약을 출고 누계로 옮기고 원장에 실물과 예약 증감을 남긴다. 요청 버전을 올리고 지시·박스 확정, Inbox 처리 완료 및 순번 커서를 저장한다.
- 박스별 확정 출고 수량이 운송 중 수량의 기준이다. 초기에는 입고·손실 정산이 없으므로 출고 직후 운송 중 수량은 박스 상세의 출고 합과 같다. 운송 중 수량을 창고·매장 재고에 합산하지 않는다.
- 시뮬레이터 DB는 내부 물리 재고·지시·이벤트와 전송 시도를 독립으로 저장한다.

## 인수 기준

| 사례 | 초기 조건과 기대 결과 |
|---|---|
| 정상 출고 | 정상 10, 예약 6, 보류 4, 매장 정상 0. 지시 생성 직후 재고 불변. 확정 후 정상 4, 예약 0, 보류 4, 매장 정상 0, 운송 중 6, 지시 SHIPPED, 요청 FULFILLING |
| 중복 생성 | 같은 요청·키·입력으로 두 번 생성해도 같은 201 및 Location. 지시와 Outbox는 각각 한 건 |
| 출고 전 취소 경합 | 지시 생성과 취소는 요청 잠금으로 직렬화. 취소가 이기면 지시 미생성, 지시 생성이 이기면 즉시 취소 거절 |
| 중복 콜백 | 같은 확정 이벤트 재전송 시 재고·원장·박스 및 순번 불변 |
| 확정 반영 실패 | 원장 삽입 실패 시 재고 차감, 배정 소비, 박스·지시 상태, 순번 커서가 모두 롤백 |
