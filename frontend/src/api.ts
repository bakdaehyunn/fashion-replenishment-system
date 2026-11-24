const requestStatuses = ['SUBMITTED', 'FULFILLING', 'CANCELLED', 'REJECTED', 'COMPLETED'] as const
const outboundStatuses = [
  'DISPATCH_PENDING', 'ACCEPTANCE_UNKNOWN', 'ACCEPTED', 'WORKING', 'PICKING_EXCEPTION',
  'READY_TO_SHIP', 'SHIPPED', 'CANCELLED',
] as const
const cancellationStatuses = ['NONE', 'REQUESTED', 'CONFIRMED', 'REJECTED'] as const

export type RequestStatus = typeof requestStatuses[number]
export type OutboundStatus = typeof outboundStatuses[number]
export type OutboundCancellationStatus = typeof cancellationStatuses[number]

function checkStatus(value: unknown, allowed: readonly string[]) {
  if (typeof value !== 'string' || !allowed.includes(value)) {
    throw new Error('서버가 알 수 없는 업무 상태를 반환했어요.')
  }
}

export function checkRequestStatus(value: unknown) {
  checkStatus(value, requestStatuses)
}

export function checkReplenishmentStatuses(value: Replenishment) {
  checkRequestStatus(value.status)
  value.outboundOrders?.forEach(order => checkStatus(order.status, outboundStatuses))
}

export function checkOutboundStatuses(value: OutboundOrder) {
  checkStatus(value.status, outboundStatuses)
  checkStatus(value.cancellationStatus, cancellationStatuses)
}

export type Session = { username: string; roles: string[]; storeIds: string[]; warehouseIds: string[]; approvalWarehouseIds: string[]; outboundWarehouseIds: string[] }
export type Variant = { variantId: string; skuCode: string; productName: string; colorCode: string; sizeCode: string }
export type Line = { id: string; variantId: string; skuCode: string; requestedQty: number; openQty: number; reservedQty: number; shippedQty: number; closedQty: number }
export type Replenishment = {
  id: string; requestNo: string; outboundOrderId?: string; outboundOrders?: { id: string; status: OutboundStatus }[]; receivedQty?: number; sourceLocationId: string; destinationLocationId: string
  desiredReceiptDate: string; status: RequestStatus; version: string; allowPartial: boolean; lines: Line[]
}
export type Page<T> = { items: T[]; hasNext: boolean }
type Shortage = { requestItemId: string; attemptedQty: number; availableQty: number }
const messages: Record<string, string> = {
  UNAUTHENTICATED: '로그인 정보가 올바르지 않거나 세션이 만료됐어요.',
  FORBIDDEN: '이 요청을 처리할 권한이 없어요.',
  CSRF_INVALID: '보안 토큰이 만료됐어요. 다시 시도해 주세요.',
  VERSION_CONFLICT: '다른 사용자가 먼저 변경했어요. 상세를 새로고침해 주세요.',
  INSUFFICIENT_STOCK: '승인 수량을 확보하지 못했어요. 승인 전체가 취소됐어요.',
  VALIDATION_ERROR: '필수 항목과 수량을 확인해 주세요.',
  IDEMPOTENCY_CONFLICT: '이미 처리된 명령과 입력값이 달라요. 상세를 확인해 주세요.',
  INVALID_STATE: '현재 상태에서 이 작업을 할 수 없어요.',
  PARTIAL_RELEASE_NOT_ALLOWED: '부분 출고를 허용하지 않거나 확보 수량이 없어요.',
  PICKING_REVISION_CONFLICT: '피킹 결과가 바뀌었어요. 출고 상태를 다시 조회해 주세요.',
  RECEIPT_DAMAGE_CONFLICT: '파손 건이 종결됐거나 남은 보류 수량을 초과했어요.',
  RECEIPT_SHORTAGE_CONFLICT: '이미 해결됐거나 남은 부족 수량을 초과했어요. 다시 조회해 주세요.',
  RECEIPT_ALREADY_CONFIRMED: '이미 입고를 확정한 박스예요. 출고 상태를 다시 조회해 주세요.',
  DISPATCH_ALREADY_CREATED: '출고 지시가 생성된 요청이에요.',
  TEMPORARY_CONFLICT: '처리가 지연되고 있어요. 같은 입력으로 다시 시도해 주세요.',
}

export class ApiError extends Error {
  readonly status: number
  readonly shortages: Shortage[]

  constructor(status: number, code: string, shortages: Shortage[] = []) {
    super(messages[code] ?? '요청을 처리하지 못했어요.')
    this.status = status
    this.shortages = shortages
  }
}

export async function api<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch('/api/v1' + path, options)
  if (!response.ok) {
    const error = await response.json().catch(() => ({}))
    throw new ApiError(response.status, error.code, error.shortages)
  }
  if (response.status === 204) return undefined as T
  return response.json()
}

export async function post<T>(path: string, payload?: object | URLSearchParams, key?: string): Promise<T> {
  const { token } = await api<{ token: string }>('/csrf')
  const headers = new Headers({ 'X-CSRF-TOKEN': token })
  if (key) headers.set('Idempotency-Key', key)
  if (payload && !(payload instanceof URLSearchParams)) headers.set('Content-Type', 'application/json')
  return api<T>(path, { method: 'POST', headers,
    body: payload instanceof URLSearchParams ? payload : payload ? JSON.stringify(payload) : undefined })
}

export type OutboundOrder = {
  id: string; requestVersion: string; status: OutboundStatus; pickingRevision?: number; partialReleaseStatus?: 'PENDING' | 'REQUESTED'; cancellationStatus: OutboundCancellationStatus
  lines: { id: string; requestItemId: string; skuCode: string; instructedQty: number; pickedQty?: number; shortageQty?: number; pickingShortage?: { quantity: number; status: 'OPEN' | 'RESOLVED'; outcome?: 'FOUND' | 'LOSS' }; shippedQty: number; lostQty: number; damagedQty: number; inTransitQty: number }[]
}

export type ReceivingCarton = {
  id: string; cartonNo: string; inspectionStatus: 'PENDING' | 'CONFIRMED'; receiptId?: string
  lines: { outboundOrderItemId: string; skuCode: string; shippedQty: number; receivedQty: number; lostQty: number; damagedQty: number; inTransitQty: number; damage?: { id: string; initialQty: number; remainingQty: number; status: string }; shortage?: { id: string; initialQty: number; remainingQty: number; status: string } }[]
}
export type CartonList = { requestVersion: string; cartons: ReceivingCarton[] }
export type ReceiptResult = {
  requestVersion: string; requestStatus: RequestStatus; carton: ReceivingCarton
}
