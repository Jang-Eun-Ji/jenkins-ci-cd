// medi-my-data-auth-deploy (배포 action 스크립트)
#!groovy

import java.text.SimpleDateFormat

assert params.desc

def desc = readYaml text: params.desc

node('was-node-001') {

  def downDateTime = new Date().format('yyyy-MM-dd')
  def tempDir = "${env.WORKSPACE}/auth/app"
  def downloadDir = "${tempDir}/${downDateTime}"
  def dateTimeFormat = "yyyy-MM-dd HH:mm:ss.SSSSSS"
  def sha1Sum
  def downloadUrl
  def sourcePackage
  def warName

  stage("${desc.package.artifactId} 조회") {

    def selectDownloadUrlSql = """\
      SELECT GROUP_ID, ARTIFACT_ID, VERSION, DOWNLOAD_URL, SHA1_SUM,
        FORMATDATETIME(LAST_MODIFIED, '${dateTimeFormat}') LAST_MODIFIED_TIMESTAMP
      FROM ASSETS
      WHERE REPO_TYPE='release'
            AND GROUP_ID='${desc.package.groupId}'
            AND ARTIFACT_ID='${desc.package.artifactId}'
            AND VERSION='${desc.package.version}'
      ORDER BY LAST_MODIFIED DESC, ADD_DATE DESC
      FETCH FIRST 1 ROWS ONLY
    """.stripIndent()

    def record
    getDatabaseConnection(type: "GLOBAL") {
      // println selectDownloadUrlSql
      records = sql(selectDownloadUrlSql)
      if(records != null && records.size() < 1) {
        error "no item."
      }
      record = records[0]
    }

    println "" \
      << "Package : ${record.GROUP_ID}:${record.ARTIFACT_ID}\n" \
      << "Version : ${record.VERSION}\n" \
      << "Last Modified : ${record.LAST_MODIFIED_TIMESTAMP}\n" \
      << "Download URL : ${record.DOWNLOAD_URL}\n" \
      << "SHA1 Sum : ${record.SHA1_SUM}"

    sha1Sum = record.SHA1_SUM;
    downloadUrl = record.DOWNLOAD_URL;
    sourcePackage = "${downloadDir}/${record.ARTIFACT_ID}-${record.VERSION}.war"
	
	warName = "${record.ARTIFACT_ID}-${record.VERSION}.war"
	
	println "sourcePackage: ${sourcePackage}"
	println "warName: ${warName}"
  }

  // WAR 다운로드
  stage("${desc.package.version} 다운로드") {
	  
	println "== war download start =="

	println "= tempDir delete start ="	
    dir(tempDir) {
      deleteDir()
    }
	println "= tempDir delete end ="	
	
    // downloadDir 변수가 정확한지 확인하기 위해 출력 로그 추가
    println "Target Directory: ${downloadDir}"
    sh "mkdir -p ${downloadDir}"
    
    //Credentials 사용 및 다운로드
        withCredentials([usernamePassword(credentialsId: desc.nexus.credentialsId, 
                                     usernameVariable: 'USER', 
                                     passwordVariable: 'PASS')]) {
        
        println "Downloading to: ${sourcePackage}"
        // -f: 서버 에러 시 실패처리, -sS: 진행바 없이 에러만 표시
        sh "curl -f -u ${USER}:${PASS} -L '${downloadUrl}' -o '${sourcePackage}'"
    }
				
    println "== war download end =="
  }
  
 /****************/
 /*    war 배포   */
 /***************/

  def backupDir = "/mnt/backup/auth"
  def appDir = "/mnt/app-server/auth/webapps"
  def service = "tomcat-auth.service"
  
  // war backup
  stage("백업") {
	echo "== backup start =="
	sh "cp ${appDir}/auth.war ${backupDir}/auth_${downDateTime}.war"
	echo "== backup end =="
  }
  
  // war copy
  stage("배포") {
	echo "== deploy start =="

	sh "sudo systemctl stop ${service}"
	sh "cp ${sourcePackage} ${appDir}/auth.war"
  	sh "sudo systemctl start ${service}"
	
	echo "== deploy end =="
	
	println "= tempDir delete start ="	
    dir(tempDir) {
      deleteDir()
    }
	println "= tempDir delete end ="
  }
  
}
