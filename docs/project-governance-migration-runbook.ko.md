# AIConnect 프로젝트 거버넌스·신뢰 경계·비용 한도 운영 이행 런북 (`V43` → `V50`)

## 1. 목적 및 변경 범위
본 문서는 AIConnect LLM Gateway의 3계층 계정 체계(`SYSTEM_ADMIN` / `PROJECT_DIRECTOR` / `GENERAL_USER`), TOTP 2차 인증, 프로젝트 단위 스코프 분리, Runtime 보안 분류·승인, 계층별 데이터 경계 교집합(`Project ∩ LlmService ∩ ApiKey`), API 키 발급 위임 및 권한 회수 연쇄 무효화, 그리고 통화 분리 기반 Provider 월 한도·HA 예약 정산(`V43`~`V50`)을 운영 환경에 안전하게 이행하고 롤백하기 위한 표준 절차를 정의합니다.

| Flyway 버전 | 주요 스키마 변경 | 기존 데이터 보수적 기본값 / 백필 |
|---|---|---|
| `V43` | `app_user` 계층·TOTP 컬럼, `auth_refresh_token.mfa_verified`, `account_invitation`, `user_totp_recovery_code` | `platform_admin=TRUE` → `SYSTEM_ADMIN`, `ORGANIZATION_ADMIN` → `PROJECT_DIRECTOR`, 나머지 `GENERAL_USER` (`totp_enabled=FALSE`) |
| `V44` | `project_membership` (`MANAGER` / `MEMBER`) | 수락 완료된 프로젝트 초대 기반 명시적 멤버십만 생성(팀/조직 권한 추론 금지) |
| `V45` | `runtime_trust_attestation` (URL 바인딩, 만료, 철회) | 기존 `runtime_endpoint`는 미분류(`UNCLASSIFIED`) 상태 → **Fail-closed 차단** |
| `V46` | `project_runtime_approval` (프로젝트별 `manual`/`failover` 승인) | 기존 프로젝트는 승인 없음 → **Fail-closed 차단** |
| `V47` | `connection_registration_request` (연결 신청/결정 큐) | 신규 테이블(초기 0건) |
| `V48` | `app_user.project_scope_enforced` | `project_membership` 보유 계정은 `TRUE` |
| `V49` | `project_external_access.monthly_cost_currency`, `project_external_access_reservation`, `spend_quota.admin_ceiling`, `spend_quota_reservation` 4상태, `project_quota` 관리자 상한 | 기존 `spend_quota` → `admin_ceiling=TRUE`; 기존 `monthly_cost_limit IS NOT NULL` 레코드는 `monthly_cost_currency=NULL` 유지 → **통화 지정 전까지 Fail-closed 차단** |
| `V50` | `project.(max_)data_boundary_policy`, `llm_service.data_boundary_policy`, `api_key.data_boundary_policy`, `project_membership.can_issue_api_keys`, `account_invitation.can_issue_api_keys`, `runtime_trust_attestation` 메타데이터 4종 | `external_ai_blocked=TRUE` → `INTERNAL_ONLY` / `INTERNAL_ONLY`; `MANAGER` → `can_issue_api_keys=TRUE`, `MEMBER` → `FALSE` |

---

## 2. 사전 점검(Pre-flight) 체크리스트 및 DB 백업

1. **스냅샷 & 논리 백업 확보 (필수)**:
   - MariaDB 11.4 기준 전체 스키마·데이터 논리 백업(`mariadb-dump --single-transaction --routines --triggers`) 및 스토리지 볼륨 스냅샷을 생성합니다.
   - 백업 파일의 무결성(복원 드라이런 또는 체크섬)을 확인한 뒤 다음 단계로 진행합니다.
2. **현재 Flyway 버전 및 미완료 마이그레이션 확인**:
   ```sql
   SELECT installed_rank, version, description, type, installed_on, success
   FROM flyway_schema_history
   ORDER BY installed_rank DESC
   LIMIT 20;
   ```
   - `success = 0` 인 실패 이력이 없는지 확인합니다.
3. **관리자 TOTP 및 Break-Glass 경로 확인**:
   - 배포 전 `SYSTEM_ADMIN` 계정이 최소 1개 이상 존재하며, 로그인 후 TOTP 온보딩을 즉시 완료할 수 있는지 확인합니다.
   - 비상 복구용 `X-Admin-Token`은 내부망/점검 시에만 제한적으로 사용하며 외부 노출을 금지합니다.

---

## 3. 사전 감사 SQL 쿼리 (이행 전·직후 실행)

### 3.1 계정 계층 및 프로젝트 스코프 현황
```sql
SELECT account_tier, enabled, totp_enabled, project_scope_enforced, COUNT(*) AS cnt
FROM app_user
GROUP BY account_tier, enabled, totp_enabled, project_scope_enforced;

-- 조직 관리자 권한과 PROJECT_DIRECTOR 계층이 혼재된 레거시 계정 점검
SELECT u.id, u.email, u.account_tier, om.organization_id, om.role AS org_role
FROM app_user u
JOIN organization_member om ON om.user_id = u.id
WHERE u.account_tier = 'PROJECT_DIRECTOR' AND om.role = 'ORGANIZATION_ADMIN';
```
> 참고: `TeamAccessService`는 `PROJECT_DIRECTOR`의 `ORGANIZATION_ADMIN` 권한을 런타임에 항상 무효화하므로, 이 쿼리로 식별된 계정은 필요 시 `project_membership(MANAGER)`로 명시 할당해야 합니다.

### 3.2 사내·외부 Runtime Endpoint 신뢰 분류 및 프로젝트 승인 누락 점검 (중요)
> **주의**: `V45`+`V46` 이후 모든 `runtime_endpoint`는 `runtime_trust_attestation`과 `project_runtime_approval`이 없으면 **Gateway와 Playground가 Fail-closed로 트래픽을 차단**합니다. 트래픽 개방 전 기존 사내 GPU를 반드시 `INTERNAL`로 선분류하고 프로젝트별 승인을 등록해야 합니다.

```sql
-- 미분류 또는 만료·철회된 Runtime Endpoint 목록
SELECT re.id AS endpoint_id, n.organization_id, n.name AS node_name, re.display_name, re.runtime_type, re.base_url,
       rta.classification, rta.expires_at, rta.revoked_at
FROM runtime_endpoint re
JOIN inference_node n ON n.id = re.node_id
LEFT JOIN runtime_trust_attestation rta ON rta.endpoint_id = re.id
WHERE re.enabled = TRUE
  AND (rta.endpoint_id IS NULL
       OR rta.revoked_at IS NOT NULL
       OR (rta.expires_at IS NOT NULL AND rta.expires_at <= CURRENT_TIMESTAMP));

-- 활성 프로젝트 중 사내(INTERNAL) 또는 비보존(EXTERNAL_NO_RETENTION) Runtime 승인이 없는 프로젝트 점검
SELECT p.id AS project_id, p.name AS project_name, re.id AS endpoint_id, re.display_name, rta.classification
FROM project p
JOIN inference_node n ON n.organization_id = p.organization_id
JOIN runtime_endpoint re ON re.node_id = n.id AND re.enabled = TRUE
JOIN runtime_trust_attestation rta ON rta.endpoint_id = re.id AND rta.revoked_at IS NULL
LEFT JOIN project_runtime_approval pra ON pra.project_id = p.id AND pra.endpoint_id = re.id
WHERE p.status = 'ACTIVE' AND pra.id IS NULL;
```

### 3.3 외부 Provider 월 비용 한도 통화 누락 레코드 점검 (`V49` Fail-closed 대상)
> **주의**: `V49`에서 `monthly_cost_limit IS NOT NULL AND monthly_cost_currency IS NULL`인 기존 승인 레코드는 묵시 환율 변환 없이 `409 EXTERNAL_PROVIDER_CAP_RESERVATION_UNAVAILABLE`로 차단됩니다.

```sql
SELECT pea.project_id, p.name AS project_name, pea.provider_id, ep.display_name AS provider_name,
       pea.status, pea.monthly_cost_limit, pea.monthly_cost_currency, pea.expires_at
FROM project_external_access pea
JOIN project p ON p.id = pea.project_id
JOIN external_provider ep ON ep.id = pea.provider_id
WHERE pea.status = 'APPROVED'
  AND pea.monthly_cost_limit IS NOT NULL
  AND pea.monthly_cost_currency IS NULL;
```
- 조치: `PATCH /api/admin/projects/{projectId}/external-access/{providerId}` 또는 운영자 확인 후 명시적 통화(`KRW` 또는 `USD`)를 지정하십시오(임의 추측·자동 환산 금지).

### 3.4 프로젝트 데이터 경계·서비스·API 키·위임 백필 검증 (`V50` 이후)
```sql
-- V50 백필 정합성 확인: external_ai_blocked=TRUE 프로젝트가 모두 INTERNAL_ONLY인지 검증 (0건이어야 정상)
SELECT id, name, external_ai_blocked, data_boundary_policy, max_data_boundary_policy
FROM project
WHERE external_ai_blocked = TRUE
  AND (data_boundary_policy <> 'INTERNAL_ONLY' OR max_data_boundary_policy <> 'INTERNAL_ONLY');

-- 프로젝트 멤버십 및 API 키 발급 위임 상태 점검
SELECT pm.project_id, p.name AS project_name, u.email, pm.role, pm.can_issue_api_keys
FROM project_membership pm
JOIN project p ON p.id = pm.project_id
JOIN app_user u ON u.id = pm.user_id
ORDER BY p.name, pm.role, u.email;

-- 상한이 없는 기존 ProjectQuota 및 admin_ceiling 설정된 SpendQuota 확인
SELECT pq.project_id, p.name, pq.requests_per_minute, pq.max_requests_per_minute,
       pq.monthly_token_limit, pq.max_monthly_token_limit
FROM project_quota pq
JOIN project p ON p.id = pq.project_id;

SELECT id, organization_id, scope_type, scope_id, name, currency, period, limit_amount, admin_ceiling, enabled
FROM spend_quota
ORDER BY organization_id, scope_type;
```

---

## 4. 단계적 롤아웃 순서 (Staging → Production)

### Phase 1: 스테이징 리허설
1. 운영 스냅샷(마스킹/비식별화 완료) 또는 동일 스키마 버전 DB를 스테이징에 복원합니다.
2. 새 백엔드 바이너리를 기동하거나 Flyway CLI로 `V43` → `V50` 마이그레이션을 실행하고 실패가 0건인지 확인합니다.
3. 위 3장의 감사 쿼리를 실행하여 데이터 보정 대상을 목록화합니다.
4. 새 프런트엔드 빌드(`npm run build`)와 함께 다음 핵심 시나리오를 검증합니다:
   - `SYSTEM_ADMIN` TOTP 등록 및 기존 사내 GPU `INTERNAL` 선분류
   - `PROJECT_DIRECTOR` 로그인 후 `ProjectsPage`, `QuotaPage`, `DataProtectionPage` 진입 시 HTTP 403 오류 0건
   - `INTERNAL_ONLY` / `APPROVED_EXTERNAL_NO_RETENTION` / `EXTERNAL_ALLOWED` 교집합 라우팅 및 상향 완화 거부(`403 DATA_BOUNDARY_RELAXATION_DENIED`)
   - Provider 월 한도 동시성 예약 및 미정산 스트림 중단 시 `HELD_UNSETTLED` 유지 확인

### Phase 2: 운영 유지보수 창 / 트래픽 제어 전환
1. **게이트웨이/프런트엔드 트래픽 제어**:
   - 점검 공지 또는 로드밸런서에서 신규 요청을 일시 드레인(또는 무중단 롤링 시 구버전 트래픽 차단 후 DB 마이그레이션 선행).
   - 주의: `V49`/`V50`은 모두 **Additive DDL**이나, `V45`/`V46` 선분류 작업 전에는 구버전/신버전 혼재 시 신뢰 검증 불일치가 발생할 수 있으므로 백엔드 인스턴스를 순차 교체하기 전에 DB 마이그레이션과 선분류를 완료하는 것을 권장합니다.
2. **DB 마이그레이션 적용**:
   - 백업 완료 재확인 후 `V43`~`V50` Flyway 마이그레이션 적용.
   - `flyway_schema_history`의 최신 버전이 `50`이고 `success = 1`인지 확인.
3. **트래픽 개방 전 필수 선행 설정 (System Admin 수행)**:
   - **(필수 1) 기존 사내 GPU 선분류**: 확인된 모든 사내 `runtime_endpoint`에 `PUT /api/admin/platform/runtime-endpoints/{endpointId}/trust` (`classification: "INTERNAL"`) 등록.
   - **(필수 2) 프로젝트별 Runtime 승인**: 활성 프로젝트에 대해 `PUT /api/admin/platform/projects/{projectId}/runtime-approvals/{endpointId}`로 사내 GPU 사용 승인(`manualAllowed: true`, 만료일 설정).
   - **(필수 3) 외부 비보존 GPU 메타데이터 보완**: `EXTERNAL_NO_RETENTION` GPU는 `vendorName`, `processingLocation`, `retentionPolicy`, `approvalScope`, 미래 `expiresAt` 5종을 모두 등록 후 프로젝트 승인.
   - **(필수 4) 레거시 Provider Cap 통화 지정**: 3.3 쿼리에서 조회된 `monthly_cost_limit IS NOT NULL AND monthly_cost_currency IS NULL` 항목에 명시적 `monthlyCostCurrency`(`KRW` 또는 `USD`) 지정.
   - **(필수 5) 프로젝트 관리자 및 API 키 위임 확인**: 기존 `PROJECT_DIRECTOR`가 담당 프로젝트의 `MANAGER`로 할당(`PUT /api/admin/platform/projects/{projectId}/managers/{userId}`)되어 있는지, 키 발급이 필요한 `GENERAL_USER` 멤버에게 `canIssueApiKeys=true`가 부여되었는지 확인.
   - **(필수 6) 프로젝트 경계 및 Quota 상한 확인**: 필요 시 `PATCH /api/admin/projects/{projectId}`로 `maxDataBoundaryPolicy`를, `PUT /api/admin/platform/projects/{projectId}/quota-ceiling`으로 RPM/월 토큰 상한을 설정.
4. **백엔드 & 프런트엔드 배포 및 스모크 검증**:
   - 새 백엔드 기동 → `/actuator/health` 정상 확인.
   - 새 프런트엔드 정적 번들 배포 → 브라우저 콘솔/네트워크 탭에서 `PROJECT_DIRECTOR` 화면 403 크래시 0건 확인.
   - 사내 GPU 기반 테스트 API 키 1건 호출(`200 OK`) 및 외부 전송 차단 프로젝트의 외부 우회 차단(`LOCAL_MODEL_UNAVAILABLE`) 확인.

---

## 5. 안전한 롤백 절차

> **원칙**: `audit_log`, `llm_request`, `llm_request_attempt`, 그리고 미정산 비용 보호를 위한 `HELD_UNSETTLED` 예약 레코드는 롤백 시에도 **절대 삭제(DROP/TRUNCATE)하지 않습니다**.

### 시나리오 A: 애플리케이션 회귀 발생 시 (권장 — 무손실 바이너리 롤백)
`V49`와 `V50`의 모든 스키마 변경은 **Additive 컬럼/테이블 추가**이며 기존 테이블을 삭제하거나 파괴적으로 변환하지 않습니다.
1. 트래픽 차단 또는 드레인 후 백엔드 및 프런트엔드 바이너리를 직전 정상 버전으로 롤백합니다.
2. 신규 컬럼(`data_boundary_policy`, `max_data_boundary_policy`, `can_issue_api_keys`, `monthly_cost_currency`, `admin_ceiling`, `max_requests_per_minute`, `max_monthly_token_limit`, `vendor_name` 등)은 `DEFAULT` 값 또는 `NULL` 허용이므로, 스키마를 되돌리지 않아도 구버전 애플리케이션이 정상 동작할 수 있는지 사전 스테이징에서 검증한 범위 내에서 그대로 유지합니다.
   - 단, `V49`에서 `spend_quota_reservation` / `project_external_access_reservation`이 추가/확장되었으므로, 구버전으로 내릴 경우 동시성/비용 한도 보호 기능이 구버전 수준으로 축소됨을 인지하고 가능한 한 긴급 패치(Forward Fix)를 우선 고려합니다.

### 시나리오 B: DB 스키마까지 완전 복구해야 하는 중대 장애 시
Flyway는 다운그레이드(`undo`) 스크립트를 자동 실행하지 않으므로 스냅샷 기반으로 복구합니다.
1. 애플리케이션 인스턴스를 모두 중지(쓰기 트래픽 완전 차단)합니다.
2. 롤백 직전 시점의 감사 로그(`audit_log`)와 요청 이력(`llm_request`)이 보존되도록 별도 아카이브 덤프를 먼저 떠둡니다.
3. Phase 0(2장)에서 생성한 **마이그레이션 직전 DB 백업/스냅샷**으로 복원합니다.
4. 직전 버전 백엔드/프런트엔드 바이너리를 기동하고 `flyway_schema_history`와 헬스체크를 검증한 뒤 트래픽을 재개합니다.

---

## 6. 운영 중 긴급 보안 대응 치트시트
- **외부 GPU 즉시 차단**: `POST /api/admin/platform/runtime-endpoints/{endpointId}/trust/revoke` 실행 → 진행 중이던 다음 failover 시도(IMMEDIATE 모드 포함)부터 즉시 차단.
- **특정 프로젝트 관리자/멤버 즉시 박탈**:
  - 관리자: `DELETE /api/admin/platform/projects/{projectId}/managers/{userId}`
  - 일반 멤버: `DELETE /api/admin/projects/{projectId}/members/{userId}`
  - 효과: 멤버십 제거(`project_scope_enforced=TRUE` 유지) + 해당 프로젝트에서 해당 사용자가 발급한 모든 `ACTIVE` API 키 즉시 폐기 + 대기 초대 취소 + 리프레시 토큰 폐기.
- **퇴사자/침해 계정 전역 오프보딩**: 플랫폼 사용자 비활성화(`enabled = false`) 실행 → 모든 리프레시 토큰 및 해당 사용자가 발급한 **전 프로젝트의 모든 `ACTIVE` API 키 즉시 폐기**.
