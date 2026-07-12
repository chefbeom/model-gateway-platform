# 외부 OpenAI Provider 운영 가이드

AICONNECT는 회사 GPU의 LM Studio를 기본 경로로 유지하면서, 관리자가 등록한 OpenAI 호환 외부 Provider를 프로젝트별로 허용할 수 있다. 외부 API 키는 관리자가 한 번 등록하며 프로젝트 사용자에게 공개되지 않는다.

## 기본 원칙

- Provider 등록만으로 요청이 외부로 전송되지 않는다.
- 프로젝트 구성원이 사용 사유를 제출하고 관리자 또는 프로젝트 관리자가 승인해야 한다.
- **수동 사용**과 **자동 Failover**는 서로 다른 승인 항목이다.
- 자동 Failover는 기본값이 OFF이며, 승인 화면에서 명시적으로 켠 프로젝트에서만 작동한다.
- 외부 전송 시 프롬프트와 첨부 입력이 회사 네트워크 밖으로 전달될 수 있으므로 데이터 정책을 먼저 확인한다.
- 외부 호출의 입력·출력 토큰, 예상 비용, Provider, 라우팅 사유는 기존 요청·사용량·감사 기록에 함께 저장된다.

## 관리자 설정 순서

1. **외부 AI**에서 Provider 이름, OpenAI 호환 Base URL, API 키를 등록한다.
2. **연결 확인**을 실행해 인증과 네트워크 상태를 검증한다.
3. 허용할 모델만 명시적으로 등록한다. 모델 ID, 호환성 키, 컨텍스트 길이, Capability, 동시 요청 수, 입력·출력 100만 토큰 단가를 입력한다.
4. 프로젝트의 사용 요청을 검토하거나, 관리자가 프로젝트를 선택해 권한을 직접 부여한다.
5. `수동 사용 허용`, `자동 Failover 허용`, 월 비용 상한, 만료일을 각각 설정하고 승인한다.
6. **LLM 서비스**에서 외부 모델을 Target으로 연결한다.

## 수동 사용 구성

외부 모델만 Target으로 가진 별도 논리 서비스를 만든다. 예를 들어 `openai-heavy`를 만들고 승인된 외부 모델을 연결한다. 사용자는 기존 프로젝트 API 키로 다음처럼 명시적으로 호출한다.

```json
{
  "model": "openai-heavy",
  "messages": [{"role": "user", "content": "복잡한 보고서를 분석해 주세요."}]
}
```

프로젝트에 `manualAllowed=true` 승인이 없으면 Gateway는 외부 호출을 실행하지 않고 모델 사용 불가 오류를 반환한다.

## 선택형 자동 Failover 구성

하나의 논리 서비스에 로컬 LM Studio Target을 우선순위 1로, 외부 모델 Target을 후순위로 연결한다. 프로젝트 승인 정책에서 `autoFailoverEnabled=true`를 명시적으로 켠다.

```text
text-pro
├─ P1: 사내 LM Studio 모델
└─ P2: 외부 OpenAI 모델
```

정상 상태에서는 로컬 모델을 사용한다. 사용 가능한 로컬 Target이 없거나 로컬 호출이 안전하게 실패한 경우에만 승인된 외부 Target을 시도한다. 스위치를 끄거나 승인을 취소하면 외부 자동 전환은 즉시 후보에서 제외된다.

## 비용과 관측

- 모델 등록 시 입력·출력 100만 토큰 단가를 설정한다.
- 월 비용 상한을 설정하면 이미 기록된 해당 프로젝트의 외부 예상 비용이 상한에 도달한 후 추가 외부 호출을 차단한다.
- **관측성**에서 `Provider=OPENAI`와 `MANUAL_EXTERNAL` 또는 `AUTO_FAILOVER`를 확인한다.
- **사용량**에서 `CLOUD · Provider · model` 단위 처리량과 프로젝트/API 키별 비용을 확인한다.
- Provider 등록·정책 승인·변경은 감사 로그에 기록된다.

## 운영 체크리스트

- [ ] 외부 API 키가 화면·로그·응답에 노출되지 않는가
- [ ] Provider 연결 확인이 성공하는가
- [ ] 허용할 모델만 등록했는가
- [ ] 데이터 반출 정책을 프로젝트 소유자가 확인했는가
- [ ] 자동 Failover 기본값이 OFF인가
- [ ] 월 비용 상한과 승인 만료일을 설정했는가
- [ ] 테스트 요청에서 Provider와 라우팅 사유가 관측되는가
- [ ] 로컬 장애 테스트 후 자동 전환 ON/OFF가 정책대로 작동하는가

## 장애 처리

- `EXTERNAL_ACCESS_REQUIRED`: 프로젝트 승인이 없거나 만료·취소되었다.
- `MODEL_UNAVAILABLE`: 승인 정책, Target 상태, Capability, Provider 상태를 확인한다.
- Provider 연결 실패: 외부 AI 화면에서 연결 확인을 다시 실행하고 Base URL, API 키, 방화벽을 확인한다.
- 비용 상한 도달: 프로젝트 사용량을 검토한 뒤 관리자가 상한을 변경하거나 외부 사용을 중단한다.
## VM 전체 시나리오 재검증

Standalone VM의 프로젝트 디렉터리에서 다음 명령을 실행하면 실제 Gateway, MariaDB, 권한, 사용량 경로를 한 번에 검사한다.

```bash
chmod +x scripts/verify-external-provider-vm.sh
./scripts/verify-external-provider-vm.sh
```

스크립트는 Compose 내부 네트워크에 일회용 모의 OpenAI 서버를 실행하고 다음을 검증한다.

1. 관리자 Provider 등록과 API 키 비노출
2. Provider 연결 확인과 외부 모델 등록
3. 관리자 직접 권한 부여
4. 개발자 사용 요청과 관리자 재승인
5. 승인 전 수동 호출 차단과 승인 후 성공
6. 자동 Failover OFF 차단과 ON 성공
7. Provider·라우팅 사유·토큰·비용·사용량·감사 기록
8. 검증 Provider 비활성화, 프로젝트 중지, API 키 폐기

실제 OpenAI API 키나 외부 비용은 사용하지 않는다. 결과 JSON의 `ok`가 `true`이고 모든 `steps[].ok`가 `true`여야 통과다.