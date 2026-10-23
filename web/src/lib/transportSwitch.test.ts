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

  it('keeps an explicit default port across the switch', () => {
    // `new URL` drops :80 on http; the https target must not silently move to
    // :443, where the LensCast listener is not bound.
    expect(transportSwitchUrl('http://192.168.1.19:80/#/live', 'https')).toBe(
      'https://192.168.1.19:80/#/live',
    )
    expect(transportSwitchUrl('https://192.168.1.19:443/#/live', 'http')).toBe(
      'http://192.168.1.19:443/#/live',
    )
  })

  it('rejects malformed schemes and hrefs', () => {
    expect(transportSwitchUrl('https://192.168.1.19:8080/', 'ws')).toBeNull()
    expect(transportSwitchUrl('https://192.168.1.19:8080/', undefined)).toBeNull()
    expect(transportSwitchUrl('not a url', 'https')).toBeNull()
    expect(transportSwitchUrl('ftp://192.168.1.19:8080/live', 'https')).toBeNull()
  })
})
