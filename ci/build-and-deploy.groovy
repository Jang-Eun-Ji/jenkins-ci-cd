// build-and-deploy.groovy
// Purpose: GitLab에서 소스 체크아웃 → Maven 빌드 → Nexus에 WAR 배포 (CI)
// Note: Public/Sanitized version - 내부 URL/Credential/기관명 제거, 환경변수 기반 구성

#!groovy

/*
 * ======= Sanitized Config =======
 * 아래 값들은 Jenkins > 파이프라인 설정 > 환경변수(Environment Variables)에서 지정하세요.
 *
 * - GIT_BASE_URL         : GitLab base URL (예: ssh://git@gitlab.example.com:10022)
 * - GIT_SUB_GROUP        : GitLab 서브그룹명 (예: fhir)
 * - GIT_PROJECT          : GitLab 프로젝트명 (예: fhir-auth)
 * - GIT_CREDENTIALS_ID   : Jenkins에 등록된 GitLab SSH credentialId
 * - DEFAULT_BRANCH        : 기본 브랜치명 (예: dev)
 *
 * - NEXUS_REPO_ID         : Nexus 저장소 ID (예: release-repository)
 * - NEXUS_REPO_URL        : Nexus 저장소 URL (예: https://nexus.example.com/repository/my-repo/)
 * - NEXUS_SERVER_ID       : Maven settings.xml 서버 ID (예: my-nexus-server)
 * - NEXUS_CREDENTIALS_ID  : Jenkins에 등록된 Nexus credentialId
 *
 * - MAVEN_TOOL_NAME       : Jenkins Global Tool에 등록된 Maven 이름
 * - JAVA_TOOL_NAME        : Jenkins Global Tool에 등록된 JDK 이름
 *
 * - ARTIFACT_GROUP_ID     : Maven groupId (예: com.example)
 * - ARTIFACT_ID           : Maven artifactId (예: fhir-auth)
 * - ARTIFACT_VERSION      : 배포 버전 (예: 1.0.0-SNAPSHOT)
 */

String getBranchName(branch) {
    branchTemp = sh returnStdout: true, script: """echo "$branch" |sed -E "s#origin/##g" """
    if (branchTemp) {
        branchTemp = branchTemp.trim()
    }
    return branchTemp
}

node {

    def GIT_BASE_URL        = (env.GIT_BASE_URL        ?: "ssh://git@gitlab.example.com:10022").trim()
    def GIT_SUB_GROUP       = (env.GIT_SUB_GROUP       ?: "my-group").trim()
    def GIT_PROJECT         = (env.GIT_PROJECT         ?: "my-project").trim()
    def GIT_CREDENTIALS_ID  = (env.GIT_CREDENTIALS_ID  ?: "git-ssh-credentials").trim()
    def DEFAULT_BRANCH      = (env.DEFAULT_BRANCH      ?: "dev").trim()

    def NEXUS_REPO_ID       = (env.NEXUS_REPO_ID       ?: "release-repository").trim()
    def NEXUS_REPO_URL      = (env.NEXUS_REPO_URL      ?: "https://nexus.example.com/repository/my-repo/").trim()
    def NEXUS_SERVER_ID     = (env.NEXUS_SERVER_ID     ?: "my-nexus-server").trim()
    def NEXUS_CREDENTIALS_ID = (env.NEXUS_CREDENTIALS_ID ?: "nexus-credentials").trim()

    def MAVEN_TOOL_NAME     = (env.MAVEN_TOOL_NAME     ?: "Maven").trim()
    def JAVA_TOOL_NAME      = (env.JAVA_TOOL_NAME      ?: "JDK17").trim()

    def ARTIFACT_GROUP_ID   = (env.ARTIFACT_GROUP_ID   ?: "com.example").trim()
    def ARTIFACT_ID         = (env.ARTIFACT_ID         ?: GIT_PROJECT).trim()
    def ARTIFACT_VERSION    = (env.ARTIFACT_VERSION    ?: "1.0.0-SNAPSHOT").trim()

    def branchName
    def gitCommitId
    def startedBy = '{Unknown}'

    stage('Prepare') {
        env.JAVA_HOME  = tool "${JAVA_TOOL_NAME}"
        env.MAVEN_HOME = tool "${MAVEN_TOOL_NAME}"
        env.PATH = "${env.PATH}:${env.JAVA_HOME}/bin:${env.MAVEN_HOME}/bin"

        if (currentBuild.buildCauses.size() > 0) {
            buildCause = currentBuild.buildCauses[0]
            switch (buildCause._class) {
                case "hudson.model.Cause\$RemoteCause":
                    startedBy = "remote host ${buildCause.addr}"
                    break
                case "com.dabsquared.gitlabjenkins.cause.GitLabWebHookCause":
                    if (buildCause.shortDescription != null) {
                        startIdx = buildCause.shortDescription.indexOf("GitLab")
                        startedBy = buildCause.shortDescription.substring(startIdx)
                    }
                    break
                default:
                    if (buildCause.shortDescription != null) {
                        startIdx = buildCause.shortDescription.indexOf(" by ") + 4
                        startedBy = buildCause.shortDescription.substring(startIdx)
                    }
                    break
            }
        }
        env.startedBy = startedBy
    }

    stage('Checkout') {
        branchName = params.branch != null ? getBranchName(params.branch) : DEFAULT_BRANCH

        gitObj = git url: "${GIT_BASE_URL}/${GIT_SUB_GROUP}/${GIT_PROJECT}.git",
                credentialsId: "${GIT_CREDENTIALS_ID}",
                branch: "${branchName}"
        gitCommitId = gitObj.GIT_COMMIT.substring(0, 8)

        println "*********** Target ***********\n" +
                "   Project: ${GIT_PROJECT}\n" +
                "   Branch:  ${branchName}\n" +
                "   commitId: ${gitCommitId}\n" +
                "******************************"
    }

    stage('Build') {
        sh "mvn clean install -DskipTests"
    }

    stage('Deploy to Nexus') {
        withCredentials([usernamePassword(credentialsId: "${NEXUS_CREDENTIALS_ID}", usernameVariable: 'id', passwordVariable: 'pass')]) {
            def artifact = findFiles(glob: 'target/ROOT.war').first()
            if (artifact != null) {
                def credentials = "-D${NEXUS_REPO_ID}.username=${id} -D${NEXUS_REPO_ID}.password=${pass}"

                sh """mvn clean install deploy:deploy-file -DskipTests \
                    -Dfile=${artifact} \
                    -DgroupId=${ARTIFACT_GROUP_ID} \
                    -DartifactId=${ARTIFACT_ID} \
                    -Dversion=${ARTIFACT_VERSION} \
                    -Dpackaging=war \
                    -Durl=${NEXUS_REPO_URL} \
                    -DrepositoryId=${NEXUS_SERVER_ID} \
                    ${credentials}"""
            } else {
                error 'WAR 파일을 찾을 수 없습니다.'
            }
        }
    }
}
