import type { DocPage } from './types'

export const runtimeDiagnosticsDoc: DocPage = {
  id: 'runtime-diagnostics',
  group: '운영과 참조',
  title: 'Runtime 연결 장애 진단',
  shortTitle: 'Runtime 장애 진단',
  description: 'MODEL_UNAVAILABLE, UNHEALTHY, Tailscale 노드 오프라인 상황을 순서대로 분리합니다.',
  audience: '관리자',
  minutes: 8,
  icon: '⚑',
  keywords: ['runtime', 'tailscale', 'offline', 'unhealthy', '503', 'model unavailable', '연결 확인'],
  sections: [
    {
      id: 'meaning',
      title: '503 MODEL_UNAVAILABLE의 의미',
      blocks: [
        { type: 'paragraph', text: '이 오류는 API 키나 논리 모델명이 틀렸다는 뜻이 아닙니다. 해당 논리 서비스에 연결된 Target 가운데 Endpoint·Deployment·동시성·Capability 조건을 모두 만족하는 후보가 없다는 뜻입니다.' },
        { type: 'flow', items: [
          { label: '1', title: 'Endpoint', text: 'ENABLED와 HEALTHY인지 확인' },
          { label: '2', title: 'Deployment', text: 'LOADED·ENABLED·HEALTHY인지 확인' },
          { label: '3', title: 'Target', text: '논리 서비스에 활성 Target으로 연결됐는지 확인' },
          { label: '4', title: 'Gateway', text: '프로젝트 권한이 있는 논리 model로 다시 호출' }
        ] }
      ]
    },
    {
      id: 'diagnose',
      title: 'Tailscale과 LM Studio를 순서대로 확인',
      blocks: [
        { type: 'steps', items: [
          { title: 'GPU 노드가 온라인인지 확인', text: 'GPU 서버에서 Tailscale이 로그인되어 있고 LM Studio Developer Server가 실행 중인지 확인합니다. Tailnet 상태가 offline이면 Gateway는 연결할 수 없습니다.' },
          { title: '호스트 연결 확인', text: 'AICONNECT 호스트에서 Runtime URL의 TCP 1234와 /v1/models를 확인합니다. 여기서 실패하면 Docker나 Gateway 설정을 바꾸기 전에 원격 GPU 서버·Tailscale부터 복구합니다.' },
          { title: '인프라의 연결 확인 실행', text: '인프라 화면의 연결 확인은 성공일 때만 “연결 성공”을 표시합니다. 실패하면 HTTP 상태 또는 “runtime endpoint is unreachable” 사유가 표시됩니다.', action: { label: '인프라 열기', destination: 'infrastructure' } },
          { title: '모델 동기화와 Target 재확인', text: '연결이 성공한 후 모델 동기화를 실행하고, 사용할 Deployment가 LOADED·HEALTHY인지 및 논리 서비스 Target이 활성인지 확인합니다.', action: { label: 'LLM 서비스 열기', destination: 'services' } }
        ] },
        { type: 'callout', tone: 'warning', title: 'Docker Desktop과 Tailnet', text: '호스트에서 Runtime URL이 열리지만 API 컨테이너에서만 실패한다면 Docker가 호스트의 Tailnet 경로를 사용하지 못하는 상태일 수 있습니다. 이 경우 TS_AUTHKEY를 준비해 docker-compose.tailscale.yml 오버레이를 사용하거나, 네트워크 운영자가 Gateway 컨테이너에서 Tailnet으로 나가는 경로를 제공해야 합니다.' }
      ]
    },
    {
      id: 'verify',
      title: '복구 후 전체 경로 검증',
      blocks: [
        { type: 'checklist', items: ['사용자 프로젝트의 /v1/models에서 논리 모델명이 보임', '짧은 /v1/chat/completions가 200으로 완료됨', '관측성의 요청 탐색기에 Request ID와 Attempt가 생김', '사용량의 프로젝트·서비스·인프라 집계가 1건 증가함', 'GPU Runtime IP:1234가 아니라 Gateway Base URL을 호출함'] },
        { type: 'callout', tone: 'info', title: '관측이 0건일 때', text: 'AICONNECT DB에 요청이 0건이면 해당 호출은 Gateway를 우회한 것입니다. 호출 애플리케이션의 Base URL은 Gateway의 /v1이며, LM Studio의 100.x.x.x:1234 주소는 사용자 호출에 사용하지 않습니다.' }
      ]
    }
  ]
}
