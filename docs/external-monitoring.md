# 외부 Prometheus·Grafana 연결

기존 모니터링 서버가 있다면 AIConnect에 포함된 Prometheus와 Grafana를 중복 실행하지 않고 Gateway의 관리 포트만 안전한 사내망 또는 Tailnet에 노출할 수 있습니다.

## Standalone Gateway

`.env`에 모니터링 서버가 접근할 Gateway 주소와 전용 포트를 지정합니다.
아래 `192.0.2.10`은 문서용 예시이므로 실제 Gateway의 LAN 또는 Tailscale IP로 교체합니다.

```dotenv
AICONNECT_METRICS_BIND_ADDRESS=192.0.2.10
AICONNECT_METRICS_PORT=18081
```

다음 오버레이를 함께 적용합니다. `prometheus`와 `grafana`는 `bundled-monitoring` 프로필로 분리되므로 기본 실행 대상에서 제외됩니다.

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.external-monitoring.yml \
  up -d --build mariadb api frontend nginx
```

Gateway readiness와 메트릭을 확인합니다.

```bash
curl -fsS http://192.0.2.10:18081/actuator/health/readiness
curl -fsS http://192.0.2.10:18081/actuator/prometheus | head
```

## 단일 호스트 HA Gateway

HA 프로필은 `api-1`, `api-2`가 동시에 실행되므로 관리 포트도 서로 달라야 합니다.

```dotenv
AICONNECT_METRICS_BIND_ADDRESS=192.0.2.10
AICONNECT_METRICS_PORT_1=18081
AICONNECT_METRICS_PORT_2=18082
```

```bash
docker compose --env-file deploy/ha/.env \
  -f deploy/ha/docker-compose.yml \
  -f deploy/ha/docker-compose.external-monitoring.yml \
  up -d --build mariadb redis api-1 api-2 frontend load-balancer
```

두 인스턴스가 독립적으로 준비되었는지 확인합니다.

```bash
curl -fsS http://192.0.2.10:18081/actuator/health/readiness
curl -fsS http://192.0.2.10:18082/actuator/health/readiness
```

## 외부 Prometheus

```yaml
scrape_configs:
  - job_name: aiconnect-gateway
    scrape_interval: 15s
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - 192.0.2.10:18081
        labels:
          application: aiconnect
          environment: production
```

HA에서는 같은 job에 두 Target을 등록하고 인스턴스 라벨을 구분합니다.

```yaml
      - targets: ["192.0.2.10:18081"]
        labels:
          application: aiconnect
          environment: production
          instance_id: gateway-1
      - targets: ["192.0.2.10:18082"]
        labels:
          application: aiconnect
          environment: production
          instance_id: gateway-2
```

설정 검사 후 Prometheus를 재시작하거나 `/-/reload`를 호출합니다.

```bash
promtool check config /etc/prometheus/prometheus.yml
curl -X POST http://127.0.0.1:9090/-/reload
```

## Grafana

`infra/grafana/dashboards/aiconnect-gateway-overview.json`을 기존 Grafana의 파일 프로비저닝 디렉터리에 복사합니다. 데이터소스 UID는 `Prometheus`여야 합니다.

검증 쿼리:

```promql
up{job="aiconnect-gateway"}
```

## 권장 Alert 규칙

`infra/prometheus/aiconnect-alerts.yml`에는 다음 기준이 포함됩니다.

- Gateway 인스턴스 하나가 1분 동안 수집되지 않으면 `warning`
- 모든 Gateway가 30초 동안 수집되지 않으면 `critical`
- 5분 오류율이 10%를 넘는 상태가 5분 유지되면 `warning`

기존 Prometheus의 `rule_files` 경로에 파일을 추가하고 `promtool check rules`와 `promtool check config`를 모두 통과한 뒤 재적용합니다. 인스턴스 장애 경고와 전체 중단 경고를 구분해야 HA가 일부 용량으로 동작 중인 상태를 놓치지 않습니다.

## 보안

- 관리 포트를 인터넷 전체에 공개하지 않습니다.
- 방화벽 또는 Tailscale ACL로 Prometheus 서버만 관리 포트에 접근하도록 제한합니다.
- `/actuator/prometheus`에는 API 키·프롬프트·응답 원문을 라벨로 기록하지 않습니다.
- 정확한 요청·토큰·비용 원장은 MariaDB를 사용하고 Prometheus는 시스템 추세 감시에만 사용합니다.
