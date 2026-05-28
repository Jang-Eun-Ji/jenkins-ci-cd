// 01-nexus-list-refresh.groovy
// Purpose: Nexus(artifact repository)에서 아티팩트 목록 조회 후 DB(ASSETS)에 메타데이터 갱신
// Note: Public/Sanitized version - 내부 URL/Repo/Credential/기관명 제거, 환경변수 기반 구성

#!groovy
import java.text.SimpleDateFormat

node {

  /*
   * ======= Sanitized Config =======
   * - REPO_URL: Nexus base URL (예: https://nexus.example.com)
   * - REPO_NAME: Nexus repository name (예: release-repository)
   * - NEXUS_CREDENTIALS_ID: Jenkins credentialsId
   * - GROUP_ID_PATTERN: 조회할 groupId 패턴 (기본: "*")
   * - MAX_PAGES: continuationToken 페이징 최대 횟수 (기본: 15)
   */
  def REPO_URL = (env.REPO_URL ?: "https://nexus.example.com").trim()
  def REPO_NAME = (env.REPO_NAME ?: "release-repository").trim()
  def NEXUS_CREDENTIALS_ID = (env.NEXUS_CREDENTIALS_ID ?: "nexus-credentials").trim()
  def GROUP_ID_PATTERN = (env.GROUP_ID_PATTERN ?: "*").trim()
  def MAX_PAGES = (env.MAX_PAGES ?: "15").toInteger()

  def NEXUS_SEARCH_PATH = "/service/rest/v1/search/assets"
  def QUERY_FORMAT = "?maven.extension=%s&sort=version&repository=%s&maven.groupId=%s&maven.artifactId=%s"

  def artifactId
  def extension
  def artifacts = []
  def skipInsert = false

  stage('prepare') {
    artifactId = params.artifactId != null ? params.artifactId : "*"
    extension  = params.extension  != null ? params.extension  : "*"

    println "Query - ${extension}:${GROUP_ID_PATTERN}:${artifactId}"
    println "Repo  - ${REPO_URL} / ${REPO_NAME}"
  }

  stage('fetch_from_repository') {
    def query = String.format(QUERY_FORMAT, extension, REPO_NAME, GROUP_ID_PATTERN, artifactId)
    def baseUrl = REPO_URL + NEXUS_SEARCH_PATH + query
    def url = baseUrl

    for (int i = 0; i < MAX_PAGES; i++) {
      def response = httpRequest(
        httpMode: "GET",
        url: url,
        acceptType: "APPLICATION_JSON_UTF8",
        authentication: NEXUS_CREDENTIALS_ID,
        consoleLogResponseBody: false,
        timeout: 10000
      )

      def jsonObj = readJSON text: response.content

      if (jsonObj?.items != null) {
        artifacts.addAll(jsonObj.items)
      }

      def token = jsonObj?.continuationToken
      if (token == null) break

      url = baseUrl + "&continuationToken=${token}"
    }

    if (artifacts.size() == 0) {
      skipInsert = true
    }
  }

  stage('upsert_assets_to_db') {
    if (skipInsert) {
      println "No artifacts found"
      return
    }

    // 날짜 파싱 포맷
    def parseFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    def dateTimeFormat = "yyyy-MM-dd HH:mm:ss.SSS"

    def lastIndex = artifacts.size() - 1
    def insertSql = new StringBuilder("MERGE INTO ASSETS VALUES ")

    artifacts.eachWithIndex { artifact, index ->
      // 안전한 널 처리
      def g = artifact?.maven2?.groupId ?: ""
      def a = artifact?.maven2?.artifactId ?: ""
      def v = artifact?.maven2?.version ?: ""
      def lm = artifact?.lastModified ?: ""
      def du = artifact?.downloadUrl ?: ""
      def sha1 = artifact?.checksum?.sha1 ?: ""

      // 로그 최소화(민감정보 가능성 있는 URL 전체 출력 지양)
      println "asset - ${g}:${a}:${v} / lastModified=${lm} / sha1=${sha1}"

      def lastModifiedDate = lm ? parseFormat.parse(lm).format(dateTimeFormat) : null

      insertSql.append("(")
      insertSql.append("'${g}',")
      insertSql.append("'${a}',")
      insertSql.append("'${v}',")
      insertSql.append(lastModifiedDate != null
        ? "PARSEDATETIME('${lastModifiedDate}', '${dateTimeFormat}'),"
        : "NULL,"
      )
      insertSql.append("'${du}',")
      insertSql.append("'release',")
      insertSql.append("'${sha1}',")
      insertSql.append("CURRENT_TIMESTAMP()")
      insertSql.append(index != lastIndex ? ")," : ")")
    }

    println "upsert to ASSETS - rows=${artifacts.size()}"

    // Jenkins Shared Library 또는 플러그인 제공 커넥션 사용 전제
    getDatabaseConnection(type: "GLOBAL") {
      sql(insertSql.toString())
    }
  }
}
