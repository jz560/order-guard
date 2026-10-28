<script setup lang="ts">
import { reactive, ref } from 'vue'
import { checkHealth, type HealthState, type Service } from './health'
const status = reactive<Record<Service, HealthState>>({ backend: 'loading', simulator: 'loading' })
const checked = ref(false), loading = ref(false)
const labels = { loading: '正在检查', up: '运行正常', down: '暂不可用' }
async function refresh() {
  if (loading.value) return
  checked.value = true; loading.value = true
  await Promise.all((['backend', 'simulator'] as const).map(async id => { status[id] = 'loading'; status[id] = await checkHealth(id) }))
  loading.value = false
}
</script>
<template>
  <details class="environment">
    <summary>环境状态</summary>
    <button
      :disabled="loading"
      class="secondary"
      @click="refresh"
    >
      {{ loading ? '检查中…' : '检查服务' }}
    </button>
    <p
      v-if="checked"
      role="status"
    >
      业务服务：{{ labels[status.backend] }} · 模拟服务：{{ labels[status.simulator] }}
    </p>
    <p v-else>
      点击检查服务读取当前健康状态。
    </p>
  </details>
</template>
