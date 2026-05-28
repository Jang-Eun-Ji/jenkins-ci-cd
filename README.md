# Jenkins CI/CD Pipeline

GitLab 기반 형상관리 + Jenkins 기반 CI/CD 파이프라인 구성  
빌드(CI)와 배포(CD)를 명확히 분리한 설계 구조

---

## 목표

- 수동 빌드 및 배포 과정 자동화
- 인적 오류 최소화
- 배포 일관성 및 재현성 확보
- 운영 안정성 향상
- WAR 기반 배포 표준 수립

---

## 기술 스택

| 구분 | 내용 |
|------|------|
| 형상관리 | GitLab |
| CI 도구 | Jenkins |
| 빌드 도구 | Maven |
| 빌드 산출물 | WAR |
| 아티팩트 저장소 | Nexus Repository |
| CD 도구 | Jenkins |
| 배포 방식 | curl 다운로드 → systemctl 재기동 |
| 배포 대상 | Tomcat WAS 서버 |
| 스크립트 언어 | Groovy (Jenkins Pipeline DSL) |
| DB (메타 저장소) | H2 (ASSETS 테이블) |

---

## 전체 아키텍처

```
Developer
   │
   │  Push
   ▼
GitLab Repository
   │
   │  Webhook Trigger
   ▼
Jenkins CI (build-and-deploy.groovy)
   │  - Checkout
   │  - Maven Build (WAR 패키징)
   │  - WAR → Nexus Upload
   ▼
Nexus Repository (Artifact Storage)
   │
   │  버전 선택 후 배포 트리거
   ▼
Jenkins CD
   ├── 01. Nexus 아티팩트 목록 조회 → H2 DB(ASSETS) 갱신
   ├── 02. 배포 가능 버전 목록 생성 (Jenkins 파라미터 바인딩)
   ├── 03. 버전 선택 → CD Action Job 트리거
   └── 04. WAR 다운로드 → 백업 → 서비스 중지 → WAR 교체 → 재기동
   │
   ▼
WAS Server (Tomcat)
```

---

## CI 단계 (Continuous Integration)

**파일:** `ci/build-and-deploy.groovy`

### 흐름

1. GitLab Webhook → Jenkins CI 트리거
2. 소스 코드 Checkout (브랜치 파라미터 지원)
3. Maven 빌드 수행
   - 컴파일 → 테스트 → WAR 패키징
4. Nexus Repository에 WAR 업로드 (`deploy:deploy-file`)

### 주요 구현 포인트

- `getBranchName()` : `origin/` prefix 자동 제거
- `currentBuild.buildCauses` 파싱으로 빌드 트리거 출처 구분 (GitLab Webhook / Remote / 수동)
- `findFiles(glob: 'target/ROOT.war')` 로 WAR 파일 존재 여부 검증 후 배포
- Maven `deploy:deploy-file` 골을 사용해 단일 WAR를 직접 Nexus에 업로드
- Nexus 인증은 `withCredentials` 블록으로 처리 (username/password)

### 설계 원칙

> CD 단계에서는 빌드를 수행하지 않음  
> CI에서 생성된 WAR만을 배포 산출물로 사용

---

## CD 단계 (Continuous Deployment)

**파일:** `cd/01 ~ 04`

### CD 파이프라인을 4개 파일로 분리한 이유

단일 스크립트로 구성할 경우 배포 흐름 전체가 하나의 Job에 묶이게 됨.  
아래 이유로 역할별로 분리 설계:

| 파일 | 역할 | 분리 이유 |
|------|------|-----------|
| `01-nexus-list-refresh.groovy` | Nexus 아티팩트 목록 조회 → DB 갱신 | 목록 갱신을 독립 실행 가능하게 분리. 배포와 무관하게 주기적 실행 가능 |
| `02-deploy-version-list.groovy` | 배포 가능 버전 목록 반환 | Jenkins Active Choice Parameter에 바인딩되는 스크립트. UI 연동 목적으로 분리 |
| `03-deploy-trigger.groovy` | 버전 파싱 → CD Action Job 호출 | 버전 선택과 실제 배포 실행을 분리해 트리거 로직 단순화 |
| `04-deploy-to-server.groovy` | WAR 다운로드 → 백업 → 배포 | 실제 서버 접근이 발생하는 유일한 스크립트. 권한/노드 분리 목적 |

> `04`는 `node('was-node-001')`로 WAS 서버 전용 노드에서만 실행되도록 지정  
> 나머지 01~03은 Jenkins Master 또는 공용 노드에서 실행

### 흐름

```
02 (버전 목록 파라미터 생성)
   └── 01 트리거 (Nexus 최신 목록 갱신)
       └── H2 DB (ASSETS 테이블) 조회
03 (버전 선택 → 04 트리거)
   └── 버전 문자열 파싱 (extractVersion)
       └── 04 호출 (desc YAML 파라미터 전달)
04 (실제 배포 수행)
   ├── ASSETS DB에서 다운로드 URL / SHA1 조회
   ├── curl로 Nexus에서 WAR 다운로드
   ├── 기존 WAR 백업
   ├── systemctl stop
   ├── WAR 교체
   └── systemctl start
```

### 주요 구현 포인트

- Nexus API (`/service/rest/v1/search/assets`) continuationToken 기반 페이징 처리
- H2 DB `ASSETS` 테이블을 중간 메타 저장소로 활용 (`MERGE INTO`)
- `getDatabaseConnection(type: "GLOBAL")` Jenkins 플러그인 기반 DB 커넥션
- `03`의 `extractVersion()` : 다양한 버전 문자열 포맷 파싱 (`@NonCPS` 처리)
- `04`의 WAR 다운로드 전 tempDir 초기화로 잔여 파일 오염 방지
- 배포 전 기존 WAR 백업 (`cp auth.war auth_날짜.war`)

---

## CI/CD 분리 설계 의도

| 항목 | 설계 목적 |
|------|-----------|
| 책임 분리 | 빌드 실패가 운영 배포에 영향을 주지 않음 |
| 환경 격리 | 빌드 환경(Maven/JDK)과 운영 서버 환경 분리 |
| 재현성 | 동일 버전 WAR로 언제든 재배포 가능 |
| 확장성 | 배포 대상 서버 증가 시 04 스크립트만 확장 |
| 감사 추적 | Nexus 버전 관리 + DB 메타데이터로 배포 이력 추적 |

---

## 환경변수 설정 가이드

본 레포지토리의 스크립트는 내부 인프라 정보를 모두 환경변수로 분리함.  
Jenkins Pipeline 또는 Job 설정에서 아래 항목을 구성 후 사용.

**CI (`build-and-deploy.groovy`)**

| 환경변수 | 설명 | 예시 |
|----------|------|------|
| `GIT_BASE_URL` | GitLab SSH base URL | `ssh://git@gitlab.example.com:10022` |
| `GIT_SUB_GROUP` | GitLab 서브그룹 | `my-group` |
| `GIT_PROJECT` | GitLab 프로젝트명 | `my-project` |
| `GIT_CREDENTIALS_ID` | Jenkins SSH credentialId | `git-ssh-credentials` |
| `DEFAULT_BRANCH` | 기본 브랜치 | `dev` |
| `NEXUS_REPO_URL` | Nexus 저장소 URL | `https://nexus.example.com/repository/my-repo/` |
| `NEXUS_SERVER_ID` | Nexus 서버 ID | `my-nexus-server` |
| `NEXUS_CREDENTIALS_ID` | Jenkins Nexus credentialId | `nexus-credentials` |
| `ARTIFACT_GROUP_ID` | Maven groupId | `com.example` |
| `ARTIFACT_ID` | Maven artifactId | `my-app` |
| `ARTIFACT_VERSION` | 배포 버전 | `1.0.0-SNAPSHOT` |

**CD (`01~04`)**

| 환경변수 | 설명 |
|----------|------|
| `REPO_URL` | Nexus base URL |
| `REPO_NAME` | Nexus repository 이름 |
| `NEXUS_CREDENTIALS_ID` | Nexus credentialId |
| `GROUP_ID` | Maven groupId |
| `ARTIFACT_ID` | Maven artifactId |
| `INDEX_JOB_NAME` | 01 스크립트 Job 이름 |

---

※ **익명화 처리 안내**

이 레포지토리는 공개용으로 내부 인프라 정보를 아래와 같이 제거/치환하였음

| 항목 | 처리 내용 |
|------|-----------|
| 내부 GitLab 서버 URL | `gitlab.example.com` 으로 치환 |
| 내부 Nexus 서버 URL | `nexus.example.com` 으로 치환 |
| Jenkins credentialId | `nexus-credentials`, `git-ssh-credentials` 등 제네릭 명칭으로 치환 |
| Maven groupId | `com.example` 으로 치환 |
| Nexus 서버 ID | `my-nexus-server` 로 치환 |
| Jenkins Tool 이름 | `Maven`, `JDK17` 등 제네릭 명칭으로 치환 |
| WAS 노드명 | `was-node-001` 로 치환 |
| 배포 경로 | `/mnt/app-server/...` 형태로 일반화 |
