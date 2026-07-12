# Linux VM Standalone 빠른 설치

개인·소규모 조직이 전용 Linux VM 한 대에서 AICONNECT의 MariaDB, Gateway, Frontend, Nginx, Prometheus, Grafana를 함께 실행하는 구성입니다. GPU 연산은 같은 Tailnet의 별도 LM Studio 서버가 담당하므로 VM에 GPU는 필요하지 않습니다.

Linux에서는 `.bat`가 아니라 `quickstart_standalone.sh`를 실행합니다. 이 스크립트는 Tailscale과 Docker를 대신 설치하지 않으며, 사용자가 준비한 전용 VM을 검사하고 AICONNECT를 안전하게 구성합니다.

## 1. VM 사전 준비

권장 환경:

- Ubuntu Server 22.04 또는 24.04 LTS
- 4 vCPU, RAM 8GB 이상, SSD 100GB 이상
- Docker Engine과 Docker Compose v2
- Tailscale 설치 및 `sudo tailscale up` 완료
- GPU 서버와 VM이 같은 Tailnet에 연결됨
- VM에서 GitHub와 Docker Hub에 접근 가능

GPU 서버에서는 LM Studio API Server를 실행하고 **Serve on Local Network**와 API Token 인증을 활성화합니다. Tailscale ACL은 Gateway VM에서 GPU 서버의 LM Studio 포트(기본 TCP 1234)로 가는 연결만 허용하는 것을 권장합니다.

## 2. GitHub 저장소 다운로드

```bash
sudo apt-get update
sudo apt-get install -y wget tar ca-certificates curl openssl

wget -O aiconnect.tar.gz \
  https://github.com/chefbeom/model-gateway-platform/archive/refs/heads/main.tar.gz

tar -xzf aiconnect.tar.gz
cd model-gateway-platform-main
chmod +x quickstart_standalone.sh
```

### 아무것도 설치되지 않은 VM 전체 설정

Docker와 Tailscale도 설치되지 않은 Ubuntu VM이라면 다음 파일을 실행합니다.

```bash
chmod +x deploy/fullsetting_quickstart_standingalone.sh
./deploy/fullsetting_quickstart_standingalone.sh
```

스크립트가 sudo 권한을 요청하고 다음을 수행합니다.

1. Ubuntu와 대상 사용자를 검사합니다.
2. Docker 공식 APT 저장소에서 Engine, containerd, Buildx, Compose v2를 설치합니다.
3. 일반 사용자를 `docker` 그룹에 추가하고 서비스를 활성화합니다.
4. Tailscale 공식 stable APT 저장소에서 클라이언트를 설치하고 `tailscaled`를 활성화합니다.
5. **Tailscale 로그인은 수행하지 않고**, loopback 전용으로 전체 AICONNECT 스택을 배포합니다.
6. 최초 관리자 생성 여부를 입력받고 6개 컨테이너 Health Check를 완료합니다.

설치가 끝나면 사용자가 직접 Tailscale 인증을 진행합니다.

```bash
sudo tailscale up
cd ~/model-gateway-platform-main  # 실제 저장소 경로로 이동
./quickstart_standalone.sh
```

두 번째 실행에서 Tailscale Serve HTTPS가 설정되고 `.env`의 내부 Base URL이 `https://<host>.<tailnet>.ts.net/v1`로 자동 갱신됩니다. 인증 전 서비스는 `127.0.0.1`에만 바인딩되므로 다른 컴퓨터에서 접근할 수 없습니다.

## 3. 한 번에 설치

```bash
./quickstart_standalone.sh
```

실행 중 최초 관리자 이메일과 12자 이상의 비밀번호를 입력합니다. 비밀번호는 `.env`에 저장되지 않고 최초 관리자 생성 요청에만 사용됩니다.

스크립트는 다음 작업을 수행합니다.

1. Linux, Docker Compose v2, Docker 권한, Tailscale 연결을 검사합니다.
2. Tailscale Serve를 이용해 Tailnet 전용 HTTPS 주소를 준비합니다.
3. `.env`가 없을 때만 서로 다른 운영 비밀값을 생성하고 권한을 `600`으로 제한합니다.
4. 기존 MariaDB 볼륨이 있는데 `.env`가 없으면 데이터 손상을 막기 위해 실행을 중단합니다.
5. Standalone 프로필로 전체 Docker Compose 스택을 빌드하고 Health Check가 끝날 때까지 기다립니다.
6. 빈 설치라면 최초 플랫폼 관리자 계정을 생성합니다.
7. 관리 화면과 OpenAI 호환 Base URL을 출력합니다.

Tailscale Serve는 VM의 `localhost:80`을 Tailnet 내부의 신뢰된 HTTPS 주소로 전달합니다. 로그인 Refresh Cookie가 `Secure`이므로 일반 `http://100.x.x.x` 대신 출력된 `https://<hostname>.<tailnet>.ts.net` 주소를 사용하세요.

## 4. LM Studio 연결까지 함께 확인

GPU 서버의 Tailscale 주소가 `http://100.92.170.22:1234`라면 다음처럼 실행할 수 있습니다.

```bash
AICONNECT_LM_STUDIO_URL=http://100.92.170.22:1234 \
  ./quickstart_standalone.sh
```

HTTP `200`이면 모델 목록 조회까지 성공한 것입니다. HTTP `401`도 네트워크 연결은 성공했으며, 로그인 후 **인프라 → Runtime 연결**에서 LM Studio API Token을 등록하면 됩니다.

연결 자체가 실패하면 다음 항목을 확인합니다.

- GPU 서버의 LM Studio API Server가 실행 중인가?
- LM Studio가 localhost가 아닌 Tailscale 인터페이스에서도 수신하는가?
- VM에서 `tailscale ping <GPU 호스트>`가 성공하는가?
- Tailnet ACL에서 VM → GPU TCP 1234가 허용됐는가?
- GPU 서버 방화벽이 Tailscale 인터페이스의 1234 포트를 허용하는가?

## 5. 비대화형 설치

자동화 환경에서는 다음 변수를 전달할 수 있습니다. 셸 기록과 CI 로그에 비밀번호가 남지 않도록 Secret 저장소를 사용하세요.

```bash
AICONNECT_ADMIN_EMAIL=admin@example.com \
AICONNECT_ADMIN_PASSWORD='replace-with-a-strong-password' \
./quickstart_standalone.sh --non-interactive
```

최초 관리자 생성을 나중에 직접 수행하려면:

```bash
./quickstart_standalone.sh --skip-bootstrap
```

그 후 빈 DB에서 한 번만 `POST /api/auth/bootstrap`을 호출합니다.


## 6. 신뢰된 내부망 HTTP 접속

API 사용자와 관리자가 같은 사내 LAN에서만 접속한다면 Tailscale Serve 대신 VM의 내부 IP를 직접 사용할 수 있습니다.

```bash
AICONNECT_LAN_IP=192.168.35.101 \
  ./quickstart_standalone.sh --lan
```

Docker와 Tailscale도 없는 빈 VM에서는 설치 단계부터 LAN 모드를 전달합니다.

```bash
AICONNECT_LAN_IP=192.168.35.101 \
  ./deploy/fullsetting_quickstart_standingalone.sh --lan
```

접속 주소:

```text
관리 화면: http://192.168.35.101
OpenAI Base URL: http://192.168.35.101/v1
```

LAN 모드는 Nginx를 지정한 내부 IP에만 바인딩하고 `AUTH_COOKIE_SECURE=false`를 적용해 HTTP에서도 로그인 갱신이 동작하게 합니다. 이 설정은 신뢰된 내부망 전용입니다.

- VM의 TCP 80을 인터넷이나 게스트 네트워크에 포워딩하지 않습니다.
- 사내 방화벽에서 허용된 개발자·서비스 대역만 접근하게 제한합니다.
- 외부 공개가 필요해지면 `AUTH_COOKIE_SECURE=true`와 신뢰된 HTTPS를 사용합니다.
- Gateway와 GPU 서버의 통신에는 별도로 `sudo tailscale up`이 필요하지만, LAN 웹 주소를 다시 배포할 필요는 없습니다.
## 7. 인터넷 공개 주소

기본 Quickstart는 Tailnet 구성원만 접근하는 내부 HTTPS 설치입니다. 인터넷에서 API를 호출하려면 공인 DNS, 신뢰된 TLS 인증서, 방화벽과 리버스 프록시를 별도로 구성해야 합니다.

```bash
AICONNECT_EXTERNAL_BASE_URL=https://api.example.com/v1 \
  ./quickstart_standalone.sh
```

외부 URL 문자열을 설정하는 것만으로 인터넷 공개나 TLS가 자동 구성되지는 않습니다. 공인 리버스 프록시는 VM의 Nginx로 요청을 전달해야 하며 MariaDB, Backend 8080, Prometheus는 외부에 직접 공개하면 안 됩니다.

## 8. 운영 명령

```bash
# 상태
docker compose --env-file .env ps

# Gateway 로그
docker compose --env-file .env logs -f api

# 재시작
docker compose --env-file .env restart

# 종료(데이터 볼륨 유지)
docker compose --env-file .env down

# 업데이트
wget -O aiconnect.tar.gz \
  https://github.com/chefbeom/model-gateway-platform/archive/refs/heads/main.tar.gz
# 새 소스에 기존 .env를 복원한 뒤
./quickstart_standalone.sh --skip-bootstrap
```

같은 디렉터리에서 스크립트를 다시 실행하면 기존 `.env`와 데이터 볼륨을 유지합니다. `.env`를 잃어버렸다면 새 비밀값을 만들지 말고 반드시 백업에서 복원하세요.

VM 외부의 암호화 저장소에 다음을 함께 백업해야 합니다.

- MariaDB 정기 백업
- `.env`
- `API_KEY_PEPPER`
- `GATEWAY_ENCRYPTION_KEY`
- `AUTH_SIGNING_KEY`
- `AUTH_REFRESH_PEPPER`

## 제한

Standalone은 Gateway와 DB가 한 VM에 있으므로 VM 장애나 재부팅 중에는 API가 중단됩니다. 서비스 중단을 허용할 수 없게 되면 `deploy/ha` 또는 Kubernetes 프로필로 이전하세요. 같은 DB와 비밀값을 보존하면 기존 API 키와 논리 모델명을 유지할 수 있습니다.
