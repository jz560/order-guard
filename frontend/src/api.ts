export interface Identity { username: string; role: string; orderPrefix: string; allowedActions: string[] }
export interface Task { id: string; orderId: string; state: string; outcome: string | null; version: number; createdBy: string; createdAt: string; updatedAt: string; nextRunAt: string | null; allowedActions: string[] }
export interface TaskPage { items: Task[]; total: number; page: number; size: number }
export interface Evidence { id: string; source: string; businessVersion: number; payload: unknown; fetchedAt: string }
export interface Diagnosis { run: null | { rounds: number; tools: number; reviews: number; startedAt: string; deadlineAt: string; result: string | null }; events: { id: number; type: string; payload: string; createdAt: string }[] }
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public correlationId = '') { super(message) }
}
let csrf: { headerName: string; token: string } | null = null
export function clearSession() { csrf = null }
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(path, { ...init, credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
    if (!response.ok) {
      const body = await response.json().catch(() => ({}))
      throw new ApiError(response.status, body.code ?? 'HTTP_ERROR', body.message ?? '请求失败，请稍后重试', body.correlationId ?? response.headers.get('X-Correlation-ID') ?? '')
    }
    return response.status === 204 ? undefined as T : await response.json() as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(0, 'NETWORK_ERROR', '连接中断或超时。请检查服务后重试；创建请求可能已保存，重试会沿用原请求标识。')
  } finally { clearTimeout(timeout) }
}
export async function login(username: string, password: string) {
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  const authorization = `Basic ${btoa(String.fromCharCode(...bytes))}`
  const identity = await request<Identity>('/api/me', { headers: { Authorization: authorization } })
  csrf = null
  return identity
}
export async function write<T>(path: string, body?: unknown, key?: string) {
  if (!csrf) csrf = await request('/api/auth/csrf')
  const headers: Record<string, string> = { [csrf!.headerName]: csrf!.token }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (key) headers['Idempotency-Key'] = key
  try { return await request<T>(path, { method: 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body) }) }
  catch (error) { if (error instanceof ApiError && [401, 403].includes(error.status)) csrf = null; throw error }
}
export const states: Record<string, string> = { QUEUED: '排队中', DIAGNOSING: '诊断中', WAITING_APPROVAL: '待审批', APPROVED: '已批准', EXECUTING: '执行中', UNKNOWN: '结果待确认', VERIFYING: '验证中', MANUAL: '转人工', FINISHED: '已结束' }
export const recommendations: Record<string, string> = { NO_ACTION: '无需处理', WAIT: '等待复查', MANUAL: '转人工核实', RECOMMEND_RECHECK: '建议核验支付并修复' }
export function date(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Hong_Kong', hour12: false }) : '—' }
export function object(value: unknown): Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {} }
export function parse(value: string | null | undefined) { try { return object(JSON.parse(value ?? '{}')) } catch { return {} } }
export const stopReasons: Record<string, string> = {
  DIAGNOSIS_BUDGET: '诊断轮数或总时限已耗尽，请人工核实已有证据。',
  TOOL_BUDGET_OR_LOOP: '查询次数达到上限或发现重复查询，已停止诊断。',
  WAIT_REVIEW_LIMIT: '等待复查次数已耗尽，请人工确认支付和回调状态。',
  MODEL_TIMEOUT_USAGE_UNKNOWN: '模型调用超时，未形成可靠结论；本次用量未知。',
  MODEL_ERROR_USAGE_UNKNOWN: '模型调用失败，未形成可靠结论；本次用量未知。',
  INVALID_MODEL_RESPONSE: '模型返回内容无效，已停止诊断并转人工。',
  MODEL_OUTPUT_LIMIT: '模型输出超过限制，已停止诊断并转人工。',
  INVALID_DIAGNOSIS_OR_TOOL: '诊断结论、证据引用或查询请求未通过校验，已转人工。',
}
export function outcome(value: string | null) { return value ? recommendations[value] ?? stopReasons[value] ?? value : '尚无结果' }
