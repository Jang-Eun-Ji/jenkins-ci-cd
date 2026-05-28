// 03-deploy-trigger.groovy
// Purpose: 선택된 버전 기반 배포 Action Job 호출 수행
// Note: Public/Sanitized version - job 경로/credentialId/groupId/artifactId/도메인 제거, env/param 기반 구성

#!groovy

/*
 * ======= Sanitized Config =======
 * - APP_ID: 논리적 서비스 식별자 (예: app-auth)
 * - NEXUS_CREDENTIALS_ID: Jenkins credentialsId (실제 값은 Jenkins에서 관리)
 * - GROUP_ID: Maven groupId
 * - ARTIFACT_ID: Maven artifactId
 * - ACTION_JOB: 실제 배포 수행 Job 이름(또는 경로)
 */
def APP_ID = (env.APP_ID ?: "app").trim()
def NEXUS_CREDENTIALS_ID = (env.NEXUS_CREDENTIALS_ID ?: "nexus-credentials").trim()
def GROUP_ID = (env.GROUP_ID ?: "com.example").trim()
def ARTIFACT_ID = (env.ARTIFACT_ID ?: "app").trim()
def ACTION_JOB = (env.ACTION_JOB ?: "cd/04-deploy-to-server").trim()

// desc 템플릿(민감정보 제거 버전)
def desc = [
  id    : APP_ID,
  nexus : [ credentialsId: NEXUS_CREDENTIALS_ID ],
  package: [
    groupId   : GROUP_ID,
    artifactId: ARTIFACT_ID,
    version   : null
  ],
  worker: null
]

/**
 * params.version 입력 포맷 예시
 * - "1.2.3"
 * - "version: 1.2.3 | date: ... | repo: ..."
 * - "버전: 1.2.3 |등록일자: ..."
 */
@NonCPS
String extractVersion(String raw) {
  if (raw == null) return null
  def s = raw.trim()

  // "version:" 또는 "버전:" 라벨 기반 추출
  def m = (s =~ /(?:^|\s)(?:version|버전)\s*:\s*([^\|\s]+)/)
  if (m.find()) return m.group(1).trim()

  // "|" 구분 존재 시 첫 토큰을 버전으로 간주(마지막 안전장치)
  if (s.contains("|")) return s.split("\\|")[0].trim()

  // 그 외는 전체 문자열을 버전으로 간주
  return s
}
