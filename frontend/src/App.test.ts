import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import App from './App.vue'

afterEach(() => vi.unstubAllGlobals())
it('展示实际服务状态，并允许刷新失败的服务', async () => {
  let release!: () => void
  const pending = new Promise<void>((resolve) => { release = resolve })
  const fetchMock = vi.fn().mockImplementation(async () => {
    await pending
    return new Response(JSON.stringify({ status: 'UP' }))
  })
  vi.stubGlobal('fetch', fetchMock)
  const wrapper = mount(App)
  await nextTick()
  expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  release()
  await flushPromises()
  expect(wrapper.findAll('.up')).toHaveLength(2)
  fetchMock.mockRejectedValue(new Error('offline'))
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.findAll('.down')).toHaveLength(2)
  expect(wrapper.text()).toContain('尚未开放订单诊断、审批与修复功能')
  wrapper.unmount()
})
