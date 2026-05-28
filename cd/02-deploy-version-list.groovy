// 02-deploy-version-list.groovy
// Purpose: 배포 대상 버전 목록 생성용 스크립트
// Note: Public/Sanitized version - 내부 job명/DB접속정보/groupId/artifactId 제거, env/param 기반 구성

import groovy.sql.Sql
import jenkins.model.*
import hudson.model.*

/*
 * ======= Sanitized Config =======
 * - GROUP_ID: 조회 대상 groupId (예: com.example)
 * - ARTIFACT_ID: 조회 대상 artifactId (예: my-app)
 * - EXTENSION: 확장자 (예: war/jar)
 * - INDEX_JOB_NAME: 01 스크립트(목록 갱신 job) 이름
 * - DB_CONN_TYPE: getDatabaseConnection type (예: GLOBAL)
 * - LIMIT: 목록 반환 개수 제한
 */
def GROUP_ID      = (params.groupId ?: env.GROUP_ID ?: "com.example").trim()
def ARTIFACT_ID   = (params.artifactId ?: env.ARTIFACT_ID ?: "app").trim()
def EXTENSION     = (params.extension ?: env.EXTENSION ?: "war").trim()
def INDEX_JOB_NAME = (env.INDEX_JOB_NAME ?: "01-nexus-list-refresh").trim()
def DB_CONN_TYPE  = (env.DB_CONN_TYPE ?: "GLOBAL").trim()
def LIMIT         = (params.limit ?: env.LIMIT ?: "50").toInteger()

// 1) (선택) 인덱스 갱신 Job 트리거
try {
  def job = Jenkins.instance.getAllItems().find { it -> it.name == INDEX_JOB_NAME }
  if (job != null) {
    def paramsAction = new ParametersAction(
      new StringParameterValue("artifactId", ARTIFACT_ID),
      new StringParameterValue("extension", EXTENSION)
    )
    def causeAction = new CauseAction(new Cause.UserIdCause())
    Jenkins.instance.queue.schedule(job, 0, causeAction, paramsAction)

    // 인덱싱 완료 대기(필요 시 환경변수로 조정 가능)
    sleep((env.INDEX_WAIT_MS ?: "3000").toInteger())
  } else {
    // 공개 버전에서는 job 미존재 가능하므로 경고만 출력
    println "Index job not found: ${INDEX_JOB_NAME} (skip trigger)"
  }
} catch (Exception e) {
  // 공개용: 상세 스택 대신 메시지만 반환
  return ["index trigger error: ${e.message}"]
}

// 2) DB(ASSETS)에서 버전 목록 조회
def selectSql = """\
  SELECT VERSION,
         REPO_TYPE,
         FORMATDATETIME(LAST_MODIFIED, 'yyyy-MM-dd HH:mm:ss.SSS') AS LAST_MODIFIED_DATETIME
    FROM ASSETS
   WHERE GROUP_ID='${GROUP_ID}'
     AND ARTIFACT_ID='${ARTIFACT_ID}'
     AND REPO_TYPE='release'
   ORDER BY LAST_MODIFIED DESC
   FETCH FIRST ${LIMIT} ROWS ONLY
""".stripIndent()

def rows = null

// ✅ 하드코딩된 jdbc:h2:tcp://... 제거
// ✅ Jenkins에 구성된 공용 DB 커넥션 사용 전제
getDatabaseConnection(type: DB_CONN_TYPE) {
  rows = sql(selectSql)
}

if (rows == null || rows.size() < 1) {
  return ["no package"]
}

// 3) Jenkins 파라미터 선택용 문자열 리스트 생성
def versions = []
rows.each { row ->
  versions.add("version: ${row.VERSION} | date: ${row.LAST_MODIFIED_DATETIME} | repo: ${row.REPO_TYPE.toUpperCase()}")
}

return versions
