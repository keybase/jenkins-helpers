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
        wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'XTerm']) {
            try {
                deleteDir()
                closure()
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
    node(label, wrappedClosure)
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
        withCredentials([[$class: 'StringBinding',
            credentialsId: 'SLACK_INTEGRATION_TOKEN',
            variable: 'SLACK_INTEGRATION_TOKEN',
        ]]) {
            slackSend([
                channel: "#ci-notify",
                color: color,
                message: message,
                teamDomain: "keybase",
                token: "${env.SLACK_INTEGRATION_TOKEN}"
            ])
        }
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
    println "${container} logs:"
    //sh "docker-compose logs --tail 100000 ${container}.local"
}

return this
