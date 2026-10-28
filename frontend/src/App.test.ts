import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import App from './App.vue'
import { clearSession } from './api'

const id = '11111111-1111-1111-1111-111111111111'
const factId = '22222222-2222-2222-2222-222222222222'
const task = { id, orderId: 'T05-ORDER-001', state: 'WAITING_APPROVAL', outcome: 'RECOMMEND_RECHECK', version: 2, createdBy: 'operator', createdAt: '2026-10-28T00:00:00Z', updatedAt: '2026-10-28T00:01:00Z', nextRunAt: null, allowedActions: ['VIEW_EVIDENCE', 'VIEW_DIAGNOSIS'] }
const operator = { username: 'operator', role: 'OPERATOR', orderPrefix: 'T05-ORDER-', allowedActions: ['CREATE_TASK', 'VIEW_TASKS'] }
const facts = [{ id: factId, source: 'DIAG_query_payment', businessVersion: 1, fetchedAt: task.createdAt, payload: { review: 0, data: { available: false } } }]
const log = { run: { rounds: 2, tools: 4, reviews: 0, startedAt: task.createdAt, deadlineAt: task.updatedAt, result: JSON.stringify({ recommendation: 'MANUAL', reason: '支付证据缺失', evidenceIds: [factId] }) }, events: [{ id: 1, type: 'MODEL_RESPONSE', createdAt: task.createdAt, payload: JSON.stringify({ model: 'stub', text: '<script>private output</script>' }) }] }
let wrapper: VueWrapper
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const fetchMock = vi.fn<typeof fetch>()
function defaults(url: string | URL | Request) {
  const path = String(url)
  if (path === '/api/me') return json(operator)
  if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' })
  if (path.endsWith('/evidence')) return json(facts)
  if (path.endsWith('/diagnosis')) return json(log)
  if (path === `/api/tasks/${id}`) return json(task)
  if (path.startsWith('/api/tasks?')) return json({ items: [], total: 0, page: 0, size: 20 })
  throw new Error(`Unexpected request: ${path}`)
}
function button(label: string) { const found = wrapper.findAll('button').find(item => item.text() === label); if (!found) throw new Error(`Missing button ${label}`); return found }
async function render(hash = '') { history.replaceState(null, '', `/${hash}`); wrapper = mount(App, { attachTo: document.body }); await flushPromises() }
async function route(hash: string) { history.replaceState(null, '', `/${hash}`); window.dispatchEvent(new Event('hashchange')); await flushPromises() }
beforeEach(() => {
  clearSession(); fetchMock.mockReset(); fetchMock.mockImplementation(async url => defaults(url)); vi.stubGlobal('fetch', fetchMock)
  HTMLDialogElement.prototype.showModal = function () { this.open = true }
  HTMLDialogElement.prototype.close = function () { this.open = false }
})
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals(); history.replaceState(null, '', '/') })
it('登录仅一次发送凭证，刷新会话读取列表，退出携带 CSRF', async () => {
  fetchMock.mockImplementation(async (url, init) => {
    if (String(url) === '/api/me' && !(init?.headers as Record<string, string>)?.Authorization) return json({}, 401)
    if (String(url) === '/api/auth/logout') return new Response(null, { status: 204 })
    return defaults(url)
  })
  await render()
  await wrapper.get('#password').setValue('test-password-123')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.text()).toContain('暂无符合条件的任务')
  expect(fetchMock.mock.calls.filter(([, init]) => (init?.headers as Record<string, string>)?.Authorization)).toHaveLength(1)
  expect(wrapper.find('#password').exists()).toBe(false)
  await button('退出登录').trigger('click'); await flushPromises()
  const logout = fetchMock.mock.calls.find(([url]) => url === '/api/auth/logout')!
  expect(logout[1]?.headers).toEqual({ 'X-CSRF-TOKEN': 'test-csrf' })
  expect(wrapper.text()).toContain('登录工作台')
})
it('筛选发送完整条件，分页及重置可用，审批员无创建入口', async () => {
  fetchMock.mockImplementation(async url => {
    if (String(url) === '/api/me') return json({ ...operator, role: 'APPROVER', allowedActions: ['VIEW_TASKS'] })
    if (String(url).startsWith('/api/tasks?')) return json({ items: [task], total: 21, page: Number(new URL(String(url), 'http://localhost').searchParams.get('page')), size: 20 })
    return defaults(url)
  })
  await render(); expect(wrapper.text()).not.toContain('新建诊断')
  await wrapper.get('input').setValue(task.orderId); await wrapper.get('select').setValue('MANUAL'); await wrapper.get('form').trigger('submit'); await flushPromises()
  expect(String(fetchMock.mock.lastCall?.[0])).toContain('orderId=T05-ORDER-001&state=MANUAL')
  await button('下一页').trigger('click'); await flushPromises(); expect(String(fetchMock.mock.lastCall?.[0])).toContain('page=1')
  await button('重置筛选').trigger('click'); await flushPromises(); expect(String(fetchMock.mock.lastCall?.[0])).toBe('/api/tasks?page=0&size=20')
})
it('创建期间禁止重复提交，网络失败沿用幂等键，复用后进入原详情', async () => {
  let release!: () => void
  const pending = new Promise<void>(resolve => { release = resolve })
  let attempts = 0
  fetchMock.mockImplementation(async (url, init) => {
    if (url === '/api/tasks' && init?.method === 'POST') { attempts++; if (attempts === 1) { await pending; throw new Error('offline') }; return json({ task, reused: true }) }
    return defaults(url)
  })
  await render(); await button('新建诊断').trigger('click'); await wrapper.get('#new-order').setValue(task.orderId)
  await wrapper.get('dialog form').trigger('submit'); await flushPromises()
  expect(button('正在创建…').attributes('disabled')).toBeDefined()
  await wrapper.get('dialog form').trigger('submit'); expect(attempts).toBe(1)
  release(); await flushPromises(); expect(wrapper.text()).toContain('创建请求可能已保存')
  await wrapper.get('dialog form').trigger('submit'); await flushPromises(); await route(location.hash)
  const posts = fetchMock.mock.calls.filter(([url]) => url === '/api/tasks')
  expect((posts[0]![1]?.headers as Record<string, string>)['Idempotency-Key']).toBe((posts[1]![1]?.headers as Record<string, string>)['Idempotency-Key'])
  expect(location.hash).toBe(`#/tasks/${id}`); expect(wrapper.text()).toContain('已打开原任务'); expect(wrapper.text()).toContain('支付证据缺失')
})
it('详情刷新为只读，引用展开定位证据，失败保留明确过期快照，不渲染模型原文', async () => {
  await render(`#/tasks/${id}`)
  expect(wrapper.text()).toContain('查询不可用：证据缺失')
  expect(wrapper.text()).not.toContain('private output')
  await button('E01 查看证据').trigger('click')
  expect((wrapper.get('details').element as HTMLDetailsElement).open).toBe(true)
  expect(document.activeElement?.id).toBe(`evidence-${factId}`)
  expect(wrapper.text()).toContain('2026/10/28 08:00:00')
  fetchMock.mockRejectedValue(new Error('offline')); await button('刷新状态').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('可能已过期'); expect(wrapper.text()).toContain(task.orderId)
  expect(fetchMock.mock.calls.every(([, init]) => !init?.method)).toBe(true)
})
it('无权限详情不获取证据，会话失效后清除业务内容', async () => {
  fetchMock.mockImplementation(async url => url === `/api/tasks/${id}` ? json({}, 404) : defaults(url))
  await render(`#/tasks/${id}`); expect(wrapper.text()).toContain('不在当前账号权限范围内')
  expect(fetchMock.mock.calls.some(([url]) => String(url).endsWith('/evidence'))).toBe(false)
  fetchMock.mockImplementation(async url => defaults(url)); await button('刷新状态').trigger('click'); await flushPromises()
  fetchMock.mockImplementation(async () => json({}, 401)); await button('刷新状态').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('登录已过期'); expect(wrapper.text()).not.toContain(task.orderId)
})
it('页面切换后迟到的详情响应不会覆盖新页面', async () => {
  let release!: (value: Response) => void
  fetchMock.mockImplementation(async url => url === `/api/tasks/${id}` ? new Promise<Response>(resolve => { release = resolve }) : defaults(url))
  await render(`#/tasks/${id}`); await route('#/tasks'); release(json(task)); await flushPromises()
  expect(wrapper.text()).toContain('暂无符合条件的任务'); expect(wrapper.text()).not.toContain('支付证据缺失')
})
