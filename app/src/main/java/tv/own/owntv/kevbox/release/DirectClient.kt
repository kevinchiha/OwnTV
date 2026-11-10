// KevBox Live fork only: this file does not exist upstream (ahXN00/OwnTV).
package tv.own.owntv.kevbox.release

import java.net.Proxy
import okhttp3.Authenticator
import okhttp3.OkHttpClient

/**
 * [shared] with no proxy at all: same DNS, trust and timeouts, Core's proxy setting ignored, retries on
 * connection failure on.
 *
 * The silent self-update must reach GitHub even when the proxy Core uses (the local relay, or the
 * customer's proxy) is down or fails closed: otherwise the update that fixes a broken proxy could never
 * arrive. Core also turns retryOnConnectionFailure off on its shared client, which breaks calls on TV
 * boxes that try IPv6 first over a dead route; SupabaseApi turns it back on for the same reason.
 * Interceptors, DNS, trust roots and timeouts stay the shared client's.
 */
fun directClient(shared: OkHttpClient): OkHttpClient = shared.newBuilder()
    .proxy(Proxy.NO_PROXY)
    .proxyAuthenticator(Authenticator.NONE)
    .retryOnConnectionFailure(true)
    .build()
