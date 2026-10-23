import { describe, expect, it } from 'vitest'
import { transportSwitchUrl } from './transportSwitch'

describe('transportSwitchUrl', () => {
  it('follows an http → https switch and preserves host, port, path and hash', () => {
    expect(
      transportSwitchUrl('http://192.168.1.19:8080/#/settings?tab=app', 'https'),
    ).toBe('https://192.168.1.19:8080/#/settings?tab=app')
  })

  it('follows an https → http switch', () => {
    expect(transportSwitchUrl('https://lenscast.local:8080/dashboard', 'http')).toBe(
      'http://lenscast.local:8080/dashboard',
    )
  })

  it('stays put when the page already speaks the announced scheme', () => {
    expect(transportSwitchUrl('https://192.168.1.19:8080/', 'https')).toBeNull()
  })

  it('rejects malformed schemes and hrefs', () => {
    expect(transportSwitchUrl('https://192.168.1.19:8080/', 'ws')).toBeNull()
    expect(transportSwitchUrl('https://192.168.1.19:8080/', undefined)).toBeNull()
    expect(transportSwitchUrl('not a url', 'https')).toBeNull()
  })
})
