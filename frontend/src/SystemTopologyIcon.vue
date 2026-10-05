<script setup lang="ts">
import type { TopologyKind } from './systemTopology'
defineProps<{ kind: TopologyKind }>()
</script>

<template>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <g v-if="kind === 'project'"><rect x="3" y="4" width="18" height="13" rx="2" /><path d="M8 21h8m-4-4v4m-5-11 3-3m-3 3 3 3m7-6-3 3 3 3" /></g>
    <g v-else-if="kind === 'gateway'"><path d="M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6l-9-4Z" /><path d="m8 12 3 3 5-6" /></g>
    <g v-else-if="kind === 'service'"><rect x="8" y="3" width="8" height="6" rx="1" /><rect x="2" y="16" width="7" height="5" rx="1" /><rect x="15" y="16" width="7" height="5" rx="1" /><path d="M12 9v4H5v3m7-3h7v3" /></g>
    <g v-else-if="kind === 'model'"><path d="m12 2 10 6v8l-10 6-10-6V8l10-6Zm0 20V12m-10-4 10 4 10-4M7 5l10 5v8" /></g>
    <g v-else-if="kind === 'runtime'"><rect x="3" y="3" width="18" height="7" rx="2" /><rect x="3" y="14" width="18" height="7" rx="2" /><path d="M7 6.5h.01M7 17.5h.01M12 6.5h5m-5 11h5" /></g>
    <g v-else-if="kind === 'provider'"><path d="M7 18H6a4 4 0 0 1-1-8 7 7 0 0 1 13-2 5 5 0 0 1 0 10h-1m-5 4V12m-4 4 4-4 4 4" /></g>
    <g v-else-if="kind === 'database'"><ellipse cx="12" cy="5" rx="9" ry="3" /><path d="M3 5v14c0 4 18 4 18 0V5M3 12c0 4 18 4 18 0" /></g>
    <g v-else-if="kind === 'shared'"><path d="m12 3 10 5-10 5L2 8l10-5ZM2 12l10 5 10-5M2 17l10 5 10-5" /></g>
    <g v-else><path d="M3 3v18h18M7 16l4-6 4 3 5-8" /><circle cx="11" cy="10" r="1" /><circle cx="15" cy="13" r="1" /></g>
  </svg>
</template>
