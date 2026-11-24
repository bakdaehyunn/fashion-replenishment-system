# 모듈 설계서 v0.1

관련 문서: [요구사항](requirements.md) · [기술 스택](technology-stack.md) · [승인·예약 설계](approval-reservation-design.md)

## 1. 구조와 설계 상태

하나의 Spring Boot 애플리케이션과 하나의 PostgreSQL을 사용하는 모듈형 모놀리스다. 초기에는 하나의 Gradle 애플리케이션에서 최상위 업무 패키지로 경계를 나눈다. Gradle 멀티 프로젝트나 별도 서비스 분리는 필수 조건이 아니다.

아래 공개 인터페이스 이름은 설계용 이름이며 아직 구현된 API가 아니다. 테이블명·컬럼·외래 키·HTTP 경로는 ERD 및 API 설계 단계에서 확정한다.

## 2. 모듈 책임과 데이터 소유권

| 모듈 | 소유 데이터·책임 | 공개 기능 예시 | 직접 변경하지 않는 데이터 |
|---|---|---|---|
| catalog | 스타일, 색상·사이즈별 SKU, 사용 가능 여부 | CatalogQuery | 요청, 재고 |
| organization | 물류센터·매장, 사용자 소속 관계 | OrganizationQuery | 로그인 정보, 재고 |
| replenishment | 요청·항목, 승인 이력, 미충족 잔량 종결, 요청 명령의 멱등성 결과 | ReplenishmentCommands, ReplenishmentQuery | 재고 잔액·예약, 출고 |
| inventory | 거점·SKU 재고, 예약·예약 변동, 재고 이동 원장, 재고 조정 | InventoryReservations, InventoryMovements | 요청 승인 상태, 입고 검수 |
| fulfillment | 출고 지시·항목, 피킹 결과, 박스·출고 수량, 출고 취소 진행 | FulfillmentCommands, FulfillmentQuery | 재고 잔액, 입고 검수 |
| receiving | 박스별 검수, 파손·수량 차이 건, 조사·종결 근거 | ReceivingCommands, ReceivingQuery | 창고 출고 기록, 재고 잔액 |
| identity | 사용자, 인증 정보, 역할, 현재 인증 주체 | CurrentActor, AuthorizationPolicy | 소속 거점 원장, 업무 상태 |
| integration | Outbox, 수신 이벤트 Inbox, 전달 시도·선점·재처리, WMS 통신 어댑터 | IntegrationMessages, WmsClient | 승인 여부, 재고·출고 업무 규칙 |

보충 요청의 작업 명령을 받는 replenishment가 승인·예약 유스케이스를 조율한다. integration은 전송 상태를 관리하지만 업무 성공을 독자적으로 판정하지 않는다.

## 3. 패키지와 접근 규칙

```text
<base-package>
 ├─ catalog/{api,internal}
 ├─ organization/{api,internal}
 ├─ replenishment/{api,internal}
 ├─ inventory/{api,internal}
 ├─ fulfillment/{api,internal}
 ├─ receiving/{api,internal}
 ├─ identity/{api,internal}
 ├─ integration/{api,internal}
 ├─ workflows
 └─ configuration
```

- 다른 모듈은 공개 api의 명령·조회 인터페이스와 값 DTO만 참조한다.
- JPA 엔티티·Repository·내부 SQL 구현을 다른 모듈에 노출하지 않는다.
- 엔티티 간 모듈 횡단 JPA 연관관계 대신 식별자를 전달한다. DB 외래 키 사용 여부는 ERD에서 별도 결정한다.
- 다른 모듈의 테이블을 직접 갱신하지 않는다. 통합 조회가 필요하면 공개 조회 기능을 조합하고, 전용 조회 모델이 필요한지 나중에 판단한다.
- 공용 패키지에 업무 규칙을 모으지 않는다. 기술 설정만 configuration에 둔다.
- 감사 기록은 각 소유 모듈이 해당 변경과 함께 기록한다. 화면용 감사 타임라인은 조회 시 조합한다.

## 4. 의존 방향과 흐름 조율

| 호출 주체 | 허용 의존 | 목적 |
|---|---|---|
| replenishment | catalog, organization, identity, inventory | 요청 검증, 권한 확인, 예약·해제 |
| fulfillment | replenishment, inventory, integration, identity | 이행 가능 잔량 확인, 예약 사용, 출고·Outbox 기록 |
| receiving | fulfillment, inventory, identity | 박스 확인, 정상·파손 입고 및 조정 |
| identity | organization | 소속 거점에 따른 권한 확인 |
| workflows | 위 업무 모듈과 integration의 공개 api | 수신 이벤트 등 여러 모듈을 묶는 최상위 유스케이스 |

catalog, organization, inventory는 replenishment·fulfillment·receiving에 역의존하지 않는다. integration의 전송 구현도 업무 모듈에 역의존하지 않는다.

WMS 수신 처리는 workflows의 이벤트 처리기가 integration의 Inbox 기능과 업무 모듈 api를 조합한다. 예를 들어 피킹 부족 결과 반영은 fulfillment 변경, inventory 예약·보류 변경, replenishment 잔량 반영, Inbox 처리 완료를 한 트랜잭션으로 수행한다. 이로써 fulfillment와 replenishment 사이에 양방향 호출을 만들지 않는다.

workflows는 범용 서비스 모음이 아니라 명시된 다중 모듈 명령의 조율 계층이다. 업무 판정은 각 소유 모듈 내부에 유지한다. 최초 승인 기능은 replenishment 안에서 조율하며 불필요하게 이동하지 않는다.

## 5. 승인·예약 호출 흐름

```text
인증된 승인 요청
 → replenishment 승인 서비스 [트랜잭션 시작]
 → identity: 승인 권한 확인
 → 요청 잠금·버전·상태·명령 키 확인
 → catalog / organization: 대상 유효성 확인
 → replenishment: 승인 기본·상세 저장 및 JPA flush
 → inventory: 승인 상세 ID로 여러 SKU 예약
 → replenishment: 요청 상태·수량·이력·명령 결과 저장
 → [함께 커밋]
```

이 단계에서 WMS를 호출하거나 출고 지시를 자동 생성하지 않는다. 승인·예약과 출고 지시 생성은 별도 명령으로 두어 취소 경계와 실패 원인을 명확히 한다. 출고 지시 생성 시 fulfillment와 Outbox 기록은 함께 커밋한다.

## 6. 트랜잭션 및 데이터 접근

- 단일 DataSource와 동일한 트랜잭션 관리자 아래 모듈 호출은 REQUIRED로 참여한다.
- JPA와 JdbcClient가 실제 같은 연결·트랜잭션에 참여하는지는 통합 테스트로 검증한다.
- 재고 잔액과 예약은 inventory 내부 JdbcClient 경로로 일관되게 변경한다. 같은 잔액을 JPA로 관리하면서 SQL로 갱신하지 않는다.
- 요청·승인 이력은 JPA로 관리할 수 있다. 낙관적 버전과 명시적 요청 잠금을 함께 사용하여 오래된 화면과 동시 명령을 구분한다.
- 필수 재고·감사 기록에 REQUIRES_NEW 또는 커밋 후 비동기 이벤트를 사용하지 않는다.
- WMS HTTP 호출은 업무 DB 트랜잭션 밖에서 수행한다.
- 모듈 분리는 독립 커밋이나 독립 DB를 의미하지 않는다.

## 7. 경계 검증 계획

구현 시 모듈 간 internal 참조와 순환 의존을 검사하는 작은 아키텍처 검증을 추가한다. 도구는 초기화 시 선택한다. 승인 서비스 통합 테스트에서는 inventory 예약 성공 후 의도적인 실패를 발생시켜 요청·예약·원장이 모두 롤백되는지 확인한다.

## 8. 후속 설계

- 승인·예약의 구체적인 상태·수량·잠금은 [승인·예약 설계](approval-reservation-design.md)를 따른다.
- 출고 취소, 피킹 부족의 다중 모듈 처리, 박스별 입고·차이 처리는 각각 후속 명세로 작성한다.
- ERD에는 소유 모듈, 참조 ID, 유니크·체크 제약과 변경 주체를 표시한다.
