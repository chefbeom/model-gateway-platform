# 외부 OpenAI API와 LM Studio 호환성

AICONNECT의 공개 API는 하나의 OpenAI Chat Completions 계약을 사용합니다.
요청자는 `model`, `messages`, `stream`, `response_format`, `tools`와 같은 표준 필드를 그대로 보낼 수 있습니다.

## 런타임별 처리

| 대상 | 처리 방식 |
| --- | --- |
| LM Studio Runtime | 요청 JSON을 변경하지 않고 `/v1/chat/completions`로 전달 |
| 외부 OpenAI Provider | 외부 Provider에 필요한 필드만 Gateway 경계에서 변환 |

따라서 기존 LM Studio 연동에서 사용하던 `max_tokens`와 샘플링 옵션을 유지하면서도,
외부 GPT-5/o-series 모델의 제약을 만족할 수 있습니다.

## 외부 Provider 변환 규칙

1. `max_tokens`는 `max_completion_tokens`로 옮깁니다. 두 필드가 모두 있으면 이미 표준인 `max_completion_tokens`가 우선합니다.
2. 외부 모델 ID가 `gpt-5`, `o1`, `o3`, `o4` 계열이거나 `reasoning_effort`를 사용하면 `temperature`, `top_p`, `presence_penalty`, `frequency_penalty`, `stop`, `logprobs`, `top_logprobs`를 제거합니다. 이 모델들은 해당 샘플링 필드를 거부할 수 있기 때문입니다.
3. `messages`, 이미지 입력, `response_format`, JSON Schema, `tools`, `stream`은 변경하지 않습니다.
4. 이 변환은 외부 OpenAI 런타임 클라이언트에서만 실행되며 LM Studio 요청에는 적용되지 않습니다.

예를 들어 애플리케이션이 다음처럼 보내도 됩니다.

```json
{
  "model": "text-pro-openai-api",
  "messages": [{"role": "user", "content": "분석해줘"}],
  "temperature": 0.2,
  "max_tokens": 4096,
  "stream": false
}
```

외부 Provider에 실제로 전송되는 모델이 `gpt-5.6-luna`라면 Gateway는 다음과 같이 안전하게 변환합니다.

```json
{
  "model": "gpt-5.6-luna",
  "messages": [{"role": "user", "content": "분석해줘"}],
  "max_completion_tokens": 4096,
  "stream": false
}
```

## 확인 방법

```powershell
$body = @{
  model = "text-pro-openai-api"
  messages = @(@{ role = "user"; content = "호환성 테스트" })
  temperature = 0.2
  max_tokens = 4096
  stream = $false
} | ConvertTo-Json -Depth 20

Invoke-RestMethod `
  -Uri "http://<AICONNECT_TAILNET_OR_PRIVATE_IP>/v1/chat/completions" `
  -Method Post `
  -Headers @{ Authorization = "Bearer <AICONNECT_API_KEY>" } `
  -ContentType "application/json; charset=utf-8" `
  -Body $body
```

성공 응답의 `usage.prompt_tokens`, `usage.completion_tokens`, `usage.total_tokens`가 채워지면
사용량 기록까지 정상적으로 완료된 것입니다. LM Studio 대상 요청에서는 같은 입력의 `max_tokens`와
샘플링 옵션이 그대로 전달되는 것을 회귀 테스트로 보장합니다.
