import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
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

describe('chat questions', () => {
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
