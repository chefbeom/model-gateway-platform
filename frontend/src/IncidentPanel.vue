<script setup lang="ts">
import { ref } from 'vue'
import { adminFetch, type AdminAuth } from './api'

type Delivery = { id: string; channelType?: string; eventType: string; status: string; errorMessage?: string; createdAt: string }
type Incident = { id: string; endpointBaseUrl?: string; status: string; reason: string; openedAt: string; recoveredAt?: string; deliveries: Delivery[] }
type ChannelType = 'DISCORD_WEBHOOK' | 'TELEGRAM_BOT'
type Channel = { id: string; type: ChannelType; enabled: boolean }

const organizationId = ref(sessionStorage.getItem('aiconnect.setup.organizationId') ?? '')
const status = ref('')
const incidents = ref<Incident[]>([])
const channels = ref<Channel[]>([])
const channelType = ref<ChannelType>('DISCORD_WEBHOOK')
const target = ref('')
const secret = ref('')
const busy = ref(false)
const message = ref('조직의 장애와 알림 전송 결과를 조회하세요.')

function auth(): AdminAuth {
  const accessToken = sessionStorage.getItem('aiconnect.accessToken')
  return accessToken ? { accessToken } : { platformToken: sessionStorage.getItem('aiconnect.platformToken') ?? '' }
}

async function load() {
  busy.value = true
  try {
    const query = status.value ? `?status=${encodeURIComponent(status.value)}` : ''
    ;[incidents.value, channels.value] = await Promise.all([
      adminFetch<Incident[]>(`/api/admin/organizations/${organizationId.value}/incidents${query}`, auth()),
      adminFetch<Channel[]>(`/api/admin/organizations/${organizationId.value}/notification-channels`, auth())
    ])
    message.value = `${incidents.value.length}개의 장애와 ${channels.value.length}개의 알림 채널을 불러왔습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '장애 조회에 실패했습니다.'
  } finally {
    busy.value = false
  }
}

async function createChannel() {
  busy.value = true
  try {
    await adminFetch(`/api/admin/organizations/${organizationId.value}/notification-channels`, auth(), {
      method: 'POST', body: JSON.stringify({ type: channelType.value, target: target.value, secret: secret.value || null })
    })
    target.value = ''
    secret.value = ''
    await load()
    message.value = '암호화된 알림 채널을 등록했습니다.'
  } catch (error) {
    message.value = error instanceof Error ? error.message : '알림 채널 등록에 실패했습니다.'
  } finally {
    busy.value = false
  }
}

async function setChannelEnabled(channel: Channel, enabled: boolean) {
  busy.value = true
  try {
    await adminFetch(`/api/admin/organizations/${organizationId.value}/notification-channels/${channel.id}`, auth(), {
      method: 'PATCH', body: JSON.stringify({ enabled })
    })
    channel.enabled = enabled
    message.value = `${channel.type} 채널을 ${enabled ? '활성화' : '비활성화'}했습니다.`
  } catch (error) {
    message.value = error instanceof Error ? error.message : '알림 채널 상태 변경에 실패했습니다.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="incident-main">
    <section class="incident-shell">
      <div>
        <p class="eyebrow">INCIDENTS</p>
        <h2>장애와 알림</h2>
        <p>Endpoint 장애·복구와 Discord/Telegram 전달 성공 여부를 확인합니다.</p>
      </div>
      <p class="notice">{{ message }}</p>
      <div class="filters">
        <input v-model="organizationId" placeholder="Organization UUID" />
        <select v-model="status"><option value="">모든 상태</option><option>OPEN</option><option>RECOVERED</option></select>
        <button :disabled="busy || !organizationId" @click="load">조회</button>
      </div>
      <div class="channel-form">
        <select v-model="channelType"><option value="DISCORD_WEBHOOK">Discord Webhook</option><option value="TELEGRAM_BOT">Telegram Bot</option></select>
        <input v-model="target" :placeholder="channelType === 'DISCORD_WEBHOOK' ? 'Webhook URL' : 'Chat ID'" />
        <input v-model="secret" type="password" :placeholder="channelType === 'TELEGRAM_BOT' ? 'Bot Token' : 'Discord는 비워 두세요'" />
        <button :disabled="busy || !organizationId || !target" @click="createChannel">채널 등록</button>
      </div>
      <div v-if="channels.length" class="channel-list">
        <span v-for="channel in channels" :key="channel.id" class="channel">
          {{ channel.type }} · {{ channel.enabled ? '활성' : '비활성' }}
          <button :disabled="busy" class="channel-action" @click="setChannelEnabled(channel, !channel.enabled)">
            {{ channel.enabled ? '끄기' : '켜기' }}
          </button>
        </span>
      </div>
      <div class="incident-list">
        <article v-for="incident in incidents" :key="incident.id">
          <div class="incident-heading">
            <div>
              <strong>{{ incident.endpointBaseUrl ?? incident.id }}</strong>
              <small>{{ new Date(incident.openedAt).toLocaleString() }} → {{ incident.recoveredAt ? new Date(incident.recoveredAt).toLocaleString() : '진행 중' }}</small>
            </div>
            <span class="status" :class="incident.status === 'RECOVERED' ? 'healthy' : 'unhealthy'">{{ incident.status }}</span>
          </div>
          <p>{{ incident.reason }}</p>
          <div v-if="incident.deliveries.length" class="deliveries">
            <div v-for="delivery in incident.deliveries" :key="delivery.id">
              <strong>{{ delivery.channelType ?? '삭제된 채널' }} · {{ delivery.eventType }}</strong>
              <span class="status" :class="delivery.status === 'SENT' ? 'healthy' : 'unhealthy'">{{ delivery.status }}</span>
              <small v-if="delivery.errorMessage">{{ delivery.errorMessage }}</small>
            </div>
          </div>
          <small v-else>알림 전달 기록 없음</small>
        </article>
      </div>
    </section>
  </main>
</template>

<style scoped>
.incident-main { padding-top: 0; }
.incident-shell { border: 1px solid #24304a; border-radius: .9rem; padding: 1.25rem; background: #10182b; }
.filters, .channel-form { display: flex; gap: .5rem; margin: .75rem 0; }
.filters > *, .channel-form > * { flex: 1; }
select { min-height: 2.6rem; border: 1px solid #33415f; border-radius: .5rem; padding: .6rem; color: #f7f9ff; background: #121b31; }
.channel-list { display: flex; gap: .4rem; flex-wrap: wrap; margin: .65rem 0; }
.channel { display: inline-flex; align-items: center; gap: .45rem; padding: .35rem .6rem; border-radius: 1rem; background: #1c2945; }
.channel-action { min-height: 1.8rem; padding: .2rem .55rem; }
.incident-list { display: grid; gap: .75rem; }
.incident-list article { border: 1px solid #273451; border-radius: .7rem; padding: .85rem; }
.incident-heading, .deliveries > div { display: flex; justify-content: space-between; gap: 1rem; align-items: center; }
.deliveries { display: grid; gap: .4rem; }
.deliveries > div { padding-top: .45rem; border-top: 1px solid #273451; }
@media (max-width: 760px) { .filters, .channel-form { flex-direction: column; } }
</style>
