<script setup lang="ts">
import { ref } from 'vue'
import { adminFetch, type AdminAuth, type Deployment } from './api'

type OrganizationOption = { id: string; name: string; status: string }
type ProjectOption = { id: string; organizationId: string; name: string; status: string }

const organizationName = ref('My Organization')
const organizationId = ref(sessionStorage.getItem('aiconnect.setup.organizationId') ?? '')
const organizations = ref<OrganizationOption[]>([])
const projectName = ref('default-project')
const projectId = ref(sessionStorage.getItem('aiconnect.setup.projectId') ?? '')
const projects = ref<ProjectOption[]>([])
const nodeName = ref('gpu-node-01')
const nodeId = ref(sessionStorage.getItem('aiconnect.setup.nodeId') ?? '')
const baseUrl = ref('http://gpu-node-01:1234')
const runtimeToken = ref('')
const endpointId = ref(sessionStorage.getItem('aiconnect.setup.endpointId') ?? '')
const deployments = ref<Deployment[]>([])
const deploymentId = ref(sessionStorage.getItem('aiconnect.setup.deploymentId') ?? '')
const serviceKey = ref('text-pro')
const serviceName = ref('Text Pro')
const failoverPolicy = ref<'STRICT' | 'COMPATIBLE' | 'DEGRADED'>('COMPATIBLE')
const retryPolicy = ref<'SAFE' | 'AGGRESSIVE'>('SAFE')
const allowDegraded = ref(false)
const inputPrice = ref(0)
const outputPrice = ref(0)
const serviceId = ref(sessionStorage.getItem('aiconnect.setup.serviceId') ?? '')
const issuedKey = ref('')
const busy = ref(false)
const message = ref('순서대로 생성하거나 기존 리소스 UUID를 입력해 이어서 설정할 수 있습니다.')

function auth(): AdminAuth {
  const accessToken = sessionStorage.getItem('aiconnect.accessToken')
  return accessToken ? { accessToken } : { platformToken: sessionStorage.getItem('aiconnect.platformToken') ?? '' }
}
function save(name: string, value: string) { sessionStorage.setItem(`aiconnect.setup.${name}`, value) }
function selectOrganization() {
  save('organizationId', organizationId.value)
  projectId.value = ''
  projects.value = []
  sessionStorage.removeItem('aiconnect.setup.projectId')
}
function selectProject() { save('projectId', projectId.value) }
async function loadOrganizations() {
  await run(async () => {
    organizations.value = await adminFetch<OrganizationOption[]>('/api/admin/organizations', auth())
    if (!organizationId.value && organizations.value.length) {
      organizationId.value = organizations.value[0].id
      save('organizationId', organizationId.value)
    }
  }, () => `${organizations.value.length}개의 접근 가능한 조직을 불러왔습니다.`)
}
async function loadProjects() {
  await run(async () => {
    projects.value = await adminFetch<ProjectOption[]>(`/api/admin/organizations/${organizationId.value}/projects`, auth())
    if (!projectId.value && projects.value.length) {
      projectId.value = projects.value[0].id
      save('projectId', projectId.value)
    }
  }, () => `${projects.value.length}개의 프로젝트를 불러왔습니다.`)
}
async function run(task: () => Promise<void>, success: string | (() => string)) {
  busy.value = true
  try { await task(); message.value = typeof success === 'function' ? success() : success }
  catch (error) { message.value = error instanceof Error ? error.message : '설정 요청에 실패했습니다.' }
  finally { busy.value = false }
}
async function createOrganization() {
  await run(async () => {
    const created = await adminFetch<OrganizationOption>('/api/admin/organizations', auth(), { method: 'POST', body: JSON.stringify({ name: organizationName.value }) })
    organizations.value.push(created)
    organizationId.value = created.id
    save('organizationId', created.id)
  }, '조직을 생성했습니다.')
}
async function createProject() {
  await run(async () => {
    const created = await adminFetch<ProjectOption>('/api/admin/projects', auth(), { method: 'POST', body: JSON.stringify({ organizationId: organizationId.value, name: projectName.value }) })
    projects.value.push(created)
    projectId.value = created.id
    save('projectId', created.id)
  }, '프로젝트를 생성했습니다.')
}
async function createNode() {
  await run(async () => { const created = await adminFetch<{ id: string }>('/api/admin/nodes', auth(), { method: 'POST', body: JSON.stringify({ organizationId: organizationId.value, name: nodeName.value, connectionMode: 'DIRECT' }) }); nodeId.value = created.id; save('nodeId', created.id) }, '추론 노드를 생성했습니다.')
}
async function createEndpoint() {
  await run(async () => { const created = await adminFetch<{ id: string }>('/api/admin/runtime-endpoints', auth(), { method: 'POST', body: JSON.stringify({ nodeId: nodeId.value, runtimeType: 'LM_STUDIO', baseUrl: baseUrl.value, apiToken: runtimeToken.value || null }) }); endpointId.value = created.id; runtimeToken.value = ''; save('endpointId', created.id) }, 'LM Studio Runtime Endpoint를 등록했습니다.')
}
async function synchronizeModels() {
  await run(async () => {
    const probe = await adminFetch<{ reachable: boolean }>(`/api/admin/runtime-endpoints/${endpointId.value}/probe`, auth(), { method: 'POST' })
    if (!probe.reachable) throw new Error('LM Studio에 연결할 수 없습니다. Tailscale 주소와 토큰을 확인하세요.')
    await adminFetch(`/api/admin/runtime-endpoints/${endpointId.value}/sync-models`, auth(), { method: 'POST' })
    deployments.value = await adminFetch<Deployment[]>(`/api/admin/runtime-endpoints/${endpointId.value}/deployments`, auth())
    if (deployments.value.length && !deploymentId.value) { deploymentId.value = deployments.value[0].id; save('deploymentId', deploymentId.value) }
  }, () => `${deployments.value.length}개의 모델 배포를 불러왔습니다.`)
}
async function createService() {
  await run(async () => {
    const created = await adminFetch<{ id: string }>('/api/admin/services', auth(), {
      method: 'POST',
      body: JSON.stringify({ organizationId: organizationId.value, serviceKey: serviceKey.value, displayName: serviceName.value, failoverPolicy: failoverPolicy.value, retryPolicy: retryPolicy.value, allowDegraded: allowDegraded.value, requiredCapabilitiesJson: '[]', inputPricePerMillion: inputPrice.value, outputPricePerMillion: outputPrice.value })
    })
    serviceId.value = created.id; save('serviceId', created.id)
  }, '논리 서비스를 생성했습니다.')
}
async function connectService() {
  await run(async () => {
    await adminFetch(`/api/admin/services/${serviceId.value}/targets`, auth(), { method: 'POST', body: JSON.stringify({ deploymentId: deploymentId.value, priority: 1, weight: 100, degraded: false }) })
    await adminFetch(`/api/admin/projects/${projectId.value}/service-access`, auth(), { method: 'POST', body: JSON.stringify({ serviceId: serviceId.value }) })
  }, '배포 대상을 연결하고 프로젝트 접근 권한을 부여했습니다.')
}
async function issueKey() {
  await run(async () => { const created = await adminFetch<{ secret: string }>(`/api/admin/projects/${projectId.value}/api-keys`, auth(), { method: 'POST', body: JSON.stringify({ name: 'default-key', expiresAt: null }) }); issuedKey.value = created.secret }, 'API 키를 발급했습니다. 아래 값은 다시 조회할 수 없습니다.')
}
</script>

<template>
  <main class="setup-main">
    <section class="setup-shell">
      <div><p class="eyebrow">CONTROL PLANE</p><h2>초기 구성</h2><p>GPU 종류를 선택하지 않고 LM Studio Runtime과 발견된 모델을 논리 서비스에 연결합니다.</p></div>
      <p class="notice">{{ message }}</p>
      <div class="steps">
        <article><span>1</span><h3>조직</h3><input v-model="organizationName" placeholder="조직 이름" /><select v-model="organizationId" @change="selectOrganization"><option value="">조직 선택</option><option v-for="item in organizations" :key="item.id" :value="item.id">{{ item.name }} · {{ item.status }}</option></select><input v-model="organizationId" placeholder="Organization UUID" /><div class="inline-actions"><button :disabled="busy" @click="loadOrganizations">목록 조회</button><button :disabled="busy || !organizationName" @click="createOrganization">조직 생성</button></div></article>
        <article><span>2</span><h3>프로젝트</h3><input v-model="projectName" placeholder="프로젝트 이름" /><select v-model="projectId" @change="selectProject"><option value="">프로젝트 선택</option><option v-for="item in projects" :key="item.id" :value="item.id">{{ item.name }} · {{ item.status }}</option></select><input v-model="projectId" placeholder="Project UUID" /><div class="inline-actions"><button :disabled="busy || !organizationId" @click="loadProjects">목록 조회</button><button :disabled="busy || !organizationId" @click="createProject">프로젝트 생성</button></div></article>
        <article><span>3</span><h3>추론 노드</h3><input v-model="nodeName" placeholder="노드 이름" /><input v-model="nodeId" placeholder="Node UUID" /><button :disabled="busy || !organizationId" @click="createNode">노드 생성</button></article>
        <article><span>4</span><h3>LM Studio</h3><input v-model="baseUrl" placeholder="Tailscale URL :1234" /><input v-model="runtimeToken" type="password" placeholder="LM Studio API Token" /><input v-model="endpointId" placeholder="Endpoint UUID" /><button :disabled="busy || !nodeId || !baseUrl" @click="createEndpoint">Endpoint 등록</button></article>
        <article><span>5</span><h3>모델 발견</h3><select v-model="deploymentId"><option value="">Deployment 선택</option><option v-for="item in deployments" :key="item.id" :value="item.id">{{ item.displayName }}</option></select><input v-model="deploymentId" placeholder="Deployment UUID" /><button :disabled="busy || !endpointId" @click="synchronizeModels">Probe + 동기화</button></article>
        <article><span>6</span><h3>논리 서비스</h3><input v-model="serviceKey" placeholder="model 값 (text-pro)" /><input v-model="serviceName" placeholder="표시 이름" /><select v-model="failoverPolicy"><option value="STRICT">STRICT · 동일 호환 그룹만</option><option value="COMPATIBLE">COMPATIBLE · 승인된 모델</option><option value="DEGRADED">DEGRADED · 저성능 대상 포함</option></select><select v-model="retryPolicy"><option value="SAFE">SAFE · 연결 전 실패만 재시도</option><option value="AGGRESSIVE">AGGRESSIVE · 5xx/타임아웃도 재시도</option></select><label><input v-model="allowDegraded" type="checkbox" /> 성능 저하 대체 대상 허용</label><div class="prices"><input v-model.number="inputPrice" type="number" min="0" placeholder="입력/백만 토큰" /><input v-model.number="outputPrice" type="number" min="0" placeholder="출력/백만 토큰" /></div><input v-model="serviceId" placeholder="Service UUID" /><button :disabled="busy || !organizationId" @click="createService">서비스 생성</button></article>
        <article><span>7</span><h3>라우팅·권한</h3><p>선택한 배포를 Priority 1로 연결하고 프로젝트에 사용 권한을 부여합니다.</p><button :disabled="busy || !serviceId || !deploymentId || !projectId" @click="connectService">Target + Access 연결</button></article>
        <article><span>8</span><h3>API 키</h3><textarea v-if="issuedKey" :value="issuedKey" readonly></textarea><p v-else>발급 원문은 한 번만 표시됩니다.</p><button :disabled="busy || !projectId" @click="issueKey">프로젝트 키 발급</button></article>
      </div>
    </section>
  </main>
</template>

<style scoped>
.setup-main { padding-top: 0; } .setup-shell { border: 1px solid #24304a; border-radius: .9rem; padding: 1.25rem; background: #10182b; }
.steps { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: .75rem; } article { min-height: 0; display: flex; flex-direction: column; gap: .55rem; } article > span { width: 1.7rem; height: 1.7rem; display: grid; place-items: center; border-radius: 50%; color: #b8c6ff; background: #263c78; } h3 { margin: 0; } article p { min-height: 2.4rem; color: #9ba9c7; font-size: .8rem; } textarea, select { width: 100%; min-height: 2.6rem; border: 1px solid #33415f; border-radius: .5rem; padding: .65rem; color: #f7f9ff; background: #121b31; } .prices, .inline-actions { display: grid; grid-template-columns: 1fr 1fr; gap: .4rem; }
@media (max-width: 1050px) { .steps { grid-template-columns: repeat(2, minmax(0, 1fr)); } } @media (max-width: 620px) { .steps { grid-template-columns: 1fr; } }
</style>
