---
document_id: aiconnect-user-guide-ko
title: AICONNECT 사용자 사용 가이드
audience:
  - 일반 사용자
  - 개발자
  - 프로젝트 소유자
  - AI 작업 보조자
language: ko-KR
source_of_truth:
  - frontend/src/ProjectsPage.vue
  - frontend/src/UsagePage.vue
  - src/main/java/com/aiconnect/llmgateway/web/OpenAiGatewayController.java
last_reviewed: 2026-07-11
---

# AICONNECT 사용자 사용 가이드

## 1. 이 문서의 목적

AICONNECT는 회사 또는 개인이 소유한 여러 LM Studio 기반 LLM 서버를 하나의 OpenAI 호환 API로 사용하는 플랫폼이다. 사용자는 GPU 사양, Tailscale IP, LM Studio 모델 ID를 직접 다루지 않는다. 대신 **프로젝트**, **API 키**, **논리 모델명**만 사용한다.

이 문서는 사람이 화면을 통해 설정하고 API를 호출하는 방법과, AI 보조자가 사용자의 요청을 안전하게 처리할 때 따라야 할 규칙을 함께 설명한다.

## 2. 가장 먼저 알아둘 구조

```text
사용자 계정
  └─ 팀(선택)
      └─ 프로젝트
          ├─ API 키
          ├─ 사용 가능한 논리 모델
          └─ 사용량·비용·알림 정책

애플리케이션
  └─ AICONNECT /v1 API
      └─ Gateway가 적절한 LM Studio Runtime을 선택
```

다음은 사용자에게 보이지 않거나 직접 설정하지 않는 항목이다.

- GPU 제품명 및 VRAM
- Tailscale 주소
- LM Studio API 토큰
- 실제 모델 파일 식별자와 양자화 방식
- 장애가 났을 때의 대체 서버 선택

사용자가 호출하는 `model` 값은 `text-pro`, `document-analysis`처럼 관리자가 만든 **논리 모델명**이다.

## 3. 역할과 권한

| 역할 | 할 수 있는 일 | 할 수 없는 일 |
|---|---|---|
| Developer | 사용 가능한 프로젝트·API 키 확인, API 호출, 사용량·요청 내역 확인 | 다른 팀/프로젝트의 키·요청·서버 변경 |
| Project Owner | 프로젝트 생성·관리, API 키 발급·폐기, 팀 범위의 사용량 확인 | 조직 전체 Runtime·라우팅 변경 |
| Team Admin | 팀 구성원·팀 프로젝트 관리 | 다른 팀 또는 조직 전체 인프라 변경 |
| Organization Admin | 조직의 사용자·팀·프로젝트·Runtime·서비스 관리 | 다른 조직 관리 |
| Platform Administrator | 전체 플랫폼 운영 | 없음. 운영 감사 대상임 |

권한이 없다는 메시지가 나오면 URL을 바꾸거나 다른 API 키를 시도하지 말고, 팀 관리자 또는 조직 관리자에게 필요한 권한을 요청한다.

## 4. 빠른 시작: API를 호출하기까지

### 4.1 로그인

1. AICONNECT 웹 주소에 접속한다.
2. 전달받은 이메일과 비밀번호로 로그인한다.
3. 상단 조직 선택기에서 작업할 조직을 선택한다.
4. 좌측 메뉴에서 **프로젝트 & API 키**로 이동한다.

비밀번호는 12자 이상이어야 한다. 로그인 정보와 API 키는 채팅, Git 저장소, 캡처 이미지, 브라우저 주소창에 남기지 않는다.

### 4.2 프로젝트 생성

프로젝트는 API 키·사용량·비용·알림을 분리하는 기본 단위다. 애플리케이션 또는 배포 환경마다 프로젝트를 나누는 것을 권장한다.

권장 예시:

| 용도 | 프로젝트 이름 예시 |
|---|---|
| 개발 환경 | `document-api-dev` |
| 운영 환경 | `document-api-prod` |
| 이미지 프롬프트 생성 | `image-prompt-service` |
| 부서별 실험 | `marketing-lab` |

프로젝트 생성 시 가능한 경우 팀을 지정한다. 팀 지정은 부서·서비스·업무 단위별 사용량, 알림, 감사 범위를 나누는 데 사용된다.

### 4.3 API 키 발급

1. 프로젝트를 선택한다.
2. **API 키 발급**을 선택한다.
3. 키의 용도를 알 수 있는 이름을 입력한다. 예: `production-backend`, `local-test`.
4. 필요한 경우 만료 시각을 지정한다.
5. 생성 직후 표시되는 키 원문을 안전한 비밀 저장소에 한 번만 보관한다.

API 키 원문은 다시 표시되지 않는다. 분실했으면 기존 키를 폐기하고 새 키를 발급한다.

### 4.4 사용 가능한 모델 확인

프로젝트마다 허용된 논리 모델이 다르다. 웹 화면의 프로젝트 상세 또는 다음 API로 확인한다.

```http
GET /v1/models
Authorization: Bearer <AICONNECT_API_KEY>
```

응답의 `data[].id`만 애플리케이션의 `model` 값으로 사용한다.

## 5. OpenAI 호환 API 사용법

### 5.1 기본 주소

운영자가 안내한 AICONNECT 공개 주소를 사용한다.

```text
https://api.example.com/v1
```

로컬 환경의 기본 주소는 보통 다음과 같지만, 실제 운영 주소를 우선한다.

```text
http://localhost/v1
```

### 5.2 cURL 예시

```bash
curl "https://api.example.com/v1/chat/completions" \
  -H "Authorization: Bearer $AICONNECT_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "text-pro",
    "messages": [
      {"role": "system", "content": "답변은 간결한 한국어로 작성하세요."},
      {"role": "user", "content": "다음 문서를 세 문장으로 요약해 주세요."}
    ],
    "temperature": 0.3,
    "stream": false
  }'
```

### 5.3 JavaScript(OpenAI SDK) 예시

```ts
import OpenAI from 'openai'

const client = new OpenAI({
  apiKey: process.env.AICONNECT_API_KEY,
  baseURL: 'https://api.example.com/v1'
})

const completion = await client.chat.completions.create({
  model: 'text-pro',
  messages: [{ role: 'user', content: '회의록을 요약해 주세요.' }],
  temperature: 0.2
})

console.log(completion.choices[0]?.message.content)
```

### 5.4 Python(OpenAI SDK) 예시

```python
from openai import OpenAI
import os

client = OpenAI(
    api_key=os.environ["AICONNECT_API_KEY"],
    base_url="https://api.example.com/v1",
)

result = client.chat.completions.create(
    model="text-pro",
    messages=[{"role": "user", "content": "아래 요구사항을 JSON으로 정리해 주세요."}],
    temperature=0.2,
)

print(result.choices[0].message.content)
```

### 5.5 스트리밍

`stream: true`를 사용하면 SSE(Server-Sent Events)로 토큰이 순차 전달된다.

```json
{
  "model": "text-pro",
  "messages": [{"role": "user", "content": "긴 답변을 작성해 주세요."}],
  "stream": true,
  "stream_options": {"include_usage": true}
}
```

스트리밍이 일부 토큰을 전달한 뒤 Runtime 장애가 발생하면, 응답을 다른 모델이 이어서 작성하지 않는다. 중복 문장, 깨진 JSON, 다른 문맥의 답변을 방지하기 위한 정책이다. 같은 요청을 새 요청으로 다시 시도한다.

## 6. 모델 선택 원칙

1. `GET /v1/models`에 표시된 모델만 사용한다.
2. 특정 GPU, IP 주소, LM Studio 모델 파일명을 코드에 넣지 않는다.
3. 성능과 품질 요구가 다르면 논리 모델을 분리한다. 예: `text-lite`, `text-pro`, `json-builder`.
4. JSON 형식이 중요한 작업은 관리자에게 Structured Output 지원 모델/서비스를 요청한다.
5. 모델 품질이 중요한 업무는 대체 모델 사용 허용 여부를 관리자와 미리 합의한다.

## 7. API 키 보안

### 반드시 할 일

- 환경 변수, 비밀 저장소, CI/CD Secret에 키를 저장한다.
- 개발·운영·개인 실험 키를 분리한다.
- 사람 또는 서비스 단위로 키 이름을 구분한다.
- 노출이 의심되면 즉시 키를 폐기한다.

### 절대 하지 말 것

- 프론트엔드 JavaScript에 키를 직접 넣기
- Git 커밋, 이슈, 채팅, 스크린샷에 키 포함
- 여러 서비스가 하나의 장기 키를 공유
- LM Studio 내부 토큰을 사용자 애플리케이션에 사용

## 8. 사용량, 비용, 알림

### 8.1 사용량 화면

**사용량** 메뉴에서 다음을 확인한다.

- 기간별 요청 수
- 입력·출력 토큰
- 예상 비용
- 성공/실패 수
- 요청 지연 시간
- 사용한 논리 모델과 실제 Failover 여부

예상 비용은 프로젝트에 연결된 논리 서비스의 가격 정책을 기준으로 계산된다. 장애로 대체 Runtime이 선택되어도 사용자의 단가가 임의로 바뀌지 않도록 설계되어 있다.

### 8.2 알림

프로젝트 소유자는 RPM, 오류율, 월간 토큰 사용률에 대한 알림 정책을 설정할 수 있다. 알림 수신은 조직 관리자가 구성한 Discord 또는 Telegram 채널로 전달된다.

알림을 받은 뒤에는 다음 순서로 확인한다.

1. 사용량 화면에서 급증 시점과 모델을 확인한다.
2. 요청 탐색기에서 프로젝트와 API 키 Prefix로 필터링한다.
3. 의도하지 않은 키 사용이면 즉시 폐기한다.
4. 정상적인 트래픽 증가라면 관리자에게 할당량 또는 Runtime 증설을 요청한다.

## 9. 프롬프트·응답 보관 정책

프로젝트마다 보관 정책이 다를 수 있다.

| 정책 | 의미 |
|---|---|
| `NONE` | 원문을 보관하지 않음 |
| `METADATA_ONLY` | 토큰, 모델, 상태, 시간만 보관 |
| `MASKED` | 지정된 민감정보를 마스킹한 원문 보관 |
| `FULL_ENCRYPTED` | 암호화한 프롬프트와 응답 보관 |

기본적으로 원문을 저장하지 않는 정책을 권장한다. `FULL_ENCRYPTED` 프로젝트라도 권한이 있는 관리자만 원문을 열람할 수 있고, 열람은 감사 대상이다.

## 10. 자주 발생하는 오류

| HTTP | 코드 예시 | 사용자 조치 |
|---|---|---|
| 401 | API 키 오류 또는 만료 | 올바른 키인지 확인, 새 키 발급 또는 관리자 문의 |
| 403 | `MODEL_NOT_ALLOWED` | 프로젝트에 해당 논리 모델 권한 요청 |
| 429 | Rate Limit 또는 토큰 한도 초과 | 재시도 간격을 늘리고 사용량/할당량 확인 |
| 503 | `MODEL_UNAVAILABLE` | 잠시 후 재시도, 지속되면 관리자에게 Runtime 상태 문의 |
| 504 | Runtime 응답 시간 초과 | 요청 크기·최대 출력 토큰 축소 후 재시도 |

재시도는 지수 백오프를 사용한다. 쓰기 작업이나 외부 부작용이 있는 프롬프트는 응답이 불확실할 때 무조건 재시도하지 않는다.

## 11. AI 작업 보조자용 실행 규칙

AI가 사용자의 AICONNECT 사용을 돕는 경우 다음 규칙을 지킨다.

### 입력 확인

- 목표 프로젝트, 사용할 논리 모델, 실행 환경(개발/운영)을 먼저 식별한다.
- API 키 원문, 비밀번호, LM Studio 토큰을 답변이나 로그에 재출력하지 않는다.
- 사용자가 모델명을 모르면 `/v1/models` 결과를 먼저 요청하거나 확인한다.

### 안전한 작업 순서

```text
프로젝트 확인
→ 모델 권한 확인
→ API 키 발급 또는 기존 키 사용 여부 확인
→ 환경 변수에 키 주입
→ 최소 요청으로 연결 테스트
→ 사용량과 오류 확인
```

### AI가 해도 되는 일

- API 호출 예제 작성
- SDK의 `baseURL`을 AICONNECT 주소로 변경
- 사용량·오류 원인 요약
- 키 노출 여부 점검
- 관리자에게 전달할 장애 보고 초안 작성

### AI가 하면 안 되는 일

- 사용자의 API 키나 비밀번호를 임의로 생성·공개·저장
- Runtime 주소를 사용자 코드에 하드코딩
- 권한 없는 모델을 우회 호출
- API 키 폐기, 프로젝트 삭제, 알림 정책 변경을 사용자 확인 없이 실행
- 프롬프트 원문을 권한 없이 열람하거나 다른 사용자에게 전달

## 12. 사용자 완료 점검표

- [ ] 올바른 조직과 프로젝트를 선택했다.
- [ ] 목적에 맞는 API 키를 발급하고 안전하게 보관했다.
- [ ] `/v1/models`에서 모델 접근 권한을 확인했다.
- [ ] OpenAI SDK의 `baseURL`과 API 키를 AICONNECT 값으로 변경했다.
- [ ] 비스트리밍 요청으로 먼저 연결을 확인했다.
- [ ] 운영 키와 개발 키를 분리했다.
- [ ] 사용량·알림·보관 정책을 확인했다.

## 13. 추가 문서

- [관리자 운영 가이드](ADMIN_GUIDE_KO.md)
- [인증 및 운영](auth-and-operations.md)
- [라우팅 정책 관리](routing-policy-management.md)
- [장애 전환 운영](failover-operations.md)
- [요청 보관 정책](request-retention.md)
