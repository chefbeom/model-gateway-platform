<script setup lang="ts">
import ExternalProvidersPageImpl from './ExternalProvidersPageImpl.vue'
import type { AdminAuth } from './api'

const props = defineProps<{ organizationId: string; auth: AdminAuth; platformAdmin?: boolean }>()
const sessionUser = (() => {
  try { return JSON.parse(sessionStorage.getItem('aiconnect.user') ?? 'null') as { platformAdmin?: boolean } | null } catch { return null }
})()
const isPlatformAdmin = Boolean(props.platformAdmin || props.auth.platformToken || sessionUser?.platformAdmin)
</script>

<template>
  <ExternalProvidersPageImpl
    :organization-id="props.organizationId"
    :auth="props.auth"
    :platform-admin="isPlatformAdmin"
  />
</template>
