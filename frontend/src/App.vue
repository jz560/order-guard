<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { checkHealth, type HealthState, type Service } from './health'

const services: { id: Service; label: string; description: string }[] = [
  { id: 'backend', label: '业务服务', description: '任务与诊断服务的工程基础' },
  { id: 'simulator', label: '模拟服务', description: '独立的业务模拟环境' },
]
const status = reactive<Record<Service, HealthState>>({ backend: 'loading', simulator: 'loading' })
const loading = ref(false)
const labels = { loading: '正在检查', up: '运行正常', down: '暂不可用' }

async function refresh() {
  if (loading.value) return
  loading.value = true
  await Promise.all(services.map(async ({ id }) => {
    status[id] = 'loading'
    status[id] = await checkHealth(id)
  }))
  loading.value = false
}
onMounted(refresh)
</script>

<template>
  <main>
    <header>
      <div class="eyebrow">
        ORDER OPERATIONS / 本地演示
      </div>
      <h1>订单异常处置工作台</h1>
      <p>以证据诊断异常，通过审批控制每一次处置。</p>
    </header>
    <section
      class="overview"
      aria-labelledby="environment-title"
    >
      <div class="section-heading">
        <h2 id="environment-title">
          环境状态
        </h2>
        <button
          :disabled="loading"
          @click="refresh"
        >
          {{ loading ? '检查中…' : '刷新状态' }}
        </button>
      </div>
      <div
        class="services"
        aria-live="polite"
      >
        <article
          v-for="service in services"
          :key="service.id"
        >
          <span
            class="badge"
            :class="status[service.id]"
          >{{ labels[status[service.id]] }}</span>
          <h3>{{ service.label }}</h3>
          <p>{{ service.description }}</p>
          <p
            v-if="status[service.id] === 'down'"
            class="help"
          >
            请确认本地服务已启动后刷新。
          </p>
        </article>
      </div>
    </section>
    <section class="notice">
      <h2>工程基础已建立</h2>
      <p>当前为 T01 阶段，尚未开放订单诊断、审批与修复功能。</p>
      <p>默认使用测试替身，无需模型密钥。所有业务数据均为模拟数据，不涉及真实资金。</p>
    </section>
  </main>
</template>
