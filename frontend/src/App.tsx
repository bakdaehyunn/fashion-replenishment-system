import { useEffect, useRef, useState } from 'react'
import type { FormEvent, MouseEvent } from 'react'
import ActionDialog from './ActionDialog'
import { api, ApiError, checkOutboundStatuses, checkReplenishmentStatuses, checkRequestStatus, post } from './api'
import type { CartonList, OutboundOrder, Page, ReceiptResult, ReceivingCarton, Replenishment, RequestStatus, Session, Variant } from './api'

const requests = '/replenishment-requests'
const labels: Record<RequestStatus, string> = {
  SUBMITTED: '승인 대기', FULFILLING: '이행 중', CANCELLED: '취소', REJECTED: '반려', COMPLETED: '완료',
}

const listUrl = '/requests'
const newUrl = listUrl + '/new'
const detailUrl = (id: string) => listUrl + '/' + id
const moved = (event: MouseEvent<HTMLAnchorElement>) =>
  event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey

type Decision =
  | { kind: 'request' | 'outbound' }
  | { kind: 'unfulfilled'; itemId: string; sku: string; quantity: number }
  | { kind: 'loss' | 'disposal'; cartonId: string; caseId: string; sku: string; quantity: number }

async function loadRequests(path: string, options?: RequestInit) {
  const result = await api<Page<Replenishment>>(path, options)
  result.items.forEach(checkReplenishmentStatuses)
  return result
}

function validCount(raw: string | undefined, min: number, max: number) {
  const qty = Number(raw)
  return raw !== '' && Number.isInteger(qty) && qty >= min && qty <= max
}

export default function App() {
  const [location, setLocation] = useState(window.location.pathname)
  const detailId = /^\/requests\/(\d+)\/?$/.exec(location)?.[1]
  const composing = location === newUrl
  const listing = !composing && !detailId
  const [decision, setDecision] = useState<Decision | null>(null)
  const [listLoading, setListLoading] = useState(false)
  const [detailLoading, setDetailLoading] = useState(false)
  const [searched, setSearched] = useState(false)
  const [delivery, setDelivery] = useState({ source: '', destination: '', date: '', partial: false })
  const [session, setSession] = useState<Session | null>(null)
  const [initializing, setInitializing] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [list, setList] = useState<Page<Replenishment>>({ items: [], hasNext: false })
  const [detail, setDetail] = useState<Replenishment | null>(null)
  const [amounts, setAmounts] = useState<Record<string, number>>({})
  const [cartons, setCartons] = useState<CartonList | null>(null)
  const [counted, setCounted] = useState<Record<string, string>>({})
  const [outbound, setOutbound] = useState<OutboundOrder | null>(null)
  const [reason, setReason] = useState('')
  const [skuQuery, setSkuQuery] = useState('')
  const [variants, setVariants] = useState<Variant[]>([])
  const [draft, setDraft] = useState<Array<{ variant: Variant; qty: number }>>([])
  const pendingKeys = useRef(new Map<string, string>())

  useEffect(() => {
    function onPopState() {
      setLocation(window.location.pathname)
      setDecision(null)
      setError('')
      setNotice('')
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
  }, [])

  function navigate(path: string) {
    if (window.location.pathname === path) return
    window.history.pushState(null, '', path)
    setLocation(path)
    setDecision(null)
    setError('')
    setNotice('')
    window.scrollTo(0, 0)
  }

  function follow(event: MouseEvent<HTMLAnchorElement>, path: string) {
    if (!moved(event)) return
    event.preventDefault()
    if (!busy) navigate(path)
  }

  useEffect(() => {
    api<Session>('/session').then(enterSession).catch((err: unknown) => {
      if (!(err instanceof ApiError && err.status === 401)) setError('서버에 연결할 수 없어요.')
    }).finally(() => setInitializing(false))
  }, [])

  const listPath = requests + '?' + new URLSearchParams({ page: String(page), ...(status ? { status } : {}) })
  useEffect(() => {
    if (!session || !listing) return
    const abort = new AbortController()
    void Promise.resolve().then(async () => {
      if (abort.signal.aborted) return
      setListLoading(true)
      try {
        const result = await loadRequests(listPath, { signal: abort.signal })
        if (!abort.signal.aborted) setList(result)
      } catch (err) {
        if (!abort.signal.aborted) setError(err instanceof Error ? err.message : '목록을 불러오지 못했어요.')
      } finally {
        if (!abort.signal.aborted) setListLoading(false)
      }
    })
    return () => abort.abort()
  }, [session, listPath, listing])

  useEffect(() => {
    if (!session || !detailId) return
    const controller = new AbortController()
    const options = { signal: controller.signal }
    void Promise.resolve().then(async () => {
      if (controller.signal.aborted) return
      setDetailLoading(true)
      try {
        const request = await api<Replenishment>(`${requests}/${detailId}`, options)
        if (controller.signal.aborted) return
        show(request)
        const orderId = request.outboundOrders?.at(-1)?.id ?? request.outboundOrderId
        if (!orderId) return
        const base = `${requests}/${detailId}/outbound-orders/${orderId}`
        const [order, packing] = await Promise.all([api<OutboundOrder>(base, options), api<CartonList>(base + '/cartons', options)])
        if (controller.signal.aborted) return
        checkOutboundStatuses(order)
        setOutbound(order)
        setCartons(packing)
      } catch (err) {
        if (!controller.signal.aborted) setError(err instanceof Error ? err.message : '상세를 불러오지 못했어요.')
      } finally {
        if (!controller.signal.aborted) setDetailLoading(false)
      }
    })
    return () => controller.abort()
  }, [session, detailId])

  function show(value: Replenishment) {
    checkReplenishmentStatuses(value)
    setDetail(value)
    setOutbound(null)
    setCartons(null)
    setCounted({})
    setAmounts(Object.fromEntries(value.lines.map(line => [line.id, line.openQty])))
    setReason('')
  }

  async function run(action: () => Promise<void>) {
    if (busy) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      await action()
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) setSession(null)
      const shortages = err instanceof ApiError ? err.shortages.map(shortage => {
        const sku = detail?.lines.find(line => line.id === shortage.requestItemId)?.skuCode
        return `${sku ?? shortage.requestItemId} · 승인 ${shortage.attemptedQty} / 가용 ${shortage.availableQty}`
      }) : []
      setError([err instanceof ApiError ? err.message : '서버 응답을 확인하지 못했어요. 같은 입력으로 다시 시도해 주세요.', ...shortages].join('\n'))
    } finally {
      setBusy(false)
    }
  }

  async function command<T>(path: string, body: object): Promise<T> {
    const signature = path + JSON.stringify(body)
    const key = pendingKeys.current.get(signature) ?? crypto.randomUUID()
    pendingKeys.current.set(signature, key)
    const result = await post<T>(path, body, key)
    pendingKeys.current.delete(signature)
    return result
  }

  function enterSession(value: Session) {
    setStatus(value.roles.includes('APPROVER') && !value.roles.includes('STORE') ? 'SUBMITTED' : '')
    setSession(value)
  }

  function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    void run(async () => {
      await post('/session', new URLSearchParams({
        username: String(form.get('username')), password: String(form.get('password')),
      }))
      enterSession(await api<Session>('/session'))
    })
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    void run(async () => {
      const created = await command<Replenishment>(requests, {
        sourceLocationId: form.get('source'), destinationLocationId: form.get('destination'),
        desiredReceiptDate: form.get('date'), allowPartial: form.has('partial'),
        lines: draft.map(({ variant, qty }) => ({ variantId: variant.variantId, requestedQty: qty })),
      })
      setDraft([])
      setVariants([])
      setSkuQuery('')
      setSearched(false)
      setDelivery({ source: '', destination: '', date: '', partial: false })
      show(created)
      navigate(detailUrl(created.id))
      setNotice('보충 요청을 제출했어요.')
    })
  }

  function change(kind: 'approve' | 'cancel', cancellationReason = '') {
    if (!detail) return
    const path = `${requests}/${detail.id}`
    void run(async () => {
      if (kind === 'approve') {
        const result = await command<{ request: Replenishment }>(path + '/approvals', {
          expectedVersion: detail.version, ...(reason.trim() ? { reason: reason.trim() } : {}),
          lines: detail.lines.map(line => ({ requestItemId: line.id, approvedQty: amounts[line.id] })),
        })
        show(result.request)
        setNotice('승인하고 재고를 예약했어요.')
      } else {
        show(await command<Replenishment>(path + '/cancellation', {
          expectedVersion: detail.version, reason: cancellationReason,
        }))
        setDecision(null)
        setNotice('보충 요청을 취소했어요.')
      }
    })
  }

  async function refreshOutbound(requestId: string, orderId: string) {
    const path = `${requests}/${requestId}/outbound-orders/${orderId}`
    const [request, order, packing] = await Promise.all([
      api<Replenishment>(`${requests}/${requestId}`),
      api<OutboundOrder>(path),
      api<CartonList>(path + '/cartons'),
    ])
    checkOutboundStatuses(order)
    show(request)
    setOutbound(order)
    setCartons(packing)
  }

  function confirmReceipt(carton: ReceivingCarton) {
    if (!detail || !outbound || !cartons) return
    const orderId = outbound.id
    void run(async () => {
      const result = await command<ReceiptResult>(
        `${requests}/${detail.id}/outbound-orders/${orderId}/cartons/${carton.id}/receipt`,
        {
          expectedVersion: cartons.requestVersion,
          lines: carton.lines.map(line => ({
            outboundOrderItemId: line.outboundOrderItemId,
            receivedQty: Number(counted[`${carton.id}/${line.outboundOrderItemId}`]),
            damagedQty: Number(counted[`damage/${carton.id}/${line.outboundOrderItemId}`] || 0),
          })),
        },
      )
      checkRequestStatus(result.requestStatus)
      setDetail({ ...detail, version: result.requestVersion, status: result.requestStatus })
      setCartons({ requestVersion: result.requestVersion,
        cartons: cartons.cartons.map(item => item.id === carton.id ? result.carton : item) })
      setNotice(result.carton.lines.some(line => line.shortage?.remainingQty || line.damage?.remainingQty) ? '최초 검수를 확정했어요. 조사할 수량이 남아 있어요.' : '정상 입고를 확정했어요.')
      await refreshOutbound(detail.id, orderId)
    })
  }

  function addReceipt(cartonId: string, shortageId: string) {
    if (!detail || !outbound || !cartons) return
    const orderId = outbound.id
    void run(async () => {
      await command<ReceiptResult>(
        `${requests}/${detail.id}/outbound-orders/${orderId}/cartons/${cartonId}/shortages/${shortageId}/additional-receipts`,
        { expectedVersion: cartons.requestVersion, receivedQty: Number(counted['shortage/' + shortageId]) },
      )
      setNotice('추가 입고를 확정했어요.')
      await refreshOutbound(detail.id, orderId)
    })
  }

  function settleException(event: FormEvent<HTMLFormElement>, cartonId: string, caseId: string, disposal: boolean) {
    event.preventDefault()
    if (!detail || !outbound || !cartons) return
    const form = new FormData(event.currentTarget)
    const orderId = outbound.id
    const action = disposal ? `damages/${caseId}/disposals` : `shortages/${caseId}/losses`
    void run(async () => {
      await command(
        `${requests}/${detail.id}/outbound-orders/${orderId}/cartons/${cartonId}/${action}`,
        { expectedVersion: cartons.requestVersion, [disposal ? 'disposedQty' : 'lostQty']: Number(form.get('quantity')),
          reason: String(form.get('lossReason')).trim(), evidenceReference: String(form.get('evidence')).trim() },
      )
      setDecision(null)
      setNotice(disposal ? '파손 재고 폐기를 확정했어요.' : '운송 손실을 확정했어요.')
      await refreshOutbound(detail.id, orderId)
    })
  }

  function concludeShortage(event: FormEvent<HTMLFormElement>, itemId: string, investigation: boolean) {
    event.preventDefault()
    if (!detail || !outbound) return
    const form = new FormData(event.currentTarget)
    const path = investigation
      ? `/outbound-orders/${outbound.id}/items/${itemId}/picking-shortage-resolution`
      : `/items/${itemId}/unfulfilled-closure`
    void run(async () => {
      await command(`${requests}/${detail.id}${path}`, {
        expectedVersion: outbound.requestVersion, reason: String(form.get('reason')).trim(),
        ...(investigation ? { outcome: form.get('outcome'), evidenceReference: String(form.get('evidence')).trim() } : {}),
      })
      if (!investigation) setDecision(null)
      setNotice(investigation ? '창고 재고 조사를 종결했어요.' : '미충족 잔량을 종결했어요.')
      await refreshOutbound(detail.id, outbound.id)
    })
  }

  const staff = session?.roles.includes('STORE')
  const canApprove = !!detail && session?.approvalWarehouseIds.includes(detail.sourceLocationId) &&
    (detail.status === 'SUBMITTED' || (detail.status === 'FULFILLING' && (detail.allowPartial ? detail.lines.some(line => line.openQty > 0)
      : detail.lines.every(line => line.openQty === line.requestedQty))))
  const canStopOutbound = outbound?.status === 'PICKING_EXCEPTION' &&
    session?.outboundWarehouseIds.includes(detail?.sourceLocationId ?? '')
  const canCloseRemainder = detail?.outboundOrders?.some(order => order.status === 'SHIPPED') ||
    (detail?.outboundOrders?.length && detail.outboundOrders.every(order => order.status === 'CANCELLED'))
  const canCancel = !!detail && !detail.outboundOrderId && staff && session?.storeIds.includes(detail.destinationLocationId) &&
    ['SUBMITTED', 'FULFILLING'].includes(detail.status)
  const validApproval = detail?.lines.some(line => amounts[line.id] > 0) && detail.lines.every(line =>
    Number.isInteger(amounts[line.id]) && amounts[line.id] >= 0 && amounts[line.id] <= line.openQty) &&
    (detail.lines.every(line => amounts[line.id] === line.openQty) || !!reason.trim())

  const current = detail?.id === detailId ? detail : null
  const orders = current?.outboundOrders ?? (current?.outboundOrderId ? [{ id: current.outboundOrderId }] : [])
  const selectedIndex = orders.findIndex(order => order.id === outbound?.id)
  const requested = current?.lines.reduce((total, line) => total + line.requestedQty, 0) ?? 0
  const remaining = current?.lines.reduce((total, line) => total + line.openQty, 0) ?? 0
  const closed = current?.lines.reduce((total, line) => total + line.closedQty, 0) ?? 0
  const selectedInvestigation = outbound?.lines.reduce((total, line) =>
    total + (line.pickingShortage?.status === 'OPEN' ? line.pickingShortage.quantity : 0), 0) ?? 0

  return (
    <main>
      <header className="app-header"><a className="app-title" href={listUrl} onClick={event => follow(event, listUrl)}>매장 재고 보충 관리</a>
        {session && <div className="row"><span className="muted">{[session.roles.includes('STORE') && '매장', session.roles.includes('APPROVER') && '승인', session.roles.includes('LOGISTICS_OPERATOR') && '물류'].filter(Boolean).join(' · ')}{' · '}{session.username}</span><button className="secondary" disabled={busy} onClick={() => void run(async () => {
          await post('/logout')
          pendingKeys.current.clear()
          setSession(null)
          setDetail(null)
          setDraft([])
          setVariants([])
          setSkuQuery('')
          setSearched(false)
          setDelivery({ source: '', destination: '', date: '', partial: false })
          navigate(listUrl)
          setList({ items: [], hasNext: false })
          setPage(0)
          setStatus('')
        })}>로그아웃</button></div>}
      </header>
      {error && !decision && <p role="alert" className="error">{error}</p>}
      {notice && <p className="notice" role="status">{notice}</p>}
      {initializing ? <p>세션 확인 중…</p> : !session ? (
        <form onSubmit={login} className="panel login">
          <h2>로그인</h2>
          <label>아이디<input name="username" autoComplete="username" required /></label>
          <label>비밀번호<input name="password" type="password" autoComplete="current-password" required /></label>
          <button disabled={busy}>로그인</button>
        </form>
      ) : <>
        {composing && !staff && <p role="alert">매장 직원만 보충 요청을 신청할 수 있어요.</p>}
        {(composing || !!detailId) && <a className="back-link" href={listUrl} onClick={event => follow(event, listUrl)}>← 보충 요청 목록</a>}
        {composing && staff && <section className="panel">
          <h2>새 보충 신청</h2>
          <div className="compose-layout">
          <div className="catalog-pane">
          <h3>상품 선택</h3>
          <form className="row" onSubmit={event => {
            event.preventDefault()
            void run(async () => {
              setVariants((await api<Page<Variant>>('/catalog/variants?' + new URLSearchParams({ q: skuQuery.trim(), size: '100' }))).items)
              setSearched(true)
            })
          }}>
            <label>SKU 또는 상품명<input value={skuQuery} onChange={event => setSkuQuery(event.target.value)} maxLength={100} /></label>
            <button disabled={busy}>상품 검색</button>
          </form>
          {searched && !variants.length && <p className="muted">검색 결과가 없어요.</p>}
          {variants.length > 0 && <ul className="sku-list">{variants.map(variant => <li key={variant.variantId}>
            <span>{variant.productName} · {variant.colorCode} {variant.sizeCode} · {variant.skuCode}</span>
            <button className="secondary" disabled={busy || draft.some(line => line.variant.variantId === variant.variantId)} onClick={() => setDraft([...draft, { variant, qty: 1 }])}>{draft.some(line => line.variant.variantId === variant.variantId) ? '선택됨' : '추가'}</button>
          </li>)}</ul>}
          </div>
          <form className="draft-pane" onSubmit={submit}>
            <fieldset disabled={busy}>
              <h3>신청 품목</h3>
              {!draft.length && <p className="muted">검색 결과에서 상품을 추가하세요.</p>}
              {draft.map(({ variant, qty }) => <div key={variant.variantId} className="row selection">
                <span>{variant.productName} · {variant.colorCode} {variant.sizeCode}</span>
                <label>{variant.skuCode} 요청 수량<input type="number" min="1" max="2147483647" required value={qty} onChange={event => {
                  const value = event.target.valueAsNumber
                  setDraft(draft.map(item => item.variant.variantId === variant.variantId ? { ...item, qty: value } : item))
                }} /></label>
                <button type="button" className="secondary" onClick={() => setDraft(draft.filter(item => item.variant.variantId !== variant.variantId))}>제거</button>
              </div>)}
              <h3>배송 조건</h3>
              <div className="row">
                <label>출발 창고<select name="source" required value={delivery.source || session.warehouseIds[0] || ''} onChange={event => setDelivery({ ...delivery, source: event.target.value })}>{session.warehouseIds.map(id => <option key={id} value={id}>창고 {id}</option>)}</select></label>
                <label>도착 매장<select name="destination" required value={delivery.destination || session.storeIds[0] || ''} onChange={event => setDelivery({ ...delivery, destination: event.target.value })}>{session.storeIds.map(id => <option key={id} value={id}>매장 {id}</option>)}</select></label>
                <label>희망 입고일<input name="date" type="date" required value={delivery.date} onChange={event => setDelivery({ ...delivery, date: event.target.value })} /></label>

              </div>
              <label className="check"><input type="checkbox" name="partial" checked={delivery.partial}
                onChange={event => setDelivery({ ...delivery, partial: event.target.checked })} /> 일부 수량을 먼저 받을 수 있습니다</label>
              <button disabled={!draft.length || !session.storeIds.length || !session.warehouseIds.length}>보충 신청 제출</button>
            </fieldset>
          </form>
          </div>
        </section>}
        {listing && <section className="panel">
          <div className="section-header"><h2>보충 요청 목록</h2>
            {staff && <a className="primary-link" href={newUrl} onClick={event => follow(event, newUrl)}>새 보충 신청</a>}
          </div>
          <div className="row toolbar">
            <label>요청 상태<select value={status} onChange={event => { setStatus(event.target.value); setPage(0) }}>
              <option value="">전체</option>{Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select></label>
            <button className="secondary" disabled={busy || listLoading} onClick={() => void run(async () => setList(await loadRequests(listPath)))}>목록 새로고침</button>
          </div>
          <div aria-busy={listLoading}>
            {listLoading ? <p role="status">요청을 불러오는 중…</p> : list.items.length ? <div className="table-wrap"><table>
              <thead><tr><th scope="col">요청번호</th><th scope="col">도착 매장</th><th scope="col">희망 입고일</th><th scope="col">상태</th></tr></thead>
              <tbody>{list.items.map(item => <tr key={item.id}>
                <th scope="row"><a href={detailUrl(item.id)} onClick={event => follow(event, detailUrl(item.id))}>{item.requestNo}</a></th>
                <td>매장 {item.destinationLocationId}</td><td>{item.desiredReceiptDate}</td>
                <td><span className="status-label" data-state={item.status}>{labels[item.status]}</span></td>
              </tr>)}</tbody>
            </table></div> : <p className="empty-state">{status ? '선택한 상태의 요청이 없어요.' : '등록된 보충 요청이 없어요.'}</p>}
          </div>
          {(page > 0 || list.hasNext) && <div className="row pagination"><button className="secondary" disabled={page === 0 || listLoading || busy} onClick={() => setPage(page - 1)}>이전</button><span>{page + 1}쪽</span><button className="secondary" disabled={!list.hasNext || listLoading || busy} onClick={() => setPage(page + 1)}>다음</button></div>}
        </section>}
        {detailId && detailLoading && !current && <p role="status">요청 상세를 불러오는 중…</p>}
        {detail && current && <section className="panel request-detail">
          <div className="section-header"><h2>{detail.requestNo} 상세</h2><button className="secondary" disabled={busy || detailLoading} onClick={() => void run(async () => {
            if (outbound) await refreshOutbound(detail.id, outbound.id)
            else show(await api<Replenishment>(`${requests}/${detail.id}`))
          })}>상세 새로고침</button></div>
          <p className="muted">창고 {detail.sourceLocationId} → 매장 {detail.destinationLocationId} · 희망 입고일 {detail.desiredReceiptDate}</p>
          <div className="request-summary">
            <span className="status-label" data-state={detail.status}>{labels[detail.status]}</span>
            <strong>{requested}장 중 {detail.receivedQty ?? 0}장 정상 입고</strong>
            {remaining > 0 && <span>미배정 {remaining}장</span>}
            {closed > 0 && <span>미충족 종결 {closed}장</span>}
            {selectedInvestigation > 0 && <span>선택 회차 조사 보류 {selectedInvestigation}장</span>}
          </div>
          <h3>품목별 수량</h3>
          <div className="table-wrap"><table><thead><tr><th>SKU</th><th>요청</th><th>열린 잔량</th><th>예약</th><th>출고</th><th>종결</th>{canApprove && <th>승인할 수량</th>}</tr></thead><tbody>
            {detail.lines.map(line => <tr key={line.id}><th scope="row">{line.skuCode}</th><td>{line.requestedQty}</td><td>{line.openQty}</td><td>{line.reservedQty}</td><td>{line.shippedQty}</td><td>{line.closedQty}</td>
              {canApprove && <td><input type="number" min="0" max={line.openQty} aria-label={line.skuCode + ' 승인 수량'} disabled={busy || !detail.allowPartial} value={amounts[line.id]} onChange={event => { const qty = event.target.valueAsNumber; setAmounts(current => ({ ...current, [line.id]: qty })) }} /></td>}
            </tr>)}</tbody></table></div>
          {(canApprove || canCancel) && <div className="row task-actions">
            {canApprove && <label>승인 사유 (부분 승인 시 필수)<input value={reason} maxLength={500} onChange={event => setReason(event.target.value)} /></label>}
            {canApprove && <button disabled={busy || !validApproval} onClick={() => change('approve')}>승인 및 예약</button>}
            {canCancel && <button className="secondary" disabled={busy} onClick={() => { setError(''); setDecision({ kind: 'request' }) }}>요청 취소</button>}
          </div>}
          {session.outboundWarehouseIds.includes(detail.sourceLocationId) && !!canCloseRemainder && outbound &&
            detail.status === 'FULFILLING' && detail.lines.filter(line => line.openQty > 0).map(line =>
              <div key={line.id} className="row task-actions">
                <span>{line.skuCode} · 미배정 {line.openQty}장</span>
                <button className="secondary" disabled={busy} onClick={() => {
                  setError('')
                  setDecision({ kind: 'unfulfilled', itemId: line.id, sku: line.skuCode, quantity: line.openQty })
                }}>미충족 잔량 종결</button>
              </div>)}
          <h3 className="outbound-heading">출고 및 입고 검수</h3>
          {!(detail.outboundOrders ?? []).some(order => !['SHIPPED', 'CANCELLED'].includes(order.status)) && detail.status === 'FULFILLING' &&
            session.outboundWarehouseIds?.includes(detail.sourceLocationId) &&
            detail.lines.some(line => line.reservedQty > 0) &&
            (detail.allowPartial || detail.lines.every(line => line.reservedQty === line.requestedQty)) &&
            <button disabled={busy} onClick={() => void run(async () => {
              const result = await command<OutboundOrder>(`${requests}/${detail.id}/outbound-orders`, {
                expectedVersion: detail.version,
              })
              checkOutboundStatuses(result)
              await refreshOutbound(detail.id, result.id)
            })}>출고 지시 생성</button>}
          {!orders.length && <p className="muted">생성된 출고 지시가 없어요.</p>}
          {detailLoading && orders.length > 0 && !outbound && <p role="status">출고 내역을 불러오는 중…</p>}
          {orders.length > 1 && <div className="shipment-tabs" role="tablist" aria-label="출고 회차">
            {orders.map((order, index) => <button type="button" role="tab" key={order.id}
              aria-selected={selectedIndex === index} id={'shipment-tab-' + order.id} aria-controls="shipment-panel"
              tabIndex={index === Math.max(selectedIndex, 0) ? 0 : -1}
              onClick={() => { if (selectedIndex !== index) void run(() => refreshOutbound(detail.id, order.id)) }}
              onKeyDown={event => {
                const next = event.key === 'ArrowRight' ? (index + 1) % orders.length
                  : event.key === 'ArrowLeft' ? (index + orders.length - 1) % orders.length
                  : event.key === 'Home' ? 0 : event.key === 'End' ? orders.length - 1 : null
                if (next === null) return
                event.preventDefault()
                if (busy || next === index) return
                const nextOrder = orders[next]
                document.getElementById('shipment-tab-' + nextOrder.id)?.focus()
                void run(() => refreshOutbound(detail.id, nextOrder.id))
              }}>{index + 1}차 출고</button>)}
          </div>}
          {orders.length > 0 && !outbound && !detailLoading && <button className="secondary" disabled={busy}
            onClick={() => void run(() => refreshOutbound(detail.id, orders[0].id))}>출고 내역 다시 불러오기</button>}
          {outbound && <div id="shipment-panel" className="shipment-panel" role={orders.length > 1 ? 'tabpanel' : undefined}
            aria-labelledby={orders.length > 1 ? 'shipment-tab-' + outbound.id : undefined}>
            <h4>{selectedIndex + 1}차 출고 상세</h4>
            {outbound.cancellationStatus !== 'NONE' && <p role="status">{{
              REQUESTED: '취소 확인 중', CONFIRMED: '출고 취소 확정', REJECTED: '취소 거절 · 출고 확정',
            }[outbound.cancellationStatus]}</p>}
            {(canStopOutbound || (staff && session.storeIds.includes(detail.destinationLocationId))) && outbound.cancellationStatus === 'NONE'
              && !['SHIPPED', 'CANCELLED'].includes(outbound.status) &&
              <button className="secondary" disabled={busy} onClick={() => { setError(''); setDecision({ kind: 'outbound' }) }}>
                {canStopOutbound ? '출고 지시만 취소' : '보충 요청 취소'}
              </button>}
            <p>{{ DISPATCH_PENDING: '전달 대기', ACCEPTANCE_UNKNOWN: '접수 결과 미확인 · 자동 재전송 대기', ACCEPTED: '접수 완료', WORKING: '작업 중',
              PICKING_EXCEPTION: '피킹 부족 · 조사 보류', READY_TO_SHIP: '출고 준비', SHIPPED: '출고 완료', CANCELLED: '출고 취소' }[outbound.status] ?? outbound.status}</p>
            <ul>{outbound.lines.map(line => <li key={line.id}>{line.skuCode} · 지시 {line.instructedQty}{line.pickedQty !== undefined && ` · 확보 ${line.pickedQty} · 부족 ${line.shortageQty}`} · 출고 {line.shippedQty} · 운송 중 {line.inTransitQty}{line.lostQty > 0 && ` · 운송 손실 ${line.lostQty}`}</li>)}</ul>
            {outbound.status === 'PICKING_EXCEPTION' && (outbound.partialReleaseStatus === 'REQUESTED'
              ? <p role="status">부분 출고 승인 전달 중</p>
              : !detail.allowPartial ? <p>이 요청은 부분 출고를 허용하지 않아요.</p>
                : !outbound.lines.some(line => (line.pickedQty ?? 0) > 0) ? <p>출고할 수 있는 확보 수량이 없어요.</p>
                  : outbound.cancellationStatus === 'NONE' && session.outboundWarehouseIds.includes(detail.sourceLocationId) &&
                    <button disabled={busy} onClick={() => void run(async () => {
                      const result = await command<OutboundOrder>(
                        `${requests}/${detail.id}/outbound-orders/${outbound.id}/partial-release`,
                        { expectedVersion: outbound.requestVersion, pickingRevision: outbound.pickingRevision },
                      )
                      checkOutboundStatuses(result)
                      setOutbound(result)
                      setNotice('확보한 수량의 부분 출고를 승인했어요.')
                    })}>부분 출고 승인</button>)}
            {outbound.lines.filter(line => line.pickingShortage).map(line => {
              const shortage = line.pickingShortage!
              const canManage = session.outboundWarehouseIds.includes(detail.sourceLocationId)
              return <div key={line.id} className="panel">
                <p>{line.skuCode} · 창고 조사 {shortage.status === 'OPEN'
                  ? `보류 ${shortage.quantity}장`
                  : shortage.outcome === 'FOUND' ? '종결 · 실물 발견' : '종결 · 손실 확정'}</p>
                {canManage && shortage.status === 'OPEN' && <form className="row" onSubmit={event => concludeShortage(event,line.id,true)}>
                  <label>실사 결과<select name="outcome"><option value="LOSS">손실 확정</option><option value="FOUND">실물 발견</option></select></label>
                  <label>조사 사유<input name="reason" maxLength={500} required /></label>
                  <label>실사 근거 번호<input name="evidence" maxLength={200} required /></label>
                  <button disabled={busy}>창고 조사 종결</button>
                </form>}
              </div>
            })}
          {cartons?.cartons.map(carton => {
            const canReceive = carton.inspectionStatus === 'PENDING' && staff &&
              session.storeIds.includes(detail.destinationLocationId)
            return <div className="panel" key={carton.id}>
              <h3>박스 {carton.cartonNo}</h3>
              <p>{carton.inspectionStatus === 'CONFIRMED' ? '검수 확정' : '검수 대기'}</p>
              <div className="table-wrap"><table>
                <thead><tr>
                  <th scope="col">SKU</th><th scope="col">출고 수량</th>
                  {canReceive ? <>
                    <th scope="col">정상 확인 수량</th><th scope="col">파손 확인 수량</th>
                  </> : <>
                    <th scope="col">정상 입고</th><th scope="col">파손 입고</th>
                    <th scope="col">손실</th><th scope="col">운송 중</th>
                  </>}
                </tr></thead>
                <tbody>{carton.lines.map(line => {
                  const key = `${carton.id}/${line.outboundOrderItemId}`
                  const damageKey = 'damage/' + key
                  return <tr key={key}><th scope="row">{line.skuCode}</th><td>{line.shippedQty}</td>
                    {canReceive ? <>
                      <td><input type="number" min="0" max={line.shippedQty} step="1"
                        aria-label={line.skuCode + ' 정상 확인 수량'} disabled={busy}
                        value={counted[key] ?? ''} onChange={event => {
                          const value = event.target.value
                          setCounted(current => ({ ...current, [key]: value }))
                        }} /></td>
                      <td><input type="number" min="0" max={line.shippedQty} step="1"
                        aria-label={line.skuCode + ' 파손 확인 수량'} disabled={busy}
                        value={counted[damageKey] ?? '0'} onChange={event => {
                          const value = event.target.value
                          setCounted(current => ({ ...current, [damageKey]: value }))
                        }} /></td>
                    </> : <>
                      <td>{line.receivedQty}</td><td>{line.damagedQty}</td><td>{line.lostQty}</td><td>{line.inTransitQty}</td>
                    </>}
                  </tr>
                })}</tbody>
              </table></div>
              {canReceive && <button disabled={busy || carton.lines.some(line => {
                const key = `${carton.id}/${line.outboundOrderItemId}`
                const damage = counted['damage/' + key] || '0'
                return !validCount(damage, 0, line.shippedQty)
                  || !validCount(counted[key], 0, line.shippedQty - Number(damage))
              })}
                onClick={() => confirmReceipt(carton)}>최초 검수 확정</button>}
              {carton.lines.map(line => {
                const damage = line.damage
                if (!damage) return null
                return <div key={damage.id}>
                  <p>{line.skuCode} · 파손 건 #{damage.id} · {damage.remainingQty > 0
                    ? `보류 ${damage.remainingQty}장 · 파손 조사 중` : '파손 처리 완료'}</p>
                  {damage.remainingQty > 0 && session.outboundWarehouseIds.includes(detail.sourceLocationId) &&
                    <button className="secondary" disabled={busy} onClick={() => {
                      setError('')
                      setDecision({ kind: 'disposal', cartonId: carton.id, caseId: damage.id, sku: line.skuCode, quantity: damage.remainingQty })
                    }}>파손 재고 폐기</button>}
                </div>
              })}
              {carton.lines.map(line => {
                const shortage = line.shortage
                if (!shortage) return null
                const key = 'shortage/' + shortage.id
                return <div className="row" key={key}>
                  <p>{line.skuCode} · 차이 건 #{shortage.id} · {shortage.remainingQty > 0
                    ? `부족 ${shortage.remainingQty}장 · 조사 중` : '수량 차이 해결'}</p>
                  {shortage.remainingQty > 0 && staff && session.storeIds.includes(detail.destinationLocationId) && <>
                    <label>{line.skuCode} 추가 입고 수량<input type="number" min="1" step="1"
                      max={shortage.remainingQty} disabled={busy} value={counted[key] ?? ''}
                      onChange={event => {
                        const value = event.target.value
                        setCounted(current => ({ ...current, [key]: value }))
                      }} /></label>
                    <button disabled={busy || !validCount(counted[key], 1, shortage.remainingQty)}
                      onClick={() => addReceipt(carton.id, shortage.id)}>추가 입고 확정</button>
                  </>}
                  {shortage.remainingQty > 0 && session.outboundWarehouseIds.includes(detail.sourceLocationId) &&
                    <button className="secondary" disabled={busy} onClick={() => {
                      setError('')
                      setDecision({ kind: 'loss', cartonId: carton.id, caseId: shortage.id, sku: line.skuCode, quantity: shortage.remainingQty })
                    }}>운송 손실 처리</button>}
                </div>
              })}
            </div>
          })}
          </div>}
        </section>}
        {decision && current && <ActionDialog
          title={decision.kind === 'request' ? '보충 요청 취소' : decision.kind === 'outbound'
            ? canStopOutbound ? '출고 지시만 취소' : '보충 요청 취소'
            : decision.kind === 'unfulfilled' ? '미충족 잔량 종결'
              : decision.kind === 'loss' ? '운송 손실 처리' : '파손 재고 폐기'}
          description={decision.kind === 'request' ? `${current.requestNo}의 보충 요청을 취소하고 예약된 수량을 해제합니다.`
            : decision.kind === 'outbound' ? '창고에서 출고 취소를 확정할 때까지 예약을 유지합니다.'
              : 'quantity' in decision ? `${decision.sku} · 처리 대상 ${decision.quantity}장` : ''}
          confirmLabel={decision.kind === 'request' || decision.kind === 'outbound' ? '취소 요청 확정' : '처리 확정'}
          busy={busy} error={error} onDismiss={() => { setDecision(null); setError('') }}
          onSubmit={event => {
            event.preventDefault()
            const form = new FormData(event.currentTarget)
            if (decision.kind === 'unfulfilled') {
              concludeShortage(event, decision.itemId, false)
              return
            }
            if (decision.kind === 'loss' || decision.kind === 'disposal') {
              settleException(event, decision.cartonId, decision.caseId, decision.kind === 'disposal')
              return
            }
            const value = String(form.get('reason')).trim()
            if (!value) { setError('사유를 입력해 주세요.'); return }
            if (decision.kind === 'request') { change('cancel', value); return }
            if (!outbound) return
            void run(async () => {
              const result = await command<OutboundOrder>(
                `${requests}/${current.id}/outbound-orders/${outbound.id}/cancellation${canStopOutbound ? '?outboundOnly=true' : ''}`,
                { expectedVersion: outbound.requestVersion, reason: value },
              )
              checkOutboundStatuses(result)
              setOutbound(result)
              setDecision(null)
              setNotice('창고에 취소를 요청했어요. 출고 상태를 새로고침해 결과를 확인할 수 있어요.')
            })
          }}>
          {(decision.kind === 'loss' || decision.kind === 'disposal') ? <>
            <label>처리 수량<input name="quantity" type="number" min="1" max={decision.quantity} step="1" required /></label>
            <label>처리 사유<input name="lossReason" maxLength={500} required /></label>
            <label>조사 근거 번호<input name="evidence" maxLength={200} required /></label>
          </> : <label>사유<input name="reason" maxLength={500} required /></label>}
        </ActionDialog>}
      </>}
    </main>
  )
}
