// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Mandatory configuration
    def gitUrl = config["git"]
    assert gitUrl : "git should be specified"

    // Optional configuration
    def projectName = config["name"] ?: env.JOB_BASE_NAME
    def dockerfile = config["dockerfile"] ?: "oss-fuzz/$projectName/Dockerfile"
    def sanitizers = config["sanitizers"] ?: ["address"]
    def checkoutDir = config["checkoutDir"] ?: projectName
    def dockerContextDir = config["dockerContextDir"]

    def date = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")
        .format(java.time.LocalDateTime.now())
    def ossFuzzUrl = 'https://github.com/google/oss-fuzz.git'

    node {
      def workspace = pwd()
      def revisionsFile = "$workspace/${projectName}.rev"
      def dockerTag = "ossfuzz/$projectName"
      echo "Building $dockerTag"

      stage("docker image") {
          def revisions = [:]
          dir('oss-fuzz') {
              git url: ossFuzzUrl
          }

          dir(checkoutDir) {
              git url: gitUrl
              revisions[gitUrl] = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
          }

          if (dockerContextDir == null) {
            dockerContextDir = new File(dockerfile)
                .getParentFile()
                .getPath();
          }

          sh "docker build -t $dockerTag -f $dockerfile $dockerContextDir"

          def revText = groovy.json.JsonOutput.toJson(revisions)
          writeFile file: revisionsFile, text: revText
          echo "revisions: $revText"
      }

      for (int i = 0; i < sanitizers.size(); i++) {
        def sanitizer = sanitizers[i]
        dir(sanitizer) {
          def out = "$workspace/out/$sanitizer"
          stage("$sanitizer sanitizer") {
            // Run image to produce fuzzers
            sh "rm -rf $out"
            sh "mkdir -p $out"
            sh "docker run -v $workspace/$checkoutDir:/src/$checkoutDir -v $workspace/oss-fuzz:/src/oss-fuzz -v $out:/out -e SANITIZER_FLAGS=\"-fsanitize=$sanitizer\" -t $dockerTag"

            // Copy dict and options files
            sh "cp $workspace/oss-fuzz/$projectName/*.dict $out/ || true"
            sh "cp $workspace/oss-fuzz/$projectName/*.options $out/ || true"
          }
        }
      }

      // Run each of resulting fuzzers.
      dir ('out') {
        def resultsDir = "$workspace/test-results"
        sh "rm -rf $resultsDir"
        sh "mkdir -p $resultsDir"
        stage("running fuzzers") {
          def fuzzersFound = 0
          sh "ls -alR"
          for (int i = 0; i < sanitizers.size(); i++) {
            def sanitizer = sanitizers[i]
            dir (sanitizer) {
              def testReport = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuites><testsuite name=\"$projectName-$sanitizer\">";
                
              def d = pwd()
              def files = findFiles()
              for (int j = 0; j < files.size(); j++) {
                def file = files[j]
                if (file.directory) { continue }
                if (!new File(d, file.name).canExecute()) {
                    echo "skipping: $file"
                    continue
                }
                sh "docker run -v $d:/out -t ossfuzz/libfuzzer-runner /out/$file -runs=1"
                fuzzersFound += 1
              }
                
              testReport += "</testsuite></testsuites>";
              writeFile file:"$resultsDir/TEST-${sanitizer}.xml", text:testReport
            }
          }
          // sh "ls -al $resultsDir/"
          // step([$class: 'JUnitResultArchiver', testResults: '**/TEST-*.xml'])
          echo "Tested $fuzzersFound fuzzer"
          if (!fuzzersFound) {
            error "no fuzzers found";
          }
        }

        stage("uploading") {
          for (int i = 0; i < sanitizers.size(); i++) {
            def sanitizer = sanitizers[i]
            dir (sanitizer) {
              def zipFile = "$projectName-$sanitizer-${date}.zip"
              def revFile = "$projectName-$sanitizer-${date}.rev"
              sh "cp $revisionsFile $revFile"
              sh "zip -j $zipFile *"
              sh "gsutil cp $zipFile gs://clusterfuzz-builds/$projectName/"
              sh "gsutil cp $revFile gs://clusterfuzz-builds/$projectName/"
            }
          }
        }

        stage("pushing image") {
          docker.withRegistry('', 'docker-login') {
            docker.image(dockerTag).push()
          }
        }
      }
    }

  echo 'Done'
}

return this;
