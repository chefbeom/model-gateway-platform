type RuntimeState = { enabled: boolean; healthStatus: string }
export function runtimeStatus(endpoint: RuntimeState) {
  const code = endpoint.enabled ? endpoint.healthStatus.toUpperCase() : 'DISABLED'
  const states: Record<string, [string, string]> = {
    HEALTHY: ['준비됨', 'API 연결이 정상이며 로드된 모델이 확인되었습니다. 요청별 기능·동시성 조건은 별도로 확인합니다.'],
    STANDBY: ['모델 대기', 'API 연결은 정상입니다. 현재 로드된 모델이 없어 모델 로드를 기다리고 있습니다.'],
    LOADING: ['모델 준비 중', 'API에 연결되어 있으며 모델 로드·다운로드·언로드가 진행 중입니다. 준비가 끝날 때까지 신규 추론 대상에서 제외됩니다.'],
    CONNECTED: ['모델 상태 미확인', 'API 연결은 정상이나 이 서버 응답만으로 모델의 실제 로드 상태를 확인할 수 없습니다. 모델이 로드됐다고 추정하지 않습니다.'],
    SUSPECT: ['연결 불안정', '최근 연결 확인이 실패했습니다. 연속 실패 임계값에 도달하면 UNHEALTHY로 전환됩니다.'],
    UNHEALTHY: ['연결·복구 실패', 'API 연결 확인이 연속 실패했거나 로드된 모델의 복구 검증이 실패했습니다. 주소·인증·API 서버와 로그를 확인하세요.'],
    RECOVERING: ['복구 확인 중', 'API 연결이 복구되어 로드된 모델의 동작을 확인하고 있습니다.'],
    DRAINING: ['신규 요청 중지', '관리자가 Drain을 설정했습니다. API 연결 여부와 별개로 신규 요청을 받지 않습니다.'],
    DISABLED: ['사용 중지', '관리 설정에서 비활성화되어 연결 검사와 신규 라우팅 대상에서 제외됩니다.'],
    UNKNOWN: ['미확인', '아직 유효한 연결 확인 기록이 없습니다. 프로그램 실행 여부가 아니라 API 서버 접근 가능 여부를 확인하세요.']
  }
  const [label, description] = states[code] ?? states.UNKNOWN
  return { code, label, description, className: code.toLowerCase() }
}
