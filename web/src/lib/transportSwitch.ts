/**
 * Resolve the URL an open dashboard should navigate to when the server
 * announces an HTTP/HTTPS scheme switch. Everything but the protocol is
 * preserved — host, port, path, query and hash — so the reload stays on the
 * same dashboard entry point, including a reverse-proxy path.
 *
 * Null means "stay put": the announced scheme is malformed or the page is
 * already on it (a toggle whose event raced a page that had just reloaded).
 */
export function transportSwitchUrl(href: string, scheme: unknown): string | null {
  if (scheme !== 'http' && scheme !== 'https') return null
  try {
    const url = new URL(href)
    if (url.protocol === `${scheme}:`) return null
    url.protocol = `${scheme}:`
    return url.toString()
  } catch {
    return null
  }
}
