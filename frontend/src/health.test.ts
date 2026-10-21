import { afterEach, describe, expect, it, vi } from 'vitest'
import { checkHealth } from './health'

afterEach(() => vi.unstubAllGlobals())
describe('环境状态', () => {
  it('仅当健康接口明确返回 UP 时显示可用', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ status: 'UP' }))))
    expect(await checkHealth('backend')).toBe('up')
  })
  it.each([
    new Response(JSON.stringify({ status: 'DOWN' }), { status: 503 }),
    new Response(JSON.stringify({})),
    new Response('invalid json'),
  ])('拒绝把失败或无效响应显示为正常', async (response) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    expect(await checkHealth('simulator')).toBe('down')
  })
  it('连接失败时显示不可用', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('network error')))
    expect(await checkHealth('backend')).toBe('down')
  })
})
