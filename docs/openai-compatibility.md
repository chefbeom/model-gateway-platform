# 외부 OpenAI 호환 요청 처리

AICONNECT의 공개 API는 OpenAI Chat Completions 형식을 그대로 사용합니다. 따라서 호출하는 애플리케이션은 `model`, `messages`, `stream`, `response_format` 등을 기존 OpenAI SDK 방식으로 보낼 수 있습니다.

## 토큰 제한 필드

호출 애플리케이션이 기존 호환 클라이언트처럼 다음 필드를 보내도 됩니다.

```json
{
  "model": "text-pro-openai-api",
  "messages": [{"role": "user", "content": "분석해줘"}],
  "max_tokens": 4096,
  "stream": false
}
```

AICONNECT는 요청 대상이 외부 OpenAI Provider일 때만 `max_tokens`를 `max_completion_tokens`로 변환한 뒤 upstream에 전달합니다. 변환은 복사본에서 수행하므로 사용자의 원본 요청과 로컬 LM Studio 요청은 변경되지 않습니다.

```json
{
  "max_completion_tokens": 4096
}
```

호출자가 이미 `max_completion_tokens`를 보낸 경우에는 그 값을 유지합니다. 두 필드를 동시에 보낸 경우에도 upstream에는 `max_completion_tokens` 하나만 전달합니다.

## 로컬 LM Studio와의 차이

논리 모델의 Target이 LM Studio이면 요청을 그대로 전달합니다. 따라서 LM Studio에서 사용하는 기존 `max_tokens` 호출은 수정 없이 계속 사용할 수 있습니다. 외부 OpenAI Provider Target이면 위의 정규화가 적용됩니다.

## Structured Output

JSON 응답이 필요한 경우에는 OpenAI 형식의 `response_format.type=json_schema`를 사용합니다. 외부 Provider가 요구하는 엄격한 스키마는 호출 애플리케이션이 명시해야 합니다.

```json
{
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "ledger_result",
      "strict": true,
      "schema": {
        "type": "object",
        "properties": {
          "amount": {"type": "number"},
          "title": {"type": "string"}
        },
        "required": ["amount", "title"],
        "additionalProperties": false
      }
    }
  }
}
```

## 확인 방법

1. `/v1/models`에서 논리 모델 ID를 확인합니다.
2. AICONNECT API Key로 `/v1/chat/completions`를 호출합니다.
3. 외부 Provider 요청은 사용량과 실제 Provider가 관측 화면에 기록되는지 확인합니다.
4. `max_tokens`를 사용한 레거시 요청과 `max_completion_tokens`를 사용한 요청을 각각 테스트합니다.

직접 Provider를 호출할 때 성공하더라도 AICONNECT 경유 요청이 실패하면, 먼저 프로젝트의 논리 서비스 Target·Provider 권한·수동 사용 설정을 확인합니다. AICONNECT는 upstream HTTP 오류를 요청 이력에 `UPSTREAM_REJECTED`로 기록하므로, 해당 요청의 상세 Attempt와 Provider 상태를 함께 확인해야 합니다.
