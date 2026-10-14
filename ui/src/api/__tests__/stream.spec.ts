import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authenticatedChatFetch } from '../stream'
import { DefaultChatTransport } from '@halo-dev/ai-foundation-sdk'
const request = vi.hoisted(() => vi.fn<(...args: unknown[]) => unknown>())
vi.mock('@halo-dev/api-client', () => ({ axiosInstance: { request } }))
beforeEach(() => request.mockReset())
describe('authenticated SDK transport', () => {
  it('keeps host axios authentication and passes streaming headers and cancellation', async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(c) {
        c.enqueue(new TextEncoder().encode('data: [DONE]\n\n'))
        c.close()
      },
    })
    request.mockResolvedValue({
      data: stream,
      status: 200,
      statusText: 'OK',
      headers: { 'content-type': 'text/event-stream', 'x-halo-ai-ui-message-stream': 'v1' },
    })
    const controller = new AbortController()
    const result = await authenticatedChatFetch('/chat', {
      method: 'POST',
      body: '{"prompt":"你好"}',
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
    })
    expect(request).toHaveBeenCalledWith(
      expect.objectContaining({
        url: '/chat',
        adapter: 'fetch',
        responseType: 'stream',
        signal: controller.signal,
        data: '{"prompt":"你好"}',
      }),
    )
    expect(result.headers.get('x-halo-ai-ui-message-stream')).toBe('v1')
    expect(await result.text()).toContain('[DONE]')
  })
  it('passes standard server tools and committed data through the real SDK transport', async () => {
    const chunks = [
      { type: 'start', messageId: 'reply' },
      { type: 'text-start', id: 'text' },
      { type: 'text-delta', id: 'text', delta: '你好' },
      { type: 'text-end', id: 'text' },
      {
        type: 'tool-input-available',
        toolCallId: 'call',
        toolName: 'update_current_page',
        input: {},
      },
      { type: 'tool-output-available', toolCallId: 'call', output: { valid: true } },
      { type: 'data-page-committed', data: { page: { draftRevision: 'r2' } } },
      { type: 'finish', finishReason: 'stop' },
    ]
    const bytes = new TextEncoder().encode(
      chunks.map((chunk) => `data: ${JSON.stringify(chunk)}\n\n`).join('') + 'data: [DONE]\n\n',
    )
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (let i = 0; i < bytes.length; i += 3) controller.enqueue(bytes.slice(i, i + 3))
        controller.close()
      },
    })
    request.mockResolvedValue({
      data: stream,
      status: 200,
      statusText: 'OK',
      headers: { 'content-type': 'text/event-stream', 'x-halo-ai-ui-message-stream': 'v1' },
    })
    const transport = new DefaultChatTransport({ api: '/chat', fetch: authenticatedChatFetch })
    const received = []
    for await (const chunk of await transport.sendMessages({
      chatId: 'page',
      messages: [],
      trigger: 'submit-message',
    }))
      received.push(chunk)
    expect(received).toEqual(chunks)
  })
  it('preserves HTTP conflicts for SDK error handling', async () => {
    request.mockResolvedValue({
      data: 'Version conflict',
      status: 409,
      statusText: 'Conflict',
      headers: {},
    })
    const response = await authenticatedChatFetch('/chat')
    expect(response.status).toBe(409)
    expect(await response.text()).toBe('Version conflict')
  })
})
