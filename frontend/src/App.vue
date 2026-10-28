<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import EnvironmentStatus from './EnvironmentStatus.vue'
import { ApiError, clearSession, date, login, object, outcome, parse, recommendations, request, states, write, type Diagnosis, type Evidence, type Identity, type Task, type TaskPage } from './api'

const identity = ref<Identity | null>(null)
const booting = ref(true), busy = ref(false), loading = ref(false), creating = ref(false)
const username = ref('operator'), password = ref(''), error = ref(''), notice = ref('')
const order = ref(''), state = ref(''), page = ref(0)
const list = ref<TaskPage | null>(null), task = ref<Task | null>(null)
const evidence = ref<Evidence[]>([]), diagnosis = ref<Diagnosis | null>(null)
const selected = ref(''), refreshedAt = ref<string | null>(null)
const modal = ref<HTMLDialogElement | null>(null), newOrder = ref(''), createError = ref('')
let pending: { order: string; key: string } | null = null
let generation = 0
const route = ref(location.hash)
const detailId = computed(() => /^#\/tasks\/([0-9a-f-]{36})$/i.exec(route.value)?.[1] ?? '')
const invalidRoute = computed(() => route.value !== '' && route.value !== '#/tasks' && !detailId.value)
const result = computed(() => parse(diagnosis.value?.run?.result))
const refs = computed(() => Array.isArray(result.value.evidenceIds) ? result.value.evidenceIds.filter((id): id is string => typeof id === 'string') : [])
const canCreate = computed(() => identity.value?.allowedActions.includes('CREATE_TASK'))
const sourceLabels: Record<string, string> = { SIMULATOR_ORDER: '创建时订单快照', DIAG_query_order: '订单事实', DIAG_query_payment: '支付事实', DIAG_query_callbacks: '回调记录', DIAG_query_history: '本系统执行历史' }
const eventLabels: Record<string, string> = { MODEL_INTENT: '开始诊断轮次', MODEL_RESPONSE: '已保存模型公开输出', TOOL_INTENT: '开始只读查询', TOOL_RESULT: '已保存查询证据', CONCLUSION: '已校验诊断结论', STOPPED: '诊断停止' }
function failure(value: unknown) {
  if (value instanceof ApiError) {
    if (value.status === 401) {
      generation++; pending = null; notice.value = ''; identity.value = null; task.value = null; list.value = null; evidence.value = []; diagnosis.value = null; clearSession(); modal.value?.close()
      return '登录已过期或账号密码不正确，请重新登录。'
    }
    const message = value.status === 404 ? '任务不存在或不在当前账号权限范围内。' : value.status === 403 ? '没有访问权限或登录凭据已更新，请刷新后重试。' : value.message
    return `${message}${value.correlationId ? `（关联 ID：${value.correlationId}）` : ''}`
  }
  return '请求失败，请重试。'
}
async function load() {
  const turn = ++generation
  loading.value = true; error.value = ''
  try {
    if (invalidRoute.value) throw new ApiError(404, 'INVALID_ROUTE', '页面不存在')
    if (detailId.value) {
      const path = `/api/tasks/${detailId.value}`
      const current = await request<Task>(path)
      if (turn !== generation) return
      const [facts, log] = await Promise.all([
        current.allowedActions.includes('VIEW_EVIDENCE') ? request<Evidence[]>(`${path}/evidence`) : Promise.resolve([]),
        current.allowedActions.includes('VIEW_DIAGNOSIS') ? request<Diagnosis>(`${path}/diagnosis`) : Promise.resolve(null),
      ])
      if (turn !== generation) return
      task.value = current; evidence.value = facts; diagnosis.value = log
    } else {
      const query = new URLSearchParams({ page: String(page.value), size: '20' })
      if (order.value.trim()) query.set('orderId', order.value.trim())
      if (state.value) query.set('state', state.value)
      const data = await request<TaskPage>(`/api/tasks?${query}`)
      if (turn !== generation) return
      list.value = data
    }
    refreshedAt.value = new Date().toISOString()
  } catch (value) { if (turn === generation) error.value = failure(value) }
  finally { if (turn === generation || !identity.value) loading.value = false }
}
function navigate(id?: string) { location.hash = id ? `/tasks/${id}` : '/tasks' }
function changeRoute() {
  route.value = location.hash; task.value = null; evidence.value = []; diagnosis.value = null; refreshedAt.value = null; selected.value = ''
  if (identity.value) void load()
}
async function signIn() {
  if (busy.value) return
  busy.value = true; error.value = ''; notice.value = ''
  try { identity.value = await login(username.value, password.value); password.value = ''; await load() }
  catch (value) { error.value = failure(value) }
  finally { password.value = ''; busy.value = false }
}
async function signOut() {
  if (busy.value || creating.value) return
  busy.value = true
  try {
    await write('/api/auth/logout'); generation++; clearSession(); identity.value = null; task.value = null; list.value = null; evidence.value = []; diagnosis.value = null; pending = null; error.value = ''; notice.value = ''; modal.value?.close()
  } catch (value) { error.value = failure(value) }
  finally { busy.value = false; loading.value = false }
}
function openCreate() { createError.value = ''; newOrder.value = pending?.order ?? ''; modal.value?.showModal(); void nextTick(() => document.getElementById('new-order')?.focus()) }
async function create() {
  if (creating.value) return
  const value = newOrder.value.trim()
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(value)) { createError.value = '请输入 1—100 位字母、数字、下划线或连字符组成的订单号。'; return }
  if (!pending || pending.order !== value) pending = { order: value, key: crypto.randomUUID() }
  creating.value = true; createError.value = ''
  try {
    const created = await write<{ task: Task; reused: boolean }>('/api/tasks', { orderId: value }, pending.key)
    pending = null; modal.value?.close(); notice.value = created.reused ? '该订单已有任务或本次请求已处理，已打开原任务。' : '任务已创建，后台将独立完成诊断。'
    if (detailId.value === created.task.id) await load(); else navigate(created.task.id)
  } catch (value) { createError.value = failure(value); if (!identity.value) error.value = createError.value }
  finally { creating.value = false }
}
async function locate(id: string) {
  selected.value = id
  await nextTick()
  const element = document.getElementById(`evidence-${id}`) as HTMLDetailsElement | null
  if (element) { element.open = true; element.scrollIntoView?.({ behavior: 'smooth', block: 'center' }); element.focus() }
}
function summary(item: Evidence) {
  const payload = object(item.payload), data = object(payload.data ?? payload)
  if (data.available === false) return '查询不可用：证据缺失，不能视为没有记录'
  if (Array.isArray(payload.data)) return `${payload.data.length} 条记录`
  return ['orderId', 'id', 'status', 'amountMinor', 'currency', 'paymentId'].filter(key => data[key] !== undefined).map(key => `${key}: ${data[key]}`).join(' · ') || '展开查看保存的业务字段'
}
onMounted(async () => {
  window.addEventListener('hashchange', changeRoute)
  try { identity.value = await request<Identity>('/api/me'); await load() }
  catch (value) { if (!(value instanceof ApiError && value.status === 401)) error.value = failure(value) }
  finally { booting.value = false }
})
onUnmounted(() => { generation++; window.removeEventListener('hashchange', changeRoute) })
</script>

<template>
  <main>
    <header class="topbar">
      <div>
        <div class="eyebrow">
          ORDER OPERATIONS / 模拟业务工作台
        </div><h1>订单异常诊断</h1><p>以证据诊断异常，通过审批控制处置。</p>
      </div>
      <div
        v-if="identity"
        class="account"
      >
        <strong>{{ identity.username }}</strong><span>{{ identity.role === 'OPERATOR' ? '操作员' : '审批员' }}</span><button
          :disabled="busy || creating"
          class="secondary"
          @click="signOut"
        >
          退出登录
        </button>
      </div>
    </header>
    <p class="notice">
      所有订单与资金数据均为模拟。诊断可能使用测试替身，实际模型以诊断记录为准；建议修复仍须人工审批。
    </p>
    <p
      v-if="error"
      role="alert"
      class="error"
    >
      {{ error }} <button
        v-if="identity"
        :disabled="loading"
        @click="load"
      >
        重试读取
      </button>
    </p>
    <p
      v-if="notice"
      role="status"
      class="success"
    >
      {{ notice }}
    </p>
    <p
      v-if="booting"
      role="status"
    >
      正在恢复登录状态…
    </p>
    <section
      v-else-if="!identity"
      class="login panel"
    >
      <h2>登录工作台</h2><p>使用本地配置的 operator 或 approver 账号。密码仅用于本次认证。</p>
      <form @submit.prevent="signIn">
        <label for="username">账号</label><input
          id="username"
          v-model="username"
          autocomplete="username"
          required
          :disabled="busy"
        ><label for="password">密码</label><input
          id="password"
          v-model="password"
          type="password"
          autocomplete="current-password"
          required
          :disabled="busy"
        ><button :disabled="busy">
          {{ busy ? '正在登录…' : '登录' }}
        </button>
      </form>
    </section>
    <template v-else>
      <div class="toolbar">
        <button
          v-if="detailId || invalidRoute"
          class="secondary"
          @click="navigate()"
        >
          返回任务列表
        </button><button
          v-if="canCreate && !detailId && !invalidRoute"
          @click="openCreate"
        >
          新建诊断
        </button><button
          :disabled="loading"
          class="secondary"
          @click="load"
        >
          {{ loading ? '正在读取…' : '刷新状态' }}
        </button><small>数据读取：{{ date(refreshedAt) }}（香港时间）</small>
      </div>
      <p
        v-if="loading"
        role="status"
      >
        正在读取服务端状态…
      </p>
      <p
        v-if="error && refreshedAt"
        class="warning"
      >
        当前显示上次读取的快照，可能已过期。请重试读取。
      </p>
      <section
        v-if="!detailId && !invalidRoute"
        class="panel"
      >
        <h2>诊断任务</h2><p>订单范围：{{ identity.orderPrefix }}* · 时间均为 Asia/Hong_Kong</p>
        <form
          class="filters"
          @submit.prevent="page = 0; load()"
        >
          <label>订单号<input
            v-model="order"
            placeholder="完整订单号"
            maxlength="100"
            pattern="[A-Za-z0-9_-]{1,100}"
          ></label><label>任务状态<select v-model="state"><option value="">全部状态</option><option
            v-for="(label, code) in states"
            :key="code"
            :value="code"
          >{{ label }}</option></select></label><button :disabled="loading">
            查询
          </button><button
            type="button"
            class="secondary"
            :disabled="loading"
            @click="order = ''; state = ''; page = 0; load()"
          >
            重置筛选
          </button>
        </form>
        <div
          v-if="list?.items.length"
          class="table-wrap"
        >
          <table>
            <thead><tr><th>任务 / 订单</th><th>状态</th><th>结果</th><th>创建时间</th><th>操作</th></tr></thead><tbody>
              <tr
                v-for="item in list.items"
                :key="item.id"
              >
                <td><strong>{{ item.orderId }}</strong><small class="identifier">{{ item.id }}</small></td><td><span class="badge">{{ states[item.state] ?? item.state }}</span></td><td>{{ outcome(item.outcome) }}</td><td>{{ date(item.createdAt) }}</td><td><a :href="`#/tasks/${item.id}`">查看详情</a></td>
              </tr>
            </tbody>
          </table>
        </div>
        <p
          v-else-if="list && !loading && !error"
          class="empty"
        >
          暂无符合条件的任务。
        </p>
        <nav
          v-if="list"
          aria-label="任务分页"
          class="toolbar"
        >
          <button
            class="secondary"
            :disabled="loading || page === 0"
            @click="page--; load()"
          >
            上一页
          </button><span>第 {{ list.page + 1 }} 页 · 共 {{ list.total }} 条</span><button
            class="secondary"
            :disabled="loading || (page + 1) * list.size >= list.total"
            @click="page++; load()"
          >
            下一页
          </button>
        </nav>
      </section>
      <template v-if="task && detailId">
        <section class="panel task-header">
          <span class="badge">{{ states[task.state] ?? task.state }}</span><h2>{{ task.orderId }}</h2><p class="identifier">
            任务 {{ task.id }}
          </p><p>创建人 {{ task.createdBy }} · 更新于 {{ date(task.updatedAt) }} · 版本 {{ task.version }}</p><p v-if="task.nextRunAt">
            下次调度 / 复查：{{ date(task.nextRunAt) }}
          </p><p
            v-if="task.state === 'UNKNOWN'"
            class="warning"
          >
            结果尚未确认，请先核实，禁止重复修复。刷新只读取状态。
          </p>
        </section>
        <div class="detail-grid">
          <section class="panel">
            <h2>证据快照 <span class="muted">{{ evidence.length }}</span></h2><p>保存的查询事实，不代表执行时的最新状态。金额单位为分。</p><p
              v-if="!evidence.length"
              class="empty"
            >
              尚无可查看的证据。
            </p><details
              v-for="(item, index) in evidence"
              :id="`evidence-${item.id}`"
              :key="item.id"
              :class="{ selected: selected === item.id }"
              tabindex="-1"
            >
              <summary><strong>E{{ String(index + 1).padStart(2, '0') }} · {{ sourceLabels[item.source] ?? item.source }}</strong><small>{{ date(item.fetchedAt) }}</small><span>{{ summary(item) }}</span></summary><p class="identifier">
                证据 ID：{{ item.id }} · 业务版本 {{ item.businessVersion }}
              </p><pre>{{ JSON.stringify(item.payload, null, 2) }}</pre>
            </details>
          </section>
          <section class="panel">
            <h2>诊断结论与建议</h2><template v-if="result.recommendation">
              <h3>{{ recommendations[String(result.recommendation)] ?? result.recommendation }}</h3><p>{{ outcome(String(result.reason ?? '')) }}</p><div class="references">
                <template
                  v-for="id in refs"
                  :key="id"
                >
                  <button
                    v-if="evidence.some(item => item.id === id)"
                    class="secondary"
                    @click="locate(id)"
                  >
                    E{{ String(evidence.findIndex(item => item.id === id) + 1).padStart(2, '0') }} 查看证据
                  </button><span
                    v-else
                    class="warning"
                  >引用 {{ id }} 暂不可定位，请刷新。</span>
                </template>
              </div>
            </template><p v-else>
              尚无已校验的诊断结论。后台独立推进，可刷新读取最新状态。
            </p><p v-if="task.outcome">
              任务结果代码：{{ task.outcome }}
            </p><p
              v-if="task.state === 'WAITING_APPROVAL'"
              class="notice"
            >
              诊断建议已生成，等待审批。审批交互将在 T13 提供；本页不会执行修复。
            </p><p
              v-if="task.state === 'MANUAL'"
              class="warning"
            >
              请核实缺失、冲突或停止原因；任务转人工不表示已解除订单限制。
            </p><template v-if="diagnosis?.run">
              <p>已用 {{ diagnosis.run.rounds }} 轮 · {{ diagnosis.run.tools }} 次查询 · {{ diagnosis.run.reviews }} 次复查</p><p>诊断开始：{{ date(diagnosis.run.startedAt) }}<br>预算截止：{{ date(diagnosis.run.deadlineAt) }}</p>
            </template><h3>诊断进度</h3><p v-if="!diagnosis?.events.length">
              尚无诊断步骤记录。
            </p><ol class="events">
              <li
                v-for="event in diagnosis?.events"
                :key="event.id"
              >
                <strong>{{ eventLabels[event.type] ?? event.type }}</strong><small>{{ date(event.createdAt) }}</small><span v-if="event.type === 'TOOL_INTENT'">{{ parse(event.payload).name }}</span><span v-if="event.type === 'MODEL_RESPONSE'">模型：{{ parse(event.payload).model ?? '未记录' }} · 提示词版本：{{ parse(event.payload).promptVersion ?? '未记录' }}</span>
              </li>
            </ol>
          </section>
        </div>
      </template>
      <dialog
        ref="modal"
        aria-labelledby="create-title"
        @cancel="creating && $event.preventDefault()"
      >
        <form @submit.prevent="create">
          <h2 id="create-title">
            新建订单诊断
          </h2><p>提交后由后台收集只读证据，不会直接修复订单。</p><label for="new-order">订单号</label><input
            id="new-order"
            v-model="newOrder"
            :disabled="creating"
            maxlength="100"
            required
            aria-describedby="create-error"
            placeholder="输入模拟服务的实际订单号"
          ><p
            id="create-error"
            role="alert"
            class="error"
          >
            {{ createError }}
          </p><div class="toolbar">
            <button
              type="button"
              class="secondary"
              :disabled="creating"
              @click="modal?.close()"
            >
              取消
            </button><button :disabled="creating">
              {{ creating ? '正在创建…' : '开始诊断' }}
            </button>
          </div>
        </form>
      </dialog>
    </template>
    <EnvironmentStatus />
    <footer>只读刷新不会调用模型或重新执行。审批、取消与执行详情交互将在 T13 提供。</footer>
  </main>
</template>
