# AWS 부하테스트 실행 절차

실험 A·B·C를 AWS에서 실행하기 위한 운영 문서다.

실험의 설계와 판정 기준은 [동시성 실험 설계](./concurrency-experiment.md)를, 측정값 기록은 [동시성 실험 결과](./experiment-results.md)를 따른다. 이 문서는 **어떻게 실행하는가**만 다룬다.

---

## 1. 구성

### 1.1 구성도

```
[노트북]                    SSH 접속 · 결과 회수 · 문서 커밋
   │
   │ SSH (22)
   ▼
┌──────────────────────────── VPC ────────────────────────────┐
│                                                              │
│  [EC2 #2] k6 부하 발생기        [EC2 #1] 앱 서버             │
│  요청을 만들어 쏘는 쪽   ──8080──▶  앱 + MySQL + Redis + Kafka│
│                                    프라이빗 IP로 접근        │
└──────────────────────────────────────────────────────────────┘
```

k6는 앱 서버와 **반드시 다른 인스턴스**에서 실행한다. 같은 인스턴스에서 돌리면 k6가 앱의 CPU를 뺏어가 측정값이 오염된다.

두 인스턴스는 같은 VPC 안에서 프라이빗 IP로 통신하므로 인터넷 지연이 측정에 섞이지 않는다.

### 1.2 인스턴스

| 구분 | 인스턴스 | 타입 | 용도 |
|---|---|---|---|
| EC2 #1 | `petcoupon-performance-test`<br>`<APP_INSTANCE_ID>` | m5.xlarge (4 vCPU / 16 GiB) | 앱 + MySQL + Redis + Kafka |
| EC2 #2 | k6 부하 발생기 (신규 생성) | c5.xlarge (4 vCPU / 8 GiB)<br>실험 C에서만 r5.2xlarge (8 vCPU / 64 GiB) | k6 실행 |

### 1.3 공통 환경 정보

| 항목 | 값 |
|---|---|
| 리전 / AZ | `ap-northeast-2` / `ap-northeast-2c` |
| OS | Ubuntu 24.04 LTS |
| SSH 사용자 | `ubuntu` |
| 키 페어 | `petcoupon-test-key` |

> 앱 서버의 **퍼블릭 IP는 시작할 때마다 새로 할당된다.** SSH 접속 주소가 매번 바뀌므로 시작 직후 반드시 확인한다. 반면 프라이빗 IP는 고정이라 k6의 `BASE_URL`은 바꿀 필요가 없다.

### 1.4 환경 값 확인

이 저장소는 공개되어 있으므로 계정별 식별자는 문서에 적지 않는다. 아래 명령으로 조회해 각자 채워 쓴다.

| 플레이스홀더 | 의미 |
|---|---|
| `<APP_INSTANCE_ID>` | 앱 서버 인스턴스 ID |
| `<APP_PRIVATE_IP>` | 앱 서버 프라이빗 IP (재시작해도 유지) |
| `<APP_SG_ID>` | 앱 서버 보안 그룹 ID |
| `<K6_INSTANCE_ID>` | k6 인스턴스 ID |
| `<K6_SG_ID>` | k6 보안 그룹 ID |
| `<VPC_ID>` / `<SUBNET_ID>` | 앱 서버가 속한 VPC · 서브넷 |

**노트북 PowerShell**에서 앱 서버 관련 값을 한 번에 조회한다.

```powershell
aws ec2 describe-instances --filters "Name=tag:Name,Values=petcoupon-performance-test" --query "Reservations[].Instances[].{InstanceId:InstanceId,PrivateIP:PrivateIpAddress,SG:SecurityGroups[0].GroupId,VPC:VpcId,Subnet:SubnetId}" --output table
```

k6 인스턴스를 만든 뒤에는 다음으로 조회한다.

```powershell
aws ec2 describe-instances --filters "Name=tag:Name,Values=petcoupon-k6-generator" --query "Reservations[].Instances[].{InstanceId:InstanceId,SG:SecurityGroups[0].GroupId,PublicIP:PublicIpAddress}" --output table
```

조회한 값은 개인 메모나 팀 위키에 보관하고 저장소에 커밋하지 않는다.

---

## 2. 최초 1회 준비

인스턴스를 처음 쓸 때만 수행한다. 두 번째 실험부터는 4장으로 바로 간다.

### 2.1 k6 인스턴스 생성

AWS 콘솔에서 진행한다.

1. `https://console.aws.amazon.com/ec2/` 접속, 리전이 **아시아 태평양(서울)** 인지 확인
2. 왼쪽 메뉴 **인스턴스** → **인스턴스 시작**
3. 다음과 같이 설정한다

| 항목 | 값 |
|---|---|
| 이름 | `petcoupon-k6-generator` |
| AMI | Ubuntu Server 24.04 LTS (64비트 x86) |
| 인스턴스 유형 | `c5.xlarge` |
| 키 페어 | `petcoupon-test-key` (앱 서버와 동일) |
| VPC | `<VPC_ID>` |
| 서브넷 | `<SUBNET_ID>` (**앱 서버와 같은 AZ**) |
| 퍼블릭 IP 자동 할당 | 활성화 |
| 보안 그룹 | 새로 생성 — 이름 `petcoupon-k6-sg`, 인바운드는 **SSH(22)만**, 소스는 내 IP |
| 스토리지 | 20 GiB gp3 |

> 서브넷을 앱 서버와 같게 두는 이유는 두 가지다. AZ가 다르면 네트워크 지연이 늘어 측정에 영향을 주고, AZ 간 데이터 전송 요금이 발생한다.

4. **인스턴스 시작** 클릭

### 2.2 앱 서버가 k6를 받아들이도록 보안 그룹 설정

k6 인스턴스에서 앱 서버의 8080으로 요청을 보내야 한다. 앱 서버 보안 그룹에 k6 보안 그룹을 허용하는 규칙을 추가한다.

먼저 k6 보안 그룹 ID를 확인한다. **노트북 PowerShell**에서:

```powershell
aws ec2 describe-security-groups --filters "Name=group-name,Values=petcoupon-k6-sg" --query "SecurityGroups[].GroupId" --output text
```

나온 ID를 아래 `<K6_SG_ID>` 자리에 넣어 실행한다.

```powershell
aws ec2 authorize-security-group-ingress --group-id <APP_SG_ID> --protocol tcp --port 8080 --source-group <K6_SG_ID>
```

**확인**

```powershell
aws ec2 describe-security-groups --group-ids <APP_SG_ID> --query "SecurityGroups[].IpPermissions[]" --output json
```

8080 규칙에 `UserIdGroupPairs`로 k6 보안 그룹이 들어가 있으면 된다.

> IP 주소가 아니라 보안 그룹을 소스로 지정했기 때문에, k6 인스턴스의 IP가 바뀌어도 규칙을 고칠 필요가 없다.

### 2.3 앱 서버 소프트웨어 설치

앱 서버에 SSH로 접속한 뒤 실행한다. 접속 방법은 4.3을 참고한다.

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk docker.io docker-compose-v2
```

```bash
sudo usermod -aG docker ubuntu
```

그룹 변경을 적용하려면 **SSH 세션을 끊고 다시 접속한다.**

**확인**

```bash
java -version && docker --version && docker compose version
```

`openjdk version "21..."`, `Docker version ...`, `Docker Compose version v2...` 가 모두 나와야 한다.

### 2.4 k6 서버 소프트웨어 설치

k6 인스턴스에 SSH로 접속한 뒤 실행한다.

```bash
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
```

```bash
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
```

```bash
sudo apt update && sudo apt install -y k6
```

**확인**

```bash
k6 version
```

### 2.5 k6 서버 커널 파라미터 조정

실험 C에서 20,000개의 동시 연결을 만들려면 기본 설정으로는 부족하다. 실험 C 전에만 수행해도 되지만 미리 해두는 편이 낫다.

```bash
sudo sysctl -w net.ipv4.ip_local_port_range="10000 65535"
sudo sysctl -w net.ipv4.tcp_tw_reuse=1
ulimit -n 100000
```

`ulimit`은 셸 세션마다 다시 지정해야 한다. k6 실행 직전에 같은 셸에서 실행한다.

**확인**

```bash
sysctl net.ipv4.ip_local_port_range && ulimit -n
```

---

## 3. 실행 전 체크리스트

실험을 시작하기 전에 확인한다. 하나라도 빠지면 측정 도중에 문제가 생긴다.

- [ ] 두 인스턴스가 같은 AZ(`ap-northeast-2c`)에 있다
- [ ] 앱 서버 보안 그룹에 k6 보안 그룹으로부터의 8080 인바운드가 있다
- [ ] 노트북 공인 IP가 두 보안 그룹의 SSH 규칙과 일치한다
- [ ] 앱 서버에 Java 21 · Docker · Docker Compose가 설치돼 있다
- [ ] k6 서버에 k6가 설치돼 있다
- [ ] 결과 문서 `experiment-results.md`의 2.1 · 2.2가 채워질 준비가 됐다
- [ ] 실험 도중 서버 설정을 바꾸지 않기로 확인했다

노트북 공인 IP 확인은 **노트북 PowerShell**에서:

```powershell
curl.exe -s https://ifconfig.me
```

IP가 바뀌었다면 두 보안 그룹의 SSH 규칙을 갱신한다.

```powershell
aws ec2 authorize-security-group-ingress --group-id <APP_SG_ID> --protocol tcp --port 22 --cidr <새IP>/32
```

---

## 4. 인스턴스 시작과 접속

### 4.1 인스턴스 시작

**노트북 PowerShell**에서 실행한다. `<K6_INSTANCE_ID>`는 2.1에서 만든 인스턴스 ID다.

```powershell
aws ec2 start-instances --instance-ids <APP_INSTANCE_ID> <K6_INSTANCE_ID>
```

기동에 1~2분 걸린다.

### 4.2 퍼블릭 IP 확인

앱 서버의 퍼블릭 IP는 시작할 때마다 바뀌므로 매번 확인한다.

```powershell
aws ec2 describe-instances --instance-ids <APP_INSTANCE_ID> <K6_INSTANCE_ID> --query "Reservations[].Instances[].{Name:Tags[?Key=='Name']|[0].Value,State:State.Name,PublicIP:PublicIpAddress,PrivateIP:PrivateIpAddress}" --output table
```

`State`가 `running`이 될 때까지 기다린다.

### 4.3 SSH 접속

터미널 두 개를 쓴다.

| 터미널 | 접속 대상 | 용도 |
|---|---|---|
| 터미널 A | 앱 서버 | 앱 실행 (계속 점유됨) |
| 터미널 B | 앱 서버 | 쿠폰 생성, 상태 확인 |
| 터미널 C | k6 서버 | k6 실행 |

키 파일이 있는 폴더에서 실행한다.

```powershell
ssh -i petcoupon-test-key.pem ubuntu@<앱서버-퍼블릭IP>
```

```powershell
ssh -i petcoupon-test-key.pem ubuntu@<k6서버-퍼블릭IP>
```

**권한 오류가 나는 경우** (`Permissions for 'petcoupon-test-key.pem' are too open`) 키 파일 권한을 제한한다.

```powershell
icacls petcoupon-test-key.pem /inheritance:r /grant:r "$env:USERNAME:R"
```

---

## 5. 배포와 기동

### 5.1 로컬에서 jar 빌드

**노트북 PowerShell**, 저장소 루트에서:

```powershell
.\gradlew.bat clean bootJar
```

**성공 신호**: `BUILD SUCCESSFUL`. 결과물은 `build\libs\` 아래에 생성된다.

```powershell
Get-ChildItem build\libs\*.jar
```

### 5.2 앱 서버로 파일 전송

앱 서버에는 jar 외에 `docker-compose.yml`과 스키마 SQL이 필요하다.

```powershell
ssh -i petcoupon-test-key.pem ubuntu@<앱서버-퍼블릭IP> "mkdir -p ~/app/sql"
```

```powershell
scp -i petcoupon-test-key.pem build\libs\*.jar ubuntu@<앱서버-퍼블릭IP>:~/app/app.jar
```

```powershell
scp -i petcoupon-test-key.pem docker-compose.yml ubuntu@<앱서버-퍼블릭IP>:~/app/
```

```powershell
scp -i petcoupon-test-key.pem src\main\resources\sql\test_schema.sql ubuntu@<앱서버-퍼블릭IP>:~/app/sql/
```

> `docker-compose.yml`이 SQL 파일을 `./src/main/resources/sql/test_schema.sql` 경로로 참조하므로, 서버에서는 compose 파일의 해당 경로를 `./sql/test_schema.sql`로 수정해야 한다. 5.3에서 처리한다.

### 5.3 인프라 기동

**터미널 B (앱 서버)** 에서:

```bash
cd ~/app
```

compose 파일의 SQL 경로를 서버 구조에 맞게 수정한다.

```bash
sed -i 's|./src/main/resources/sql/test_schema.sql|./sql/test_schema.sql|' docker-compose.yml
```

```bash
docker compose up -d
```

**확인**

```bash
docker compose ps
```

MySQL과 Redis가 `Up (healthy)`, Kafka가 `Up`이면 된다. MySQL은 healthy까지 30초 정도 걸린다.

### 5.4 스키마 확인

```bash
docker exec petcoupon-mysql mysql -uroot -proot coupon_poc -e "SELECT COUNT(*) AS user_count FROM app_user;"
```

**20000** 이 나와야 한다.

> 숫자가 다르거나 테이블이 없으면 볼륨을 초기화한다. `docker compose down -v` 는 **MySQL 데이터를 모두 삭제**한다. 실험 데이터뿐이므로 안전하지만 의미를 알고 실행한다.
>
> ```bash
> docker compose down -v && docker compose up -d
> ```

### 5.5 앱 실행

**터미널 A (앱 서버)** 에서:

```bash
cd ~/app && java -jar app.jar
```

**성공 신호**: `Started ExperimentApplication in ...`

이 터미널은 앱이 점유하므로 닫지 않는다. 접속이 끊겨도 앱이 죽지 않게 하려면 `nohup`으로 띄운다.

```bash
cd ~/app && nohup java -jar app.jar > app.log 2>&1 &
```

이 경우 로그는 `tail -f ~/app/app.log`로 확인한다.

---

## 6. 실행 전 검증

부하를 걸기 전에 API가 정상 동작하는지 확인한다. 여기서 실패하면 부하테스트 결과는 의미가 없다.

### 6.1 단건 발급 확인

**터미널 B (앱 서버)** 에서 검증용 쿠폰을 만든다.

```bash
curl -X POST http://localhost:8080/experiment/coupons -H "Content-Type: application/json" -d '{"quantity": 10}'
```

응답의 `couponId`를 아래 `<검증쿠폰>` 자리에 넣어 전략별로 한 번씩 호출한다. `userId`는 전략마다 다르게 준다.

```bash
curl -X POST "http://localhost:8080/experiment/coupons/<검증쿠폰>/issue?strategy=DIRECT" -H "Content-Type: application/json" -d '{"userId": 1, "requestId": "verify-direct-1"}'
```

`PESSIMISTIC`, `OPTIMISTIC`, `CONDITIONAL`은 같은 형식으로 `strategy`와 `userId`, `requestId`만 바꾼다.

`REDIS`와 `KAFKA`는 Redis 재고를 먼저 초기화한다.

```bash
curl -X POST http://localhost:8080/experiment/coupons/<검증쿠폰>/redis/init
```

**기대 결과**

| 전략 | `result` |
|---|---|
| DIRECT · PESSIMISTIC · OPTIMISTIC · CONDITIONAL · REDIS | `SUCCESS` |
| KAFKA | `WAITING` |

### 6.2 상태 조회와 Reset 확인

```bash
curl http://localhost:8080/experiment/coupons/<검증쿠폰>/status
```

```bash
curl -X POST http://localhost:8080/experiment/coupons/<검증쿠폰>/reset
```

Reset 후 `issueCount`가 0으로 돌아오면 정상이다.

### 6.3 k6 서버에서 앱 서버 접근 확인

**터미널 C (k6 서버)** 에서:

```bash
curl -s http://<APP_PRIVATE_IP>:8080/experiment/coupons/<검증쿠폰>/status
```

JSON이 돌아오면 보안 그룹 설정이 올바른 것이다. 응답이 없으면 2.2를 다시 확인한다.

### 6.4 k6 스크립트 전송

**노트북 PowerShell**, 저장소 루트에서:

```powershell
ssh -i petcoupon-test-key.pem ubuntu@<k6서버-퍼블릭IP> "mkdir -p ~/backend/load-test/results"
```

```powershell
scp -i petcoupon-test-key.pem -r load-test\k6 ubuntu@<k6서버-퍼블릭IP>:~/backend/load-test/
```

k6는 결과 파일을 실행 위치 기준 상대경로에 저장하므로, **항상 `~/backend`에서 실행한다.**

---

## 7. 실험 A 실행 — 재고 차감 전략 비교

대상은 `DIRECT`, `PESSIMISTIC`, `OPTIMISTIC`, `CONDITIONAL`, `REDIS` 다섯 전략이다. `KAFKA`는 실험 B에서 다룬다.

### 7.1 쿠폰 생성

단계마다 재고가 다르고 Reset은 총재고를 바꾸지 못하므로 **단계별로 새 쿠폰을 만든다.** 전략별로도 분리한다.

**터미널 B (앱 서버)** 에서 재고별로 5개씩 생성한다.

```bash
for i in 1 2 3 4 5; do curl -s -X POST http://localhost:8080/experiment/coupons -H "Content-Type: application/json" -d '{"quantity": 10}'; echo; done
```

같은 방식으로 `100`, `500`, `1000` 재고도 5개씩 만든다. 총 20개다.

생성된 `couponId`를 **`experiment-results.md` 3.1 표에 즉시 기록한다.** 나중에 어느 번호가 어느 전략인지 알 수 없게 되면 결과를 신뢰할 수 없다.

### 7.2 실행

**터미널 C (k6 서버)**, `~/backend`에서 실행한다.

```bash
cd ~/backend
```

단계별 파라미터는 다음과 같다.

| 단계 | 재고 | VUS / ITERATIONS | RUN_ID | 반복 |
|---|---:|---:|---|---:|
| 스모크 | 10 | 20 | `aws-smoke-1` | 1회 |
| 기본 | 100 | 200 | `aws-basic-1` ~ `-3` | 3회 |
| 중간 | 500 | 1,000 | `aws-mid-1` ~ `-3` | 3회 |
| 공통최대 | 1,000 | 2,000 | `aws-max-1` ~ `-3` | 3회 |

실행 명령 형식이다. 전략 파일과 `COUPON_ID`, `VUS`, `ITERATIONS`, `RUN_ID`를 단계에 맞게 바꾼다.

```bash
k6 run -e BASE_URL=http://<APP_PRIVATE_IP>:8080 -e COUPON_ID=<쿠폰번호> -e VUS=200 -e ITERATIONS=200 -e RUN_ID=aws-basic-1 load-test/k6/direct.js
```

전략 파일은 `direct.js`, `pessimistic.js`, `optimistic.js`, `conditional.js`, `redis.js` 다섯 개다.

### 7.3 실행 순서

한 단계 안에서 이 순서로 돈다.

```
스모크 5전략 1회씩
  → 기본 5전략 1회차 → 2회차 → 3회차
  → 중간 5전략 1회차 → 2회차 → 3회차
  → 공통최대 5전략 1회차 → 2회차 → 3회차
```

전략을 먼저 다 돌리고 회차를 반복하는 편이 낫다. 같은 회차 안에서는 서버 상태가 비슷하게 유지된다.

### 7.4 회차마다 확인할 것

- threshold `✓` 두 개 (`system_error_rate`, `consistency_check_rate`)
- `DIRECT`는 `consistency_check_rate`가 `✗`로 나오는 것이 **정상**이다. 동시성 제어가 없는 기준 전략이므로 불일치 발생 자체가 결과다 (설계 문서 §9.2)
- 결과 파일이 생성됐는지 확인

```bash
ls -la load-test/results/
```

### 7.5 2,000 VU 실행 시 자원 사용량 기록

실험 C에 필요한 인스턴스 크기를 계산하기 위한 자료다. **공통최대 단계를 돌리는 도중** 별도 SSH 세션에서 확인한다.

```bash
free -h && uptime
```

`experiment-results.md` 7.1에 다음을 기록한다.

- 2,000 VU 실행 시 k6 서버 메모리 사용량
- 2,000 VU 실행 시 CPU 부하

VU당 메모리 사용량을 구해 20,000 VU에 필요한 용량을 계산한다.

---

## 8. 실험 B 실행 — 처리 파이프라인 비교

`REDIS`와 `KAFKA`를 비교한다. `REDIS`는 실험 A와 같은 세션·같은 설정이라면 결과를 재사용한다.

### 8.1 쿠폰 생성

`KAFKA`용으로 단계별 1개씩, 총 4개를 만든다. 생성 즉시 `experiment-results.md` 3.2에 기록한다.

### 8.2 단계별 타임아웃

`KAFKA`는 teardown에서 Consumer의 DB 저장 완료까지 기다린다. **단계가 커지면 이 값을 반드시 올린다.** 올리지 않으면 Kafka가 정상인데도 정합성 검증이 실패로 기록된다.

| 단계 | `KAFKA_WAIT_TIMEOUT` | `MAX_DURATION` |
|---|---:|---|
| 스모크 | 30 (기본값) | 2m |
| 기본 | 60 | 2m |
| 중간 | 180 | 5m |
| 공통최대 | 300 | 5m |

`teardownTimeout`은 `KAFKA_WAIT_TIMEOUT + 60초`로 자동 계산되므로 따로 지정하지 않는다.

```bash
k6 run -e BASE_URL=http://<APP_PRIVATE_IP>:8080 -e COUPON_ID=<쿠폰번호> -e VUS=200 -e ITERATIONS=200 -e RUN_ID=aws-basic-1 -e KAFKA_WAIT_TIMEOUT=60 -e MAX_DURATION=2m load-test/k6/kafka.js
```

### 8.3 Consumer 완료 확인

k6의 teardown이 자동으로 대기하지만, 최종 상태를 직접 확인해두면 판정이 확실하다. **터미널 B (앱 서버)** 에서:

```bash
curl http://localhost:8080/experiment/coupons/<쿠폰번호>/status
```

`consistent: true` 이고 `issueCount`가 기대값(재고와 요청 수 중 작은 값)과 같아야 한다.

Consumer 처리가 끝나기 전에 Reset하면 결과가 오염된다. **반드시 위 확인 후 다음 회차로 넘어간다.**

---

## 9. 실험 C 실행 — 최종 요구사항 검증

재고 10,000 / 요청 20,000 규모로 최종 요구사항을 검증한다.

### 9.1 실행 방식 결정

7.5에서 기록한 자원 사용량으로 필요한 인스턴스 크기를 계산한 뒤 진행한다.

```
VU당 메모리 사용량 × 20,000 = 필요 메모리
```

| 계산 결과 | 조치 |
|---|---|
| 64 GiB 이하 | k6 인스턴스를 `r5.2xlarge`로 변경 |
| 64 GiB 초과 | `r5.4xlarge`(128 GiB)로 변경하거나 k6 분산 실행 검토 |

### 9.2 k6 인스턴스 타입 변경

인스턴스를 중지한 뒤 변경한다. **노트북 PowerShell**에서:

```powershell
aws ec2 stop-instances --instance-ids <K6_INSTANCE_ID>
```

중지 완료 후:

```powershell
aws ec2 modify-instance-attribute --instance-id <K6_INSTANCE_ID> --instance-type r5.2xlarge
```

```powershell
aws ec2 start-instances --instance-ids <K6_INSTANCE_ID>
```

앱 서버는 중지하지 않는다. 앱 서버를 재시작하면 실험 A·B와 조건이 달라진다.

### 9.3 커널 파라미터 재적용

인스턴스를 재시작했으므로 2.5를 다시 실행한다. **터미널 C (k6 서버)** 에서:

```bash
sudo sysctl -w net.ipv4.ip_local_port_range="10000 65535" && sudo sysctl -w net.ipv4.tcp_tw_reuse=1 && ulimit -n 100000
```

### 9.4 실행

쿠폰을 하나 만들고(재고 10,000) 3회 실행한다.

```bash
k6 run -e BASE_URL=http://<APP_PRIVATE_IP>:8080 -e COUPON_ID=<쿠폰번호> -e VUS=20000 -e ITERATIONS=20000 -e RUN_ID=aws-final-1 -e KAFKA_WAIT_TIMEOUT=900 -e MAX_DURATION=10m load-test/k6/kafka.js
```

실행 중 앱 서버 자원을 기록한다. **터미널 B (앱 서버)** 에서:

```bash
free -h && uptime && docker stats --no-stream
```

---

## 10. 결과 회수와 기록

### 10.1 결과 파일 회수

k6 서버에 쌓인 JSON을 노트북으로 가져온다. **노트북 PowerShell**, 저장소 루트에서:

```powershell
scp -i petcoupon-test-key.pem -r ubuntu@<k6서버-퍼블릭IP>:~/backend/load-test/results/* load-test\results\
```

```powershell
Get-ChildItem load-test\results\ | Select-Object Name, Length
```

### 10.2 문서 기록 순서

한 단계가 끝날 때마다 기록한다. 전부 끝내고 몰아서 하면 어느 수치가 어느 실행인지 헷갈린다.

1. `experiment-results.md`의 해당 단계 표에 3회 수치를 채운다
2. 평균 행을 계산해 넣는다
3. 정합성 상세 표를 채운다
4. 관찰 내용에 특이사항을 적는다

### 10.3 커밋 단위

단계별로 묶어 커밋한다.

```
test: 실험 A 스모크 테스트 결과 추가
test: 실험 A 기본 단계(100/200) 결과 추가
test: 실험 A 중간 단계(500/1000) 결과 추가
test: 실험 A 공통최대(1000/2000) 결과 추가
docs: 실험 A 측정 결과 및 분석 기록
```

> 설정 실수나 서버 재시작으로 망친 실행 결과는 커밋하지 않는다. 같은 `RUN_ID`로 다시 돌려 덮어쓴다.

---

## 11. 종료 절차

**요금이 계속 나가므로 실험이 끝나면 반드시 인스턴스를 중지한다.**

### 11.1 앱과 컨테이너 정지

**터미널 A (앱 서버)** 에서 `Ctrl + C`로 앱을 멈춘다. `nohup`으로 띄웠다면:

```bash
pkill -f app.jar
```

```bash
cd ~/app && docker compose down
```

`-v`를 붙이지 않으면 MySQL 데이터는 보존된다.

### 11.2 인스턴스 중지

**노트북 PowerShell**에서:

```powershell
aws ec2 stop-instances --instance-ids <APP_INSTANCE_ID> <K6_INSTANCE_ID>
```

**확인**

```powershell
aws ec2 describe-instances --instance-ids <APP_INSTANCE_ID> <K6_INSTANCE_ID> --query "Reservations[].Instances[].{Name:Tags[?Key=='Name']|[0].Value,State:State.Name}" --output table
```

둘 다 `stopped`인지 확인한다.

### 11.3 실험 완전 종료 후

모든 실험이 끝나 인스턴스가 더 필요 없으면 종료(terminate)한다. 중지 상태여도 **EBS 볼륨 요금은 계속 나간다.**

```powershell
aws ec2 terminate-instances --instance-ids <K6_INSTANCE_ID>
```

> 종료하면 디스크 내용이 삭제되고 복구할 수 없다. 결과 파일을 노트북으로 모두 회수했는지 먼저 확인한다. 앱 서버는 결과 분석 중 다시 필요할 수 있으므로 성급히 종료하지 않는다.

---

## 12. 트러블슈팅

| 증상 | 확인할 것 |
|---|---|
| SSH 접속이 안 됨 | 노트북 공인 IP가 바뀌었는지 (`curl.exe -s https://ifconfig.me`) → 보안 그룹 갱신 |
| k6에서 앱 서버 응답 없음 | 앱 서버 보안 그룹에 k6 보안 그룹 8080 인바운드가 있는지 (2.2) |
| 앱 기동 실패 `Schema-validation: missing table` | 스키마 미적용 → `docker compose down -v && docker compose up -d` |
| 앱 기동 실패 `Connection refused: 3306` | MySQL이 아직 healthy가 아님 → 30초 후 재시도 |
| `Coupon reset failed` | `COUPON_ID`가 잘못됐거나 앱이 죽음 |
| `Redis initialization failed` | Redis 컨테이너 상태 확인 (`docker compose ps`) |
| `DIRECT`의 정합성 threshold 실패 | **정상이다.** 기준 전략이므로 불일치 자체가 결과다 (설계 문서 §9.2) |
| `KAFKA`의 정합성 threshold 실패 | `KAFKA_WAIT_TIMEOUT`이 단계에 비해 작을 가능성 (8.2) |
| `teardown() execution timed out` | `teardownTimeout` 계산 문제 → `common.js` 확인 |
| 실험 C에서 연결 오류 다발 | 커널 파라미터 미적용 (9.3) 또는 k6 인스턴스 메모리 부족 |
| 결과 파일이 안 생김 | k6를 `~/backend`에서 실행했는지 확인 (경로가 상대경로다) |
