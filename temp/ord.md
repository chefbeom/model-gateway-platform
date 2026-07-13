# AICONNECT Standalone VM 설치 명령 기록

## 1. 설치 대상과 결과

- 설치 일자: 2026-07-13
- VM: `192.168.35.111`
- OS: Ubuntu Server 22.04.4 LTS (`x86_64`)
- 설치 모드: 신뢰된 내부망 HTTP Standalone
- 관리 화면: `http://192.168.35.111`
- OpenAI 호환 Base URL: `http://192.168.35.111/v1`
- 설치 디렉터리: `/home/test/aiconnect`
- 최초 관리자 이메일: `admin@aiconnect.local`
- 최초 관리자 비밀번호 보관 위치: `/home/test/aiconnect-initial-admin.env`
- Tailscale: 클라이언트와 서비스 설치 완료, Tailnet 로그인은 미실행

설치 완료 시 다음 컨테이너 6개가 모두 `healthy`였다.

```text
aiconnect-api-1
aiconnect-frontend-1
aiconnect-grafana-1
aiconnect-mariadb-1
aiconnect-nginx-1
aiconnect-prometheus-1
```

검증 결과:

```text
웹 화면:                 HTTP 200
미인증 GET /v1/models: HTTP 401
관리자 로그인:           HTTP 200
Backend readiness:       UP
Tailscale 상태:           Logged out
```

> 이 문서에는 SSH 비밀번호, 관리자 비밀번호, API 키, `.env` 비밀값을 기록하지 않는다.

## 2. 로컬 PC에서 SSH 연결 확인

PowerShell에서 실행했다.

```powershell
Test-NetConnection 192.168.35.111 -Port 22
ssh test@192.168.35.111
```

확인한 VM 정보:

```bash
id
grep '^PRETTY_NAME=' /etc/os-release
uname -m
df -h /
grep MemTotal /proc/meminfo
command -v docker || true
command -v tailscale || true
```

초기 상태는 약 8GB RAM, 루트 디스크 여유 약 16GB였고 Docker와 Tailscale은 설치되지 않은 상태였다.

## 3. 현재 로컬 main 소스 패키징

원격 GitHub 브랜치 대신 작업 PC의 현재 `main` 스냅샷을 그대로 설치하기 위해 실행했다.

```powershell
cd C:\Users\kjs99\Desktop\aiconnect
git status --short --branch
git archive --format=tar.gz -o temp\aiconnect-main.tar.gz main
scp temp\aiconnect-main.tar.gz test@192.168.35.111:/home/test/aiconnect-main.tar.gz
```

GitHub의 원격 `main`을 직접 내려받아 설치할 때는 다음 방식도 사용할 수 있다.

```bash
wget -O aiconnect-main.tar.gz \
  https://github.com/chefbeom/model-gateway-platform/archive/refs/heads/main.tar.gz
tar -xzf aiconnect-main.tar.gz
cd model-gateway-platform-main
```

## 4. VM에서 소스 압축 해제

```bash
mkdir -p /home/test/aiconnect
tar -xzf /home/test/aiconnect-main.tar.gz -C /home/test/aiconnect
chmod +x /home/test/aiconnect/quickstart_standalone.sh
chmod +x /home/test/aiconnect/deploy/fullsetting_quickstart_standingalone.sh
```

## 5. 최초 관리자 자격 증명 준비

비밀번호를 명령 기록에 직접 넣지 않기 위해 VM에서 무작위로 생성하고 소유자만 읽을 수 있는 파일에 저장했다.

```bash
credential_file=/home/test/aiconnect-initial-admin.env
umask 077
password="$(head -c 24 /dev/urandom | base64 | tr -d '\n')"
printf 'AICONNECT_ADMIN_EMAIL=admin@aiconnect.local\n' >"$credential_file"
printf 'AICONNECT_ADMIN_PASSWORD=%s\n' "$password" >>"$credential_file"
chmod 600 "$credential_file"
unset password
```

사용자가 최초 관리자 비밀번호를 확인할 때만 다음 명령을 실행한다.

```bash
cat /home/test/aiconnect-initial-admin.env
```

확인 후 로그인하고 즉시 프로필 설정에서 비밀번호를 변경하는 것을 권장한다. 이 파일은 안전한 암호 저장소에 옮긴 뒤 VM에서 삭제할 수 있다.

## 6. Docker, Tailscale, AICONNECT 전체 자동 설치

다음 명령으로 기본 패키지, Docker Engine, Docker Compose v2, Tailscale 클라이언트와 AICONNECT 전체 컨테이너를 설치했다.

```bash
cd /home/test/aiconnect
set -a
. /home/test/aiconnect-initial-admin.env
set +a
sudo -E bash deploy/fullsetting_quickstart_standingalone.sh \
  --lan \
  --non-interactive
unset AICONNECT_ADMIN_EMAIL AICONNECT_ADMIN_PASSWORD
```

`--lan`은 Nginx를 VM의 내부 IP에 바인딩하고 다음 값을 적용한다.

```text
AICONNECT_HTTP_BIND_ADDRESS=192.168.35.111
AUTH_COOKIE_SECURE=false
GATEWAY_INTERNAL_BASE_URL=http://192.168.35.111/v1
```

이 모드는 신뢰된 사내망 전용이다. TCP 80을 인터넷에 포트 포워딩하면 안 된다.

## 7. Ubuntu apt 잠금이 발생했을 때

새 Ubuntu VM의 `unattended-upgrades`가 실행 중이어서 처음 두 차례 `apt` 잠금이 발생했다. 프로세스를 강제 종료하거나 잠금 파일을 삭제하지 않고 완료될 때까지 기다린 후 같은 설치 명령을 다시 실행했다.

이번 검증 후 `fullsetting_quickstart_standingalone.sh`에는 `DPkg::Lock::Timeout=600`과 최대 3회 재시도를 추가했다. 최신 스크립트는 대부분의 초기 자동 업데이트 충돌을 자체적으로 기다리며, 아래 명령은 제한 시간을 넘긴 경우의 수동 확인 절차다.

확인 명령:

```bash
pgrep -a -f 'apt|dpkg|unattended' || true
lslocks | grep -E 'dpkg|apt' || true
sudo fuser /var/lib/dpkg/lock-frontend || true
sudo fuser /var/lib/apt/lists/lock || true
```

실제 패키지 작업이 끝나 잠금 보유자가 없으면 6번의 설치 명령을 그대로 재실행한다. 다음 명령은 사용하지 않는다.

```text
sudo rm /var/lib/dpkg/lock-frontend
sudo kill -9 <unattended-upgrade PID>
```

## 8. LAN 최초 관리자 생성 오류와 수정

첫 배포에서는 컨테이너가 모두 정상 실행됐지만 최초 관리자 생성 요청이 `127.0.0.1:80`으로 전송되어 실패했다. LAN 모드 Nginx는 `192.168.35.111:80`에만 바인딩되어 있기 때문이다.

`quickstart_standalone.sh`의 관리자 생성 URL을 다음 정책으로 수정했다.

```bash
bootstrap_url="http://127.0.0.1/api/auth/bootstrap"
if ((LAN_MODE == 1)); then
  bootstrap_url="${serve_url}/api/auth/bootstrap"
fi
```

수정 후 실행한 검증과 재배포 명령:

```bash
bash -n /home/test/aiconnect/quickstart_standalone.sh

cd /home/test/aiconnect
set -a
. /home/test/aiconnect-initial-admin.env
set +a
./quickstart_standalone.sh --lan --non-interactive
unset AICONNECT_ADMIN_EMAIL AICONNECT_ADMIN_PASSWORD
```

기존 `.env`와 MariaDB 볼륨은 그대로 유지됐고 최초 관리자 생성이 완료됐다.

## 9. 설치 검증 명령

### 컨테이너와 Backend Health

```bash
cd /home/test/aiconnect
docker compose --env-file .env ps
docker compose --env-file .env exec -T api \
  curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

정상 readiness 응답:

```json
{"status":"UP"}
```

### 웹 화면과 API 인증 경계

```bash
curl -sS -o /dev/null -w 'ROOT_HTTP=%{http_code}\n' \
  http://192.168.35.111/

curl -sS -o /dev/null -w 'MODELS_HTTP=%{http_code}\n' \
  http://192.168.35.111/v1/models
```

기대 결과:

```text
ROOT_HTTP=200
MODELS_HTTP=401
```

`/v1/models`의 401은 장애가 아니라 프로젝트 API 키가 없을 때 요청을 차단하는 정상 동작이다.

### 관리자 로그인 검증

응답 본문의 Access Token은 화면에 출력하지 않고 HTTP 상태만 확인한다.

```bash
set -a
. /home/test/aiconnect-initial-admin.env
set +a

payload="$(printf '{\"email\":\"%s\",\"password\":\"%s\"}' \
  "$AICONNECT_ADMIN_EMAIL" "$AICONNECT_ADMIN_PASSWORD")"
response_file="$(mktemp)"
status="$(curl -sS -o "$response_file" -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  --data-binary "$payload" \
  http://192.168.35.111/api/auth/login)"
printf 'LOGIN_HTTP=%s\n' "$status"
rm -f "$response_file"
unset payload AICONNECT_ADMIN_EMAIL AICONNECT_ADMIN_PASSWORD
```

정상 결과:

```text
LOGIN_HTTP=200
```

### 디스크 사용량

```bash
df -h /
docker system df
```

설치 직후 루트 디스크 여유는 약 9.9GB였다. 운영 로그, DB 백업, 이미지 업데이트를 고려하면 디스크 확장을 권장한다.

## 10. Tailscale 인증 후 선택지

현재는 Tailscale 프로그램과 `tailscaled`만 설치됐으며 로그인하지 않았다.

```bash
sudo tailscale up
tailscale status
tailscale ip -4
```

### LAN 웹 주소를 계속 사용할 경우

`sudo tailscale up`만 실행한다. AICONNECT를 다시 배포할 필요는 없다.

- 관리/API 접근: `http://192.168.35.111`
- Gateway → GPU 서버: Tailscale IP 사용
- 인프라 화면에서 LM Studio 주소 예: `http://100.x.y.z:1234`

### AICONNECT 자체도 Tailnet HTTPS로 전환할 경우

```bash
sudo tailscale up
cd /home/test/aiconnect
./quickstart_standalone.sh --non-interactive
tailscale serve status
```

이 경우 Nginx는 loopback에 바인딩되고 접속 주소는 Tailscale Serve가 출력한 `https://<host>.<tailnet>.ts.net` 형태가 된다.

## 11. 운영 명령

```bash
cd /home/test/aiconnect

# 상태 확인
docker compose --env-file .env ps

# Gateway 로그
docker compose --env-file .env logs -f api

# 전체 로그 최근 200줄
docker compose --env-file .env logs --tail 200

# 재시작
docker compose --env-file .env restart

# 종료: 데이터 볼륨 유지
docker compose --env-file .env down

# 다시 시작 및 Health 대기
docker compose --env-file .env up -d --wait
```

## 12. 반드시 백업할 파일

```bash
cd /home/test/aiconnect
chmod 600 .env
```

VM 외부의 암호화 저장소에 다음 항목을 백업한다.

- `/home/test/aiconnect/.env`
- MariaDB 백업
- `API_KEY_PEPPER`
- `GATEWAY_ENCRYPTION_KEY`
- `AUTH_SIGNING_KEY`
- `AUTH_REFRESH_PEPPER`

`.env`를 잃어버린 상태에서 새 비밀값을 생성하면 기존 API 키와 암호화 데이터가 정상 동작하지 않을 수 있다.

## 13. 설치 임시 파일 정리

설치와 검증이 끝난 뒤 다음 임시 파일은 삭제할 수 있다.

```bash
rm -f /home/test/aiconnect-main.tar.gz
rm -f /home/test/prepare-vm-install.sh
rm -f /home/test/verify-vm-install.sh
```

초기 관리자 비밀번호를 별도 보관하고 변경했다면 다음 파일도 삭제할 수 있다.

```bash
rm -f /home/test/aiconnect-initial-admin.env
```
