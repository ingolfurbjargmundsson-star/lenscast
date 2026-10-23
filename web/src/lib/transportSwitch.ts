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
    // Only a dashboard served over HTTP can be switched; a non-web href
    // (mailto:, a custom scheme) has no listener to navigate to.
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
    if (url.protocol === `${scheme}:`) return null
    // URL normalizes a scheme's default port away (`:80` on http, `:443` on
    // https), but the *other* scheme does not consider it default. Read the
    // port off the raw authority so a page served on :80 stays on :80 after
    // switching to https instead of silently moving to :443.
    const authority = href.slice(href.indexOf('://') + 3).split(/[/?#]/, 1)[0] ?? ''
    const explicitPort = /:(\d+)$/.exec(authority)?.[1]
    url.protocol = `${scheme}:`
    if (explicitPort) url.port = explicitPort
    return url.toString()
  } catch {
    return null
  }
}
