import { axiosInstance } from '@halo-dev/api-client'
import type { FetchFunction } from '@halo-dev/ai-foundation-sdk'

// Keep the Console's authentication, XSRF handling and interceptors for SDK requests.
export const authenticatedChatFetch: FetchFunction = async (input, init) => {
  const headers = Object.fromEntries(new Headers(init?.headers).entries())
  const response = await axiosInstance.request<ReadableStream<Uint8Array>>({
    url: String(input),
    method: init?.method || 'POST',
    data: init?.body,
    headers,
    signal: init?.signal || undefined,
    adapter: 'fetch',
    responseType: 'stream',
    validateStatus: () => true,
  })
  const responseHeaders = new Headers()
  for (const [name, value] of Object.entries(response.headers)) {
    if (value != null) responseHeaders.set(name, String(value))
  }
  return new Response(response.data, {
    status: response.status,
    statusText: response.statusText,
    headers: responseHeaders,
  })
}
