import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatQuestions from '../../components/chat/ChatQuestions.vue'
import type { PageChatQuestion } from '../../api/generated'

const question: PageChatQuestion = {
  id: 'batch',
  createdAt: '2026-01-01T00:00:00Z',
  questions: [
    {
      id: 'style',
      title: '页面风格？',
      options: [
        { id: 'calm', label: '简洁' },
        { id: 'bold', label: '鲜明' },
      ],
      allowCustomAnswer: true,
    },
    { id: 'audience', title: '目标读者？', options: [], allowCustomAnswer: true },
  ],
}

function card() {
  return mount(ChatQuestions, { props: { question, pending: true } })
}

afterEach(() => {
  vi.restoreAllMocks()
  delete (Element.prototype as Partial<Element>).scrollIntoView
})

describe('chat questions', () => {
  it('focuses the first question and scrolls it into view when pending', async () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    const wrapper = mount(ChatQuestions, {
      props: { question, pending: true },
      attachTo: document.body,
    })
    await nextTick()
    const first = wrapper.find('input[value="calm"]')
    expect(document.activeElement).toBe(first.element)
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start' })
    expect(scrollIntoView.mock.instances[0]).toBe(wrapper.find('fieldset').element)
    wrapper.unmount()
  })

  it('focuses the textarea when the first question has no options', async () => {
    const wrapper = mount(ChatQuestions, {
      props: {
        pending: true,
        question: {
          ...question,
          questions: [
            { id: 'audience', title: '目标读者？', options: [], allowCustomAnswer: true },
          ],
        },
      },
      attachTo: document.body,
    })
    await nextTick()
    expect(document.activeElement).toBe(wrapper.find('textarea').element)
    wrapper.unmount()
  })

  it('only focuses once the batch becomes pending', async () => {
    const wrapper = mount(ChatQuestions, {
      props: { question, pending: false },
      attachTo: document.body,
    })
    await nextTick()
    expect(document.activeElement).toBe(document.body)
    await wrapper.setProps({ pending: true })
    await nextTick()
    expect(document.activeElement).toBe(wrapper.find('input[value="calm"]').element)
    wrapper.unmount()
  })

  it('retries focusing a new batch that arrives while the previous one is submitting', async () => {
    const wrapper = mount(ChatQuestions, {
      props: { question, pending: true, submitting: true },
      attachTo: document.body,
    })
    await nextTick()
    expect(document.activeElement).toBe(document.body)
    await wrapper.setProps({ submitting: false })
    await nextTick()
    expect(document.activeElement).toBe(wrapper.find('input[value="calm"]').element)
    wrapper.unmount()
  })

  it('does not steal focus from an input the user is typing in, but still positions it', async () => {
    const scrollIntoView = vi.fn()
    Element.prototype.scrollIntoView = scrollIntoView
    const comment = document.createElement('textarea')
    comment.value = '补充说明'
    document.body.appendChild(comment)
    comment.focus()
    const wrapper = mount(ChatQuestions, {
      props: { question, pending: true },
      attachTo: document.body,
    })
    await nextTick()
    expect(document.activeElement).toBe(comment)
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'start' })
    wrapper.unmount()
    comment.remove()
  })

  it('focuses the question when the empty composer keeps focus after sending', async () => {
    const composer = document.createElement('textarea')
    document.body.appendChild(composer)
    composer.focus()
    const wrapper = mount(ChatQuestions, {
      props: { question, pending: true },
      attachTo: document.body,
    })
    await nextTick()
    expect(document.activeElement).toBe(wrapper.find('input[value="calm"]').element)
    wrapper.unmount()
    composer.remove()
  })

  it('does not focus a batch that is already answered', async () => {
    const wrapper = mount(ChatQuestions, {
      props: {
        pending: true,
        question: {
          ...question,
          answers: [
            { questionId: 'style', optionId: 'calm' },
            { questionId: 'audience', text: '设计师' },
          ],
        },
      },
      attachTo: document.body,
    })
    await nextTick()
    expect(document.activeElement).toBe(document.body)
    wrapper.unmount()
  })

  it('shows structured questions and requires every answer before submitting', async () => {
    const wrapper = card()
    expect(wrapper.findAll('legend').map((item) => item.text())).toEqual([
      '1. 页面风格？',
      '2. 目标读者？',
    ])
    await wrapper.find('form').trigger('submit')
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(2)
    expect(wrapper.emitted('answer')).toBeUndefined()
    await wrapper.find('input[value="calm"]').setValue(true)
    await wrapper.findAll('textarea')[1]!.setValue('   ')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('answer')).toBeUndefined()
    await wrapper.findAll('textarea')[0]!.setValue(' 留白多一些 ')
    await wrapper.findAll('textarea')[1]!.setValue(' 设计师 ')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('answer')).toEqual([
      [
        [
          { questionId: 'style', optionId: 'calm', text: '留白多一些' },
          { questionId: 'audience', text: '设计师' },
        ],
      ],
    ])
    wrapper.unmount()
  })

  it('supports custom answers, enforces the limit and disables duplicate submissions', async () => {
    const wrapper = card()
    await wrapper.findAll('textarea')[0]!.setValue('自定义风格')
    await wrapper.findAll('textarea')[1]!.setValue('x'.repeat(2001))
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('answer')).toBeUndefined()
    await wrapper.findAll('textarea')[1]!.setValue('读者')
    await wrapper.setProps({ submitting: true })
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('正在提交')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('answer')).toBeUndefined()
    await wrapper.setProps({ submitting: false, error: '连接失败，请重试' })
    expect(wrapper.find('[role="alert"]').text()).toContain('连接失败')
    expect(wrapper.findAll('textarea')[0]!.element.value).toBe('自定义风格')
    await wrapper.find('form').trigger('submit')
    expect(wrapper.emitted('answer')?.[0]?.[0]).toEqual([
      { questionId: 'style', text: '自定义风格' },
      { questionId: 'audience', text: '读者' },
    ])
    wrapper.unmount()
  })

  it('shows answered history without editable inputs and resets for a new question batch', async () => {
    const wrapper = card()
    await wrapper.findAll('textarea')[0]!.setValue('draft')
    await wrapper.setProps({ question: { ...question, id: 'next' } })
    expect(wrapper.findAll('textarea')[0]!.element.value).toBe('')
    await wrapper.setProps({
      pending: false,
      question: {
        ...question,
        answers: [
          { questionId: 'style', optionId: 'bold', text: '暖色' },
          { questionId: 'audience', text: '设计师' },
        ],
      },
    })
    expect(wrapper.text()).toContain('已回答')
    expect(wrapper.text()).toContain('鲜明 · 暖色')
    expect(wrapper.text()).toContain('设计师')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })
})
