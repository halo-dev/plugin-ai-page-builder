import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ChatMessageParts from '../../components/chat/ChatMessageParts.vue'

vi.mock('../../api/clients', () => ({}))
vi.mock('../../api/stream', () => ({}))
vi.mock('@halo-dev/components', () => ({
  VStatusDot: { props: ['animate'], template: '<span :data-animate="animate" />' },
}))
// Simulate a stale chunk hash after a deploy: the renderer chunk cannot be fetched.
vi.mock('../../components/chat/ChatMarkdown.vue', () => {
  throw new Error('Failed to fetch dynamically imported module')
})

describe('chat markdown fallback', () => {
  it('renders message text when the markdown chunk fails to load', async () => {
    const wrapper = mount(ChatMessageParts, {
      props: {
        active: false,
        message: {
          id: 'm',
          role: 'assistant',
          parts: [{ type: 'text', id: 't', text: '**hello** world' }],
        },
      },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('**hello** world')
    wrapper.unmount()
  })
})
