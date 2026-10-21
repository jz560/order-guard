export type Service = 'backend' | 'simulator'
export type HealthState = 'loading' | 'up' | 'down'

export async function checkHealth(service: Service): Promise<HealthState> {
  try {
    const response = await fetch(`/health/${service}`, {
      signal: AbortSignal.timeout(5000),
      cache: 'no-store',
    })
    if (!response.ok) return 'down'
    const body: unknown = await response.json()
    return typeof body === 'object' && body !== null && 'status' in body && body.status === 'UP'
      ? 'up' : 'down'
  } catch {
    return 'down'
  }
}
