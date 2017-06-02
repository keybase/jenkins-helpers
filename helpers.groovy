def waitForURL(prefix, url) {
    def waitFor = 300;
    if (isUnix()) {
        sh """ bash -c '
            slept=0
            while [[ "\$slept" -lt "${waitFor}" ]]; do
                curl -s -o /dev/null ${url} && echo "Connected to ${url} after waiting \$slept times" && exit 0;
                sleep 1;
                ((slept++));
            done;
            echo "Unable to connect to ${url} after waiting ${waitFor} times";
            exit 1;
        ' """
    } else {
        bat """
            powershell.exe -c "& { \
                \$slept=0; \
                do { \
                    curl.exe --silent ${url} >\$null; \
                    if (\$?) { \
                        echo \\"Connected to ${url} after waiting \$slept times\\"; \
                        exit 0; \
                    } \
                    sleep 1; \
                    \$slept++; \
                } while (\$slept -lt ${waitFor}); \
                echo \\"Unable to connect to ${url} after waiting \$slept times\\"; \
                exit 1; \
            }"
        """
    }
}

// Need to separate this out because cause is not serializable and thus state
// cannot be saved. @NonCPS makes this method run as native and thus cannot be
// re-entered.
@NonCPS
def getCauseString(currentBuild) {
    def cause = currentBuild.getRawBuild().getCause(hudson.model.Cause)
    if (cause in hudson.model.Cause.UpstreamCause) {
        return "upstream"
    } else if (cause in hudson.model.Cause.UserIdCause) {
        return "user: ${cause.getUserName()}"
    } else {
        return "other"
    }
}

def nodeWithCleanup(label, handleError, cleanup, closure) {
    def wrappedClosure = {
        def build_path = pwd()
        dir('..') {
            build_path = [pwd(), env.JOB_NAME, env.BUILD_NUMBER].join('/')
        }
        ws(build_path) {
            timestamps {
                wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'XTerm']) {
                    try {
                        deleteDir()
                        timeout(60) {
                            closure()
                        }
                    } catch (ex) {
                        try {
                            handleError()
                        } catch (ex2) {
                            println "Unable to handle error: ${ex2.getMessage()}"
                        }
                        throw ex
                    } finally {
                        try {
                            cleanup()
                        } catch (ex2) {
                            println "Unable to cleanup: ${ex2.getMessage()}"
                        }
                        deleteDir()
                    }
                }
            }
        }
    }
    try {
        node(label, wrappedClosure)
    } finally {
        deleteDir()
    }
}

def nodeWithDockerCleanup(label, handleError, cleanup, closure) {
    def wrappedCleanup = {
        sh 'docker rm -v $(docker ps --filter status=exited -q 2>/dev/null) 2>/dev/null || echo "No Docker containers to remove"'
        sh 'docker rmi $(docker images --filter dangling=true -q --no-trunc 2>/dev/null) 2>/dev/null || echo "No Docker images to remove"'
        sh 'docker volume rm $(docker volume ls -qf dangling=true 2>/dev/null) 2>/dev/null || echo "No Docker volumes to remove"'
        cleanup()
    }
    nodeWithCleanup(label, handleError, wrappedCleanup, closure)
}

def rootLinuxNode(env, handleError, cleanup, closure) {
    if (env.CHANGE_TITLE && env.CHANGE_TITLE.contains('[ci-skip]')) {
        println "Skipping build because PR title contains [ci-skip]"
    } else {
        nodeWithDockerCleanup("linux", handleError, cleanup, closure)
    }
}

def slackMessage(channel, color, message) {
    withCredentials([[$class: 'StringBinding',
        credentialsId: 'SLACK_INTEGRATION_TOKEN',
        variable: 'SLACK_INTEGRATION_TOKEN',
    ]]) {
        slackSend([
            channel: channel,
            color: color,
            message: message,
            teamDomain: "keybase",
            token: "${env.SLACK_INTEGRATION_TOKEN}"
        ])
    }
}

slackUserLookup = [:]
slackUserLookup['aalness'] = 'andy'
slackUserLookup['akalin-keybase'] = 'akalin'
slackUserLookup['chrisnojima'] = 'cnojima'
slackUserLookup['chromakode'] = 'mgood'
slackUserLookup['cjb'] = 'cjb'
slackUserLookup['gabriel'] = 'gabriel'
slackUserLookup['jinyangli'] = 'jinyang'
slackUserLookup['jzila'] = 'jzila'
slackUserLookup['malgorithms'] = 'chriscoyne'
slackUserLookup['marcopolo'] = 'marco'
slackUserLookup['maxtaco'] = 'max'
slackUserLookup['mlsteele'] = 'miles'
slackUserLookup['mmaxim'] = 'mike'
slackUserLookup['oconnor663'] = 'jack'
slackUserLookup['patrickxb'] = 'patrick'
slackUserLookup['songgao'] = 'songgao'
slackUserLookup['strib'] = 'strib'
slackUserLookup['zanderz'] = 'steve'
slackUserLookup['cbostrander'] = 'caley'
slackUserLookup['cecileboucheron'] = 'cecile'
slackUserLookup['taruti'] = 'taru'
slackUserLookup['awendland'] = 'awendland'
slackUserLookup['jxguan'] = 'guan'
slackUserLookup['mpcsh'] = 'mpcsh'
slackUserLookup['shazow'] = 'shazow'
slackUserLookup['zapu'] = 'michal'

def slackOnError(repoName, env, currentBuild) {
    def cause = getCauseString(currentBuild)
    if (cause == "upstream") {
        return
    }
    def message = null
    def color = "warning"
    if (env.CHANGE_ID) {
        def author = env.CHANGE_AUTHOR.toLowerCase()
        if (slackUserLookup.containsKey(author)) {
            author = "<@${slackUserLookup[author]}>"
        }
        message = "<${env.CHANGE_URL}|${env.CHANGE_TITLE}>\n :small_red_triangle: Test failed: <${env.BUILD_URL}|${env.JOB_NAME} ${env.BUILD_DISPLAY_NAME}> by ${author}"
    } else if (env.BRANCH_NAME == "master" && env.AUTHOR_NAME) {
        def commitUrl = "https://github.com/keybase/${repoName}/commit/${env.COMMIT_HASH}"
        color = "danger"
        message = "*BROKEN: master on keybase/${repoName}*\n :small_red_triangle: Test failed: <${env.BUILD_URL}|${env.JOB_NAME} ${env.BUILD_DISPLAY_NAME}>\n Commit: <${commitUrl}|${env.COMMIT_HASH}>\n Author: ${env.AUTHOR_NAME} &lt;${env.AUTHOR_EMAIL}&gt;"
    }
    if (message) {
        slackMessage("#ci-notify", color, message)
    }
}

def withKbweb(closure) {
    try {
        retry(5) {
            sh "docker-compose up -d mysql.local"
        }
        sh "docker-compose up -d kbweb.local"

        closure()
    } catch (ex) {
        println "Dockers:"
        sh "docker ps -a"
        sh "docker-compose stop"
        logContainer('mysql')
        logContainer('gregor')
        logContainer('kbweb')
        throw ex
    } finally {
        sh "docker-compose down"
    }
}

def logContainer(container) {
    sh "docker-compose logs ${container}.local | gzip > ${container}.log.gz"
    archive("${container}.log.gz")
}

// Check if the current directory has any git changes. Return a list of all the
// files changed, if any.  If we're on the master branch, return a list with a
// single item: "master". This is to allow length of list checks.
def getChanges(commitHash, changeTarget) {
    if (changeTarget == null) {
        println "Missing changeTarget, so we're on master."
        return ["master"]
    }
    def branchName = "origin/$changeTarget"
    def changeBase = sh(returnStdout: true, script: "git merge-base $branchName $commitHash").trim()
    println "Received commit $commitHash, change target $changeTarget, change base $changeBase"

    retry(3) {
        sh "git fetch"
    }
    try {
        def diffFiles = sh(returnStdout: true, script: "git diff --name-only \"${changeBase}...${commitHash}\" .").trim()
        if (diffFiles.size() == 0) {
            return []
        }
        return diffFiles.split("[\\r\\n]+")
    } catch(e) {
        println "no changes"
        return []
    }
}

return this
