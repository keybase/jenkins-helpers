def waitForURL(prefix, url) {
  return waitForURLWithTimeout(prefix, url, 300)
}

def waitForURLWithTimeout(prefix, url, timeout) {
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
      wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'XTerm']) {
        try {
          deleteDir()
          timeout(90) {
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
          try {
            deleteDir()
          } catch (ex2) {
            println "Unable to remove directory: ${ex2.getMessage()}"
          }
        }
      }
    }
  }
  node(label, wrappedClosure)
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

def slackOnError(repoName, env, currentBuild) {
  // Disable for now
  return

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
    // TODO: notify in here if needed
  }
}

def withKbweb(closure) {
  try {
    retry(5) {
      sh "docker-compose up -d mysql.local"
    }
    // Give MySQL a few seconds to start up.
    sleep(10)
    sh "docker-compose up -d kbweb.local"

    closure()
  } catch (ex) {
    println "Dockers:"
    sh "docker ps -a"
    sh "docker-compose stop"
    logContainer('docker-compose', 'mysql')
    logContainer('docker-compose', 'gregor')
    logContainer('docker-compose', 'kbweb')
    throw ex
  } finally {
    sh "docker-compose down"
  }
}

def containerName(composefile, container) {
  return sh(returnStdout: true, script: "docker-compose -f ${composefile}.yml ps -q ${container}.local").trim()
}

def logContainer(composefile, container) {
  sh "docker-compose -f ${composefile}.yml logs ${container}.local | gzip > ${container}.log.gz"
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
  sh "git fetch origin +refs/heads/master:refs/remotes/origin/master"
  sh "git config --list"
  def branchName = sh(returnStdout: true, script: "git rev-list -n 1 origin/master").trim()
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

def getChangesForSubdir(subdir, env) {
    dir(subdir) {
        return getChanges(env.COMMIT_HASH, env.CHANGE_TARGET)
    }
}

def hasChanges(subdir, env) {
    def changes = getChangesForSubdir(subdir, env)
    return changes.size() != 0
}

return this
