import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type Identity } from '../api'
import { useSession } from '../session'
import { ErrorBox, PageHead } from '../ui'

const MIN = 12

/** A new password, typed twice: whether it can be saved, and why not. */
function problem(password: string, again: string): string | null {
  if (password.length < MIN) return `At least ${MIN} characters.`
  if (password !== again) return 'The two don’t match.'
  return null
}

/**
 * Changing one's own password: the current one, then the new one twice. With `forced`, it replaces a
 * temporary password an admin set, before anything else on the platform works.
 */
export function ChangePassword({ me, forced, onDone }: { me: Identity; forced?: boolean; onDone?: () => void }) {
  const queryClient = useQueryClient()
  const [current, setCurrent] = useState('')
  const [password, setPassword] = useState('')
  const [again, setAgain] = useState('')
  const [done, setDone] = useState(false)
  const why = problem(password, again)
  const change = useMutation({
    mutationFn: () => api.setPassword(me.id, { password, currentPassword: current }),
    onSuccess: async () => {
      setDone(true)
      setCurrent('')
      setPassword('')
      setAgain('')
      await queryClient.invalidateQueries()
      onDone?.()
    },
  })
  return (
    <form className="card list" style={{ gap: 12, maxWidth: 460 }} onSubmit={(e) => { e.preventDefault(); if (!why) change.mutate() }}>
      <h2>{forced ? 'Choose your own password' : 'Change password'}</h2>
      {forced && <p className="hint">An admin set a temporary password for you. Choose a new one to continue.</p>}
      <label className="field">{forced ? 'Temporary password' : 'Current password'}
        <input className="input" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required />
      </label>
      <label className="field">New password
        <input className="input" type="password" autoComplete="new-password" value={password} onChange={(e) => { setPassword(e.target.value); setDone(false) }} required />
      </label>
      <label className="field">New password again
        <input className="input" type="password" autoComplete="new-password" value={again} onChange={(e) => setAgain(e.target.value)} required />
      </label>
      {password && why && <p className="hint">{why}</p>}
      <ErrorBox error={change.error} />
      {done && !forced && <div className="note note-ok" role="status">Your password is changed.</div>}
      <div className="actions">
        <button type="submit" className="btn btn-primary" disabled={!!why || !current || change.isPending}>
          {change.isPending ? 'Saving…' : forced ? 'Save and continue' : 'Change password'}</button>
      </div>
    </form>
  )
}

/** The signed-in person's page: their password, when they sign in with one of the platform's. */
export function AccountPage() {
  const { me } = useSession()
  return (
    <>
      <PageHead title="Account" sub={`Signed in as ${me.name}.`} />
      <div className="body">
        <section className="col" aria-label="Password">
          {me.password ? <ChangePassword me={me} />
            : <p className="hint">You sign in with {me.signsInWith === 'oidc' ? 'your company account' : me.signsInWith === 'ldap'
              ? 'the company directory' : 'a token'}; there is no platform password to change.</p>}
        </section>
      </div>
    </>
  )
}

/** For admins, on a person: set a (temporary) password, and unlock an account wrong passwords locked. */
export function PersonPassword({ identity }: { identity: Identity }) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [again, setAgain] = useState('')
  const [temporary, setTemporary] = useState(true)
  const why = problem(password, again)
  const set = useMutation({
    mutationFn: () => api.setPassword(identity.id, { password, mustChange: temporary }),
    onSuccess: async () => {
      setOpen(false)
      setPassword('')
      setAgain('')
      await queryClient.invalidateQueries({ queryKey: ['identities'] })
    },
  })
  const unlock = useMutation({
    mutationFn: () => api.unlock(identity.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['identities'] }),
  })
  if (identity.kind !== 'person' || identity.signsInWith === 'oidc') return null
  const locked = identity.lockedUntil && new Date(identity.lockedUntil) > new Date()
  return (
    <div className="list" style={{ gap: 8 }}>
      <span className="actions">
        {locked && <span className="tag">locked until {new Date(identity.lockedUntil!).toLocaleTimeString()}</span>}
        {identity.mustChangePassword && <span className="tag">temporary password</span>}
        {identity.signsInWith !== 'ldap' && (
          <button type="button" className="btn btn-sm" aria-expanded={open} onClick={() => setOpen(!open)}>
            {identity.password ? 'Set password' : 'Give a password'}</button>
        )}
        {locked && <button type="button" className="btn btn-sm btn-soft" disabled={unlock.isPending} onClick={() => unlock.mutate()}>Unlock</button>}
      </span>
      {open && (
        <form className="list" style={{ gap: 8 }} onSubmit={(e) => { e.preventDefault(); if (!why) set.mutate() }}>
          <label className="field">Password for {identity.name}
            <input className="input" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </label>
          <label className="field">Again
            <input className="input" type="password" autoComplete="new-password" value={again} onChange={(e) => setAgain(e.target.value)} required />
          </label>
          <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 14 }}><input type="checkbox" checked={temporary} onChange={(e) => setTemporary(e.target.checked)} />
            Temporary: {identity.name} chooses a new one at the next sign-in</label>
          {password && why && <p className="hint">{why}</p>}
          <ErrorBox error={set.error} />
          <div className="actions">
            <button type="submit" className="btn btn-primary btn-sm" disabled={!!why || set.isPending}>Save</button>
            <button type="button" className="btn btn-sm" onClick={() => setOpen(false)}>Cancel</button>
          </div>
        </form>
      )}
      <ErrorBox error={unlock.error} />
    </div>
  )
}
