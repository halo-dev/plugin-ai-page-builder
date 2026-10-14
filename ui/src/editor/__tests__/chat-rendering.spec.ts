import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import {
  applyUIMessageChunk,
  createUIMessageReducer,
  type UIMessageChunk,
} from '@halo-dev/ai-foundation-sdk'
import ChatMarkdown from '../../components/chat/ChatMarkdown.vue'
import ChatMessageParts from '../../components/chat/ChatMessageParts.vue'
vi.mock('@halo-dev/components', () => ({
  VStatusDot: { props: ['animate'], template: '<span :data-animate="animate" />' },
}))
describe('streaming conversation rendering', () => {
  it('shows a static ask-user waiting state without thinking or tool animations', () => {
    const wrapper = mount(ChatMessageParts, {
      props: {
        active: false,
        waiting: true,
        message: {
          id: 'assistant',
          role: 'assistant',
          parts: [
            { type: 'reasoning', id: 'thinking', text: 'Need a preference' },
            {
              type: 'tool-ask_user',
              toolCallId: 'ask',
              toolName: 'ask_user',
              state: 'input-available',
              input: {},
            },
          ],
        },
      },
    })
    expect(wrapper.text()).toContain('等待回答')
    expect(wrapper.text()).not.toContain('正在思考')
    expect(wrapper.text()).not.toContain('已结束')
    expect(wrapper.find('.animate-spin').exists()).toBe(false)
    wrapper.unmount()
  })
  it('escapes HTML, blocks dangerous links and renders unfinished Markdown and code safely', async () => {
    const wrapper = mount(ChatMarkdown, {
      props: {
        final: false,
        content:
          '**Hello 你好\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1))\n\n```html\n<img src=x onerror=alert(1)>',
      },
    })
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('[onerror]').exists()).toBe(false)
    expect(wrapper.find('a[href^="javascript:"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Hello 你好')
    await wrapper.setProps({ final: true })
    expect(wrapper.find('pre').exists()).toBe(true)
    wrapper.unmount()
  })
  it('preserves SDK text/reasoning/tool order and ends animation on cancellation', () => {
    let state = createUIMessageReducer()
    const chunks: UIMessageChunk[] = [
      { type: 'text-start', id: 'text1' },
      { type: 'text-delta', id: 'text1', delta: 'Before' },
      { type: 'reasoning-start', id: 'think' },
      { type: 'reasoning-delta', id: 'think', delta: 'Reason supplied by model' },
      { type: 'tool-input-available', toolCallId: 'a', toolName: 'patch_current_page', input: {} },
      {
        type: 'tool-output-available',
        toolCallId: 'a',
        toolName: 'patch_current_page',
        output: { valid: true, staged: true },
      },
      { type: 'text-start', id: 'text2' },
      { type: 'text-delta', id: 'text2', delta: 'Between' },
      { type: 'tool-input-start', toolCallId: 'b', toolName: 'patch_current_page' },
      { type: 'abort' },
    ]
    for (const chunk of chunks) state = applyUIMessageChunk(state, chunk)
    const wrapper = mount(ChatMessageParts, { props: { message: state.message, active: false } })
    const text = wrapper.text()
    expect(text.indexOf('Before')).toBeLessThan(text.indexOf('思考过程'))
    expect(text.indexOf('思考过程')).toBeLessThan(text.indexOf('已暂存'))
    expect(text.indexOf('已暂存')).toBeLessThan(text.indexOf('Between'))
    expect(text).toContain('已结束')
    expect(wrapper.find('[data-animate="true"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
