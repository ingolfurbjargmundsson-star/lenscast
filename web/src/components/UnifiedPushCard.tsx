import { createSignal, For, onMount, Show } from 'solid-js'
import type { AllSettings } from '../types'
import { API_DEFAULTS } from '../api/defaults'
import SettingsCard from './SettingsCard'
import ToggleRow from './ToggleRow'
import {
  listUnifiedPushTargets,
  removeUnifiedPushTarget,
  type UnifiedPushTargetInfo,
} from '../api/client'
import { t } from '../lib/i18n'

interface Props {
  settings: () => AllSettings | null
  updateStreamingAndSave: (patch: Partial<AllSettings['streaming']>) => void
}

/**
 * UnifiedPush alerting: paired receiver apps (Android distributors or any
 * UnifiedPush client) register an RFC 8030 endpoint with this camera through
 * /api/push/unifiedpush, and the camera publishes the same RFC 8291-encrypted
 * detection payload it sends to browsers. This card owns the independent
 * device gate and the target readout; pairing a receiver itself is done by
 * the receiver with the camera's VAPID key (or the pairing JSON from the Auth
 * card).
 */
export default function UnifiedPushCard(props: Props) {
  const s = () => props.settings()
  const enabled = () => s()?.streaming.unifiedPushEnabled ?? API_DEFAULTS.unifiedPushEnabled

  const [targets, setTargets] = createSignal<UnifiedPushTargetInfo[]>([])
  // The server owns the cap (PushSubscriptionPolicy.MAX_UNIFIEDPUSH_TARGETS);
  // null until the first successful read, so this card never duplicates it.
  const [maxTargets, setMaxTargets] = createSignal<number | null>(null)
  const [vapid, setVapid] = createSignal('')
  const [loading, setLoading] = createSignal(false)
  const [message, setMessage] = createSignal('')

  async function refresh() {
    setLoading(true)
    try {
      const response = await listUnifiedPushTargets()
      setTargets(response.targets ?? [])
      setMaxTargets(response.maxTargets)
      setVapid(response.vapidPublicKey ?? '')
    } catch (e) {
      setMessage(e instanceof Error ? e.message : t('unifiedPush.loadFailed'))
    } finally {
      setLoading(false)
    }
  }

  async function remove(endpoint: string) {
    try {
      await removeUnifiedPushTarget(endpoint)
      await refresh()
      setMessage(t('unifiedPush.removed'))
    } catch (e) {
      setMessage(e instanceof Error ? e.message : t('unifiedPush.removeFailed'))
    }
  }

  async function copyVapid() {
    try {
      await navigator.clipboard.writeText(vapid())
      setMessage(t('unifiedPush.copied'))
    } catch {
      setMessage(t('unifiedPush.copyFailed'))
    }
  }

  onMount(() => {
    void refresh()
  })

  return (
    <SettingsCard
      icon={
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 3v12" />
          <path d="M8 11l4 4 4-4" />
          <path d="M4 17v2a2 2 0 002 2h12a2 2 0 002-2v-2" />
        </svg>
      }
      title={t('unifiedPush.title')}
    >
      <div class="field-group">
        <ToggleRow
          id="unifiedpush-enabled-toggle"
          label={t('unifiedPush.deviceToggle')}
          checked={enabled()}
          onToggle={() => props.updateStreamingAndSave({ unifiedPushEnabled: !enabled() })}
        />
        <div class="status-banner status-banner-info stream-mode-hint" role="note" aria-live="polite">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>{t('unifiedPush.desc')}</span>
        </div>
      </div>

      <div class="field-group">
        <div class="field-row">
          <span class="field-label">{t('unifiedPush.vapidKey')}</span>
          <span class="field-value mono">{vapid() || t('unifiedPush.loading')}</span>
        </div>
        <button type="button" class="action-btn action-btn-ghost" disabled={!vapid()} onClick={() => void copyVapid()}>
          {t('unifiedPush.copyKey')}
        </button>
        <div class="status-banner status-banner-info stream-mode-hint" role="note">
          <span class="status-banner-dot" aria-hidden="true" />
          <span>{t('unifiedPush.pairingHint')}</span>
        </div>
      </div>

      <div class="field-group">
        <div class="field-row">
          <span class="field-label">
            {t('unifiedPush.targets', { count: targets().length, max: maxTargets() ?? '—' })}
          </span>
          <button type="button" class="action-btn action-btn-ghost" disabled={loading()} onClick={() => void refresh()}>
            {loading() ? t('unifiedPush.loading') : t('unifiedPush.refresh')}
          </button>
        </div>
        <ul class="clients-list">
          <For each={targets()} fallback={<li class="clients-empty">{t('unifiedPush.noTargets')}</li>}>
            {(target) => (
              <li class="client-row">
                <span class="client-id" title={target.endpoint}>
                  {target.label || target.instance}
                  {target.distributor ? ` · ${target.distributor}` : ''}
                </span>
                <button type="button" class="client-kick-btn" onClick={() => void remove(target.endpoint)}>
                  {t('unifiedPush.remove')}
                </button>
              </li>
            )}
          </For>
        </ul>
        <Show when={message()}>
          <span class="clients-cap-row" role="status" aria-live="polite">
            {message()}
          </span>
        </Show>
      </div>
    </SettingsCard>
  )
}
