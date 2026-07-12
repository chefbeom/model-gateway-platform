# 운영 전 준비와 승인 기준

이 문서는 AICONNECT를 실제 사용자에게 공개하기 전에 반드시 통과해야 할 항목을 정리한다. 코드·CI로 확인할 수 있는 항목과 운영자의 외부 인프라 확인을 구분한다.

## 1. 자동 품질 게이트

GitHub Actions의 `AICONNECT quality gate`가 다음 세 작업을 모두 통과해야 한다.

1. Java 17과 Gradle 8.10.2에서 Backend 전체 테스트 및 `bootJar` 생성
2. Node.js 22에서 Frontend TypeScript 검사 및 프로덕션 빌드
3. 기본·Tailscale·TLS Compose 계약 확인과 Backend/Frontend 이미지 빌드

Pull Request의 필수 상태 검사로 등록하고, 실패한 커밋은 `main`에 병합하지 않는다.

## 2. 비밀값과 복구 가능성

새 설치에서는 다음 명령으로 서로 다른 난수 비밀값을 생성한다.

```powershell
.\scripts\new-deployment-env.ps1
.\scripts\check-deployment-env.ps1 -RequireTailscale -RequireTls
```

기존 설치에서 `.env`가 사라졌다면 새 파일을 만들기 전에 반드시 기존 값을 복원한다. 특히 아래 값이 바뀌면 기존 데이터의 사용 가능성에 영향을 준다.

| 값 | 변경 시 영향 |
|---|---|
| `API_KEY_PEPPER` | 기존 프로젝트 API 키 인증 실패 |
| `GATEWAY_ENCRYPTION_KEY` | LM Studio Token, 알림 자격증명, 보관 원문 복호화 실패 |
| `AUTH_SIGNING_KEY` | 기존 Access Token 무효화 |
| `AUTH_REFRESH_PEPPER` | 기존 Refresh Token 무효화 |

`.env`, DB 백업, 비밀값 백업은 서로 분리된 암호화 저장소에 보관한다. 분기마다 격리 환경에서 복구 시험을 실행한다.

## 3. 네트워크와 TLS

- 사용자와 브라우저 관리자는 신뢰할 수 있는 인증서가 적용된 Gateway HTTPS 주소만 사용한다.
- LM Studio `1234` 포트는 인터넷에 공개하지 않는다.
- 직접 LAN, 라우팅된 사설망 또는 Tailscale 중 하나로 Gateway에서 Runtime Endpoint까지 연결한다.
- Tailscale 사용 시 Gateway 태그에서 GPU 노드의 Runtime 포트로 향하는 연결만 허용한다.
- Grafana는 기본 Compose에서 `127.0.0.1:3000`에만 바인딩한다. 원격 운영자는 SSH 터널 또는 별도 인증이 적용된 내부 프록시를 사용한다.
- Prometheus, MariaDB, Backend 컨테이너 포트는 Docker 내부 네트워크에만 둔다.

## 4. 실제 LM Studio 승인 시험

운영과 동일한 모델과 설정으로 아래를 확인한다.

1. Endpoint Probe와 모델 동기화 성공
2. `/v1/models`에서 실제 모델 ID가 아닌 논리 서비스 키 반환
3. 비스트리밍 Chat Completion 성공 및 Request/Attempt/토큰 저장
4. SSE 스트리밍 성공 및 응답의 물리 모델 ID 비노출
5. 첫 번째 GPU Runtime 중단 시 두 번째 물리 Runtime으로 전환
6. Primary 복구·워밍업 이후 신규 요청이 Primary로 복귀

실제 두 번째 GPU가 준비되기 전에는 단일 Runtime 운영으로만 승인하고, 자동 Failover가 검증되었다고 표시하지 않는다. 검증에는 `scripts/verify-tailnet-runtime.ps1`과 `scripts/verify-tailnet-failover.ps1`을 사용한다.

## 5. 운영 승인 체크리스트

- [ ] GitHub Actions의 세 작업 통과
- [ ] `.env` placeholder 없음 및 비밀값 별도 백업 완료
- [ ] MariaDB 백업 생성, SHA-256 기록, 격리 복구 성공
- [ ] 최종 도메인의 신뢰된 TLS 인증서 검증
- [ ] LM Studio Token 인증 활성화
- [ ] GPU 호스트 방화벽과 사설망 접근 정책 확인
- [ ] 실제 비스트리밍·SSE 요청과 관측 기록 확인
- [ ] 두 물리 Runtime Failover 확인 또는 단일 Runtime 제한 명시
- [ ] 실제 Discord/Telegram 장애·복구 알림 확인
- [ ] 프롬프트 보관 정책과 관리자 열람 권한 승인

체크리스트 결과에는 날짜, 담당자, 대상 버전과 실패 시 조치 링크를 남긴다. API 키, 비밀번호, Runtime Token, Tailscale Auth Key는 증적에 포함하지 않는다.
