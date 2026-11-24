import { useEffect, useRef } from 'react'
import type { FormEvent, ReactNode } from 'react'

type Props = {
  title: string
  description: string
  confirmLabel: string
  busy: boolean
  error: string
  onDismiss: () => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
  children: ReactNode
}

export default function ActionDialog({ title, description, confirmLabel, busy, error, onDismiss, onSubmit, children }: Props) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current
    const trigger = document.activeElement
    dialog?.showModal()
    return () => {
      dialog?.close()
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus()
    }
  }, [])

  return <dialog ref={ref} aria-labelledby="action-dialog-title" aria-describedby="action-dialog-description"
    onCancel={event => { event.preventDefault(); if (!busy) onDismiss() }}>
    <form onSubmit={onSubmit}>
      <h2 id="action-dialog-title">{title}</h2>
      <p id="action-dialog-description" className="muted">{description}</p>
      {error && <p role="alert" className="error">{error}</p>}
      <fieldset disabled={busy}>{children}</fieldset>
      <div className="dialog-actions">
        <button type="button" className="secondary" disabled={busy} onClick={onDismiss}>돌아가기</button>
        <button className="danger" disabled={busy}>{busy ? '처리 중…' : confirmLabel}</button>
      </div>
    </form>
  </dialog>
}
