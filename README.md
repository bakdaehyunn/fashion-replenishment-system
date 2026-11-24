# 매장 재고 보충 관리

패션 매장의 보충 요청을 승인하고, 물류센터의 재고 예약·출고부터 매장 입고까지 추적하는 시스템이다. 요청 전체의 완료 조건과 개별 출고·입고의 상태를 분리하고, 창고 실물 부족과 WMS 응답 타임아웃이 발생해도 수량과 이력을 일관되게 관리한다.

업무 서버는 Java 25·Spring Boot 기반 단일 애플리케이션이다. PostgreSQL 17의 트랜잭션으로 재고와 업무 상태를 함께 갱신한다. 별도 WMS 시뮬레이터가 창고 작업과 연동 장애를 재현하며, React·TypeScript 화면에서 요청과 출고 이력을 확인할 수 있다.

## 구현 범위와 후속 설계

| 구분 | 범위 |
|---|---|
| 구현 | 보충 요청·승인·재고 예약, WMS 출고 지시·취소·재전송, 매장 검수, 입고 부족·운송 손실·파손 처리, 피킹 부족 조사, 추가 출고와 미충족 종결 |
| 상태 관리 | 보충 요청·출고 지시·출고 취소를 각각 Java enum과 TypeScript 타입으로 표현한다. 화면에서 해당 응답 상태값을 검증하고, 요청 완료는 잔량·예약·운송·조사 결과를 기준으로 판정한다. |
| 후속 업무 설계 | 검수 보정, 과다 입고·예상 외 SKU 처리, 완료 판정에 미처리·충돌 WMS 이벤트 반영 |
| 후속 시뮬레이터 설계 | 시드 기반 랜덤 실행과 프로토콜 오류 재현 모드 |
| 확장 검토 | 업무 문서 검색·설명용 RAG, 이벤트 소비자 확장용 Kafka, 캐시용 Redis, 배포 관리용 Kubernetes |

기본 자동 검증은 정상 입고, WMS 접수 응답 타임아웃, 부분 출고 후 2차 출고, 전량 미충족 마감의 네 가지를 다룬다. [자동 검증 시나리오와 기대 결과](#빈-환경에서-대표-시나리오-실행)

[이행 상태 설계](docs/fulfillment-state-design.md) · [WMS 시뮬레이터 설계](docs/wms-simulator-spec.md) · [기술 스택과 확장 조건](docs/technology-stack.md)

## 빠른 실행

Docker Compose와 Python 3가 설치된 환경에서 대표 시나리오를 한 번에 재현한다.

```sh
python3 scripts/run-demo.py second-shipment
```

스크립트는 새 테스트 DB에서 앱과 화면을 기동하고, 두 차례 출고와 입고를 HTTP로 실행한 뒤 최종 수량을 DB에서도 확인한다. 화면에서 직접 조작하려면 [로컬 화면 실행](#로컬-화면-실행)과 [수동 시연 절차](#같은-요청에서-두-번째-출고)를 참고한다.

## 대표 데모 · 보충 요청 7장, 1차 출고 5장, 2차 출고 2장

창고 장부에는 7장이 있지만 피킹 시점에 실물 5장만 확보된다. 매장은 확보된 물량을 먼저 받고, 물류센터는 차이가 난 2장의 행방을 조사한다.

| 업무 사건 | 수량과 상태 | 설계 이유 |
|---|---|---|
| 승인 후 피킹에서 5장 확보 | 부족 2장은 창고 조사 보류와 요청의 열린 잔량으로 기록 | 실물 확인 전까지 부족분을 다른 매장에 배정하지 않는다. |
| 부분 출고 승인 후 5장 입고 | 1차 출고·검수는 끝나고 요청은 이행 중 | 1차 박스의 검수가 끝나도 열린 2장은 계속 추적해야 한다. |
| 실사에서 2장 발견 | 조사 보류 2장을 정상 재고로 복귀시키고, 열린 잔량을 추가 승인해 예약 | 실물 발견과 매장에 다시 배정하는 결정을 각각 기록한다. |
| 2차 출고 후 2장 입고 | 정상 입고 합계 7장, 미충족 0장, 요청 완료 | 한 요청에서 두 출고 이력과 각 박스의 검수를 독립적으로 조회할 수 있다. |

자동 검증에는 같은 키로 2차 출고를 동시에 요청했을 때 동일한 지시가 반환되는 경우와, 같은 키로 2차 입고 확정을 재요청해도 누적 입고가 7장으로 유지되는 경우가 포함된다.

[상태 설계](docs/fulfillment-state-design.md) · [피킹 부족 명세](docs/outbound-picking-shortage.md) · [테이블 설계](docs/table-design.md) · [OpenAPI](docs/openapi.yaml)

## 로컬 화면 실행

```sh
docker compose up --build -d
docker compose ps
```

[로컬 업무 화면](http://localhost:5173)을 연다. 로그인과 시연 데이터 적재는 [첫 업무 API 검증](#첫-업무-api-검증)을 참고한다. 종료는 `docker compose down`으로 한다.

## 빈 환경에서 대표 시나리오 실행

Docker Compose와 Python 3으로 실행한다. 매 시나리오마다 새 PostgreSQL 17 볼륨에서 Flyway를 적용하고, 서버 두 개와 프론트엔드를 기동한다. HTTP 검증은 프론트엔드의 API 프록시를 거치고, 최종 수량과 기록 건수는 DB에서도 확인한다. 실행마다 별도 Compose 프로젝트와 임의의 로컬 포트를 사용한다. 실행이 끝나면 스크립트가 만든 컨테이너·볼륨을 정리한다.

```sh
python3 scripts/run-demo.py
```

특정 시나리오만 실행하려면 `normal`, `timeout`, `second-shipment`, `zero-shipment` 중 하나를 인수로 넘긴다.

| 시나리오 | 결과 | 설계 판단 |
|---|---|---|
| 정상 출고·입고 | 6장 출고 후 6장 입고, 운송 중 0장, 요청 완료 | 출고는 물류센터 재고를 차감하고, 입고 확정은 매장 재고를 증가시킨다. |
| WMS 접수 응답 유실 | 미확인 동안 예약 6장 유지, 전송 2회, 출고·차감 각 1회 | 타임아웃 중에는 이미 접수됐을 수 있다. 같은 외부 지시 ID를 쓰면 기존 작업으로 복구할 수 있다. |
| 피킹 부족 후 두 차례 출고 | 부족 2장 조사 보류, 1차 5장과 2차 2장 입고, 정상 입고 합계 7장 | 부족분을 다시 약속하지 않도록 보류한다. 열린 잔량과 조사가 모두 끝날 때 완료하고, 각 출고·검수는 별도로 보존한다. |
| 출고 0장으로 전량 미충족 마감 | 부분 불허 요청의 출고 지시 취소, 조사 종결 후 정상 입고 0장·미충족 7장 | 창고 출고 지시와 매장 보충 요청을 별도로 종결한다. |

두 번째 출고 시나리오의 WMS 실사 결과는 스크립트가 시연용 SQL을 적용해 재현한다.

Docker Desktop에서 이미지 다운로드가 진행되지 않으면 Docker의 인증 설정을 점검한다. 이 환경에서는 Docker Desktop 인증 도우미의 Docker Hub 자격 증명 조회가 시간 초과됐다. 동일한 Docker 엔진에 임시 익명 설정을 사용하니 PostgreSQL 17 이미지가 다운로드됐다. 다음 명령은 현재 설정을 수정하지 않고, 기본 Docker CLI 플러그인 폴더를 사용하는 환경에서 익명 설정으로 시연한다.

```sh
(
  export DOCKER_HOST="$(docker context inspect --format '{{.Endpoints.docker.Host}}')"
  config_dir="$(mktemp -d)" || exit 1
  trap 'rm -rf -- "$config_dir"' EXIT
  ln -s "$HOME/.docker/cli-plugins" "$config_dir/cli-plugins"
  export DOCKER_CONFIG="$config_dir"
  unset DOCKER_CONTEXT
  python3 scripts/run-demo.py
)
```

## 첫 업무 API 검증

백엔드 시작 시 Flyway가 스키마 마이그레이션을 적용한다. 다음 명령은 앱이 기동된 **빈 로컬 테스트 DB**에서 한 번 실행한다. 계정 세 개와 SKU 두 개, 경합 및 롤백을 재현할 창고 수량을 적재한다.

```sh
docker compose exec -T backend-db psql -U replenishment -d replenishment -v ON_ERROR_STOP=1 < backend/src/test/fixtures/replenishment.sql
python3 scripts/smoke-replenishment.py http://localhost:8080
```

검증 스크립트는 세션과 CSRF, 요청별 접근 권한, 동시 중복 신청, 두 번째 SKU 부족에 따른 롤백, 두 요청의 재고 경합, 이전 성공 응답 재생, 버전 충돌, 취소를 HTTP로 확인한다. 재실행에는 새 테스트 DB가 필요하다.

테스트 데이터 적재 후 http://localhost:5173 에서 매장 직원 `store`, 승인 담당자 `approver`로 각각 로그인한다. 두 계정의 로컬 테스트 비밀번호는 `demo`다. 매장 계정의 요청 목록에서 **새 보충 신청**을 열어 제출한다. 승인 담당자로 로그인하면 **승인 대기** 목록이 열린다. 요청번호를 눌러 상세에서 승인한다. 매장 직원으로 돌아와 상세 화면에서 예약 수량을 확인하고 취소할 수 있다.

Compose 없이 화면을 개발하려면 백엔드를 실행한 뒤 `cd frontend && npm run dev`를 사용한다. Vite는 같은 출처의 `/api`를 기본으로 `http://127.0.0.1:8080`에 전달하며, `API_TARGET`으로 변경할 수 있다.

## 정상 출고 시연

업무 테스트 데이터를 적재한 뒤 WMS 시뮬레이터 DB에도 실물 재고를 넣는다.

```sh
docker compose exec -T wms-simulator-db psql -U wms_simulator -d wms_simulator -v ON_ERROR_STOP=1 < wms-simulator/src/test/fixtures/normal-outbound.sql
python3 scripts/smoke-outbound.py http://localhost:8080
```

화면에서 시연할 때는 매장 직원으로 DOWN-095 여섯 장을 신청한다. 승인 담당자 계정에는 데모용 물류 운영 역할도 있으므로 승인 후 출고 지시를 생성할 수 있다. **상세 새로고침**을 눌러 창고 작업을 다시 조회하고, **출고 및 입고 검수**에서 출고 상태와 운송 수량을 확인한다. 명령 줄 검증과 화면 시연은 각각 새 시연 데이터에서 시작한다.

두 앱을 직접 띄울 때는 같은 `WMS_TOKEN`을 설정한다. 백엔드의 `WMS_URL` 기본값은 `http://localhost:8081`, WMS의 `WMS_CALLBACK_URL` 기본값은 `http://localhost:8080/internal/wms/events`다.

## 정상 입고 시연

정상 출고가 끝난 요청을 매장 직원 계정으로 연다. **출고 및 입고 검수**에서 박스별 **정상 확인 수량**과 **파손 확인 수량**을 입력하고 **최초 검수 확정**을 누른다. 전량 입고하면 박스는 검수 확정, 운송 중 수량은 0, 요청은 완료로 표시된다.

명령 줄에서 확인하려면 초기 데이터를 적재한 환경에서 정상 출고 검증 직후 실행한다.

```sh
python3 scripts/smoke-receiving.py http://localhost:8080
```

이 검증은 정상 입고, 소속과 역할 제어, 수량 검증, 동시 확정과 성공 응답 재전송을 확인한다. 세부 계약은 [정상 입고 구현 명세](docs/receiving-normal-flow.md)를 참고한다.

## 입고 부족 시연

출고 6장인 박스에서 정상 확인 수량은 5, 파손 확인 수량은 0으로 입력하고 최초 검수를 확정한다. 요청은 이행 중으로 남고 부족 건과 운송 중 1장이 표시된다. 같은 박스에서 추가 입고 1장을 확정하면 부족 건이 해결되고 요청이 완료된다.

HTTP로 검증하려면 초기 데이터를 적재한 환경에서 정상 출고 검증 직후 아래를 실행한다. 정상 입고 검증과 부족 입고 검증은 각각 새 출고 건에서 시작한다.

```sh
python3 scripts/smoke-receiving-shortage.py http://localhost:8080
```

## 운송 손실 시연

출고 6장 중 매장 직원이 5장의 최초 검수를 확정한 뒤, 물류 운영 권한이 있는 데모 계정 `approver`로 같은 박스의 출고 상태를 연다. 부족 건에서 손실 1장, 사유, 조사 근거 번호를 입력해 확정한다. 매장 정상 재고는 5장이고 운송 중은 0장이며, 요청은 완료로 바뀐다.

정상 출고 검증을 마친 새 데이터에서 아래 명령으로 같은 흐름을 검증할 수 있다. 별도의 새 출고 건에서 `--race`를 붙이면 추가 입고와 손실 확정을 동시에 시도한다.

```sh
python3 scripts/smoke-transit-loss.py http://localhost:8080
```

## 파손 입고와 폐기 시연

출고 6장인 박스의 최초 검수에 정상 5장과 파손 1장을 입력한다. 운송 중 수량은 0장이 되고, 파손 1장은 매장 보류 재고로 남는다. 물류 운영 권한이 있는 데모 계정 `approver`로 1장 폐기와 사유·조사 근거를 확정하면 요청이 완료된다.

정상 출고 검증을 마친 새 데이터에서 아래 명령으로 확인할 수 있다.

```sh
python3 scripts/smoke-receiving-damage.py http://localhost:8080
```

## WMS 접수 응답 타임아웃 시연

새 시연 데이터에서 시뮬레이터와 업무 서버의 실행 ID를 동일하게 설정한다. 기본 실행 ID는 `normal-local`이며 기존 기초 데이터와 일치한다.

```sh
WMS_TIMEOUT_RUN_ID=normal-local docker compose up --build -d
# 새 DB에서 위의 업무·WMS 기초 데이터를 적재한 뒤 실행
python3 scripts/smoke-outbound.py http://localhost:8080 --timeout
```

출고 상태를 다시 조회하면 첫 호출의 5초 타임아웃 이후 **접수 결과 미확인**이 표시된다. Outbox는 첫 시도 후 약 12초에 같은 외부 지시 ID로 재전송한다. 시뮬레이터는 저장된 지시를 반환하고 정상 출고를 이어간다. 정상 시연은 `WMS_TIMEOUT_RUN_ID`를 비운 상태에서 실행한다.

## WMS 출고·취소 경합 시연

출고 상태 상세에서 매장 직원이 사유를 입력해 취소를 요청한다. **취소 확인 중**에는 기존 예약을 유지한다. 결과를 보려면 출고 상태를 다시 조회한다. 아래 두 모드는 각각 새 DB와 동일한 초기 데이터를 사용한다.

| 시연 | Compose 실행 환경 변수 | 검증 |
|---|---|---|
| 취소 먼저 | `WMS_TIMEOUT_RUN_ID=normal-local` | `python3 scripts/smoke-wms-cancellation.py http://localhost:8080` |
| 출고 먼저 | `WMS_SHIP_FIRST_RUN_ID=normal-local` | `python3 scripts/smoke-wms-cancellation.py http://localhost:8080 --ship-first` |

취소 먼저 모드는 재전송 전 창고 작업을 보류하고, 출고 먼저 모드는 실제 출고를 확정한 뒤 그 콜백만 취소 명령 수신까지 보류한다. 정상 시연에서는 두 변수를 비워둔다.

## 피킹 부족과 부분 출고 시연

새 업무·WMS DB에 [업무 기본 데이터](#첫-업무-api-검증)와 [WMS 실물 재고](#정상-출고-시연)를 적재한 뒤 피킹 부족용 수량을 적용한다. 화면에서 직접 시연할 때는 이 단계의 SQL만 적용한다. 업무 장부는 7장, WMS 실물은 5장으로 설정된다.

```sh
docker compose exec -T backend-db psql -U replenishment -d replenishment -v ON_ERROR_STOP=1 < backend/src/test/fixtures/picking-shortage.sql
docker compose exec -T wms-simulator-db psql -U wms_simulator -d wms_simulator -v ON_ERROR_STOP=1 < wms-simulator/src/test/fixtures/picking-shortage.sql
python3 scripts/smoke-picking-shortage.py http://localhost:8080
```

화면에서는 부분 이행을 허용해 7장을 신청한 뒤 승인하고 출고 지시를 생성한다. **상세 새로고침**으로 확보 5장·부족 2장을 확인하고 물류 담당자로 부분 출고를 승인한다. 5장을 입고해도 요청에는 열린 잔량 2장이 남는다. 별도의 새 데이터에서 스크립트에 `--disallow`를 붙이면 부분 출고 금지 건을 검증한다. 추가 출고는 아래의 두 번째 출고 절차를 따른다.

## 미충족 잔량과 창고 조사 종결

피킹 부족 시연으로 5장 입고를 마친 뒤, 물류 운영 담당자로 동일 출고 상태를 연다. 창고 조사에는 실사 결과·사유·근거를 입력하고, 요청 잔량에는 미충족 종결 사유를 입력한다. 둘을 어느 순서로 처리해도 마지막 조건이 충족될 때만 요청이 완료된다. 실물 발견은 업무 장부의 정상 재고로 복귀시키고, 손실 확정은 조사 보류만 차감한다. 창고 실물 변동은 WMS에서 확인한 조사 결과로 가정하며 시뮬레이터 실물 재고는 별도다.

```sh
python3 scripts/smoke-picking-shortage-closure.py http://localhost:8080
```

위 검증은 새 피킹 부족 검증 직후 실행한다. 별도의 새 데이터에 `--found`를 붙이면 실물 발견을 먼저 처리하는 순서를 확인한다.

## 같은 요청에서 두 번째 출고

[피킹 부족과 부분 출고 시연](#피킹-부족과-부분-출고-시연)을 새 데이터에서 수행해 5장을 입고한 뒤, WMS에서 실사로 2장이 발견된 상태를 만든다.

```sh
docker compose exec -T wms-simulator-db psql -U wms_simulator -d wms_simulator -v ON_ERROR_STOP=1 < wms-simulator/src/test/fixtures/picking-shortage-found.sql
python3 scripts/smoke-second-shipment.py http://localhost:8080
```

화면에서 시연하려면 위 SQL만 실행한 뒤 창고 조사 결과를 **실물 발견**으로 확정하고 같은 요청의 열린 2장을 추가 승인한다. 출고 지시를 새로 생성한 뒤 매장 직원이 **2차 출고** 탭에서 두 번째 박스를 검수한다. 요청 상세에서 1차·2차 이력과 정상 입고 누계 7장을 확인할 수 있다. 시연용 SQL은 WMS 실사 결과를 재현하며 업무 장부는 조사 확정 API에서 변경한다.

## 프로젝트 구성

| 경로 | 역할 |
|---|---|
| `backend/` | 보충 업무 API, 상태 전이와 재고 트랜잭션 |
| `wms-simulator/` | 창고 작업과 연동 결과 재현 |
| `frontend/` | React·TypeScript·Vite 업무 화면 |
| `infra/` | 공용 Java 컨테이너 빌드 설정 |
| `docs/` | [요구사항](docs/requirements.md), [모듈 경계](docs/module-design.md), [기술 스택](docs/technology-stack.md), [WMS 명세](docs/wms-simulator-spec.md) |

호스트에서 직접 빌드할 때는 Java 25와 Node.js 22.12 이상이 필요하다.

```sh
./gradlew build
cd frontend
npm ci
npm run lint
npm run build
```
