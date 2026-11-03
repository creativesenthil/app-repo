
cd ~/app-repo

cat > Jenkinsfile <<'JENKINSFILE'
pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 40, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    environment {
        IMAGE_NAME = 'senthilkumarsoundararajan/myapp'
        APP_NAME = 'myapp'
        APP_NAMESPACE = 'default'
        ARGO_APP = 'poc3-myapp'
        ARGO_NAMESPACE = 'argocd'
        K8S_SERVER = 'https://poc3-cluster-control-plane:6443'
    }

    stages {

        stage('Maven Build') {
            steps {
                sh 'mvn -B clean package -DskipTests'
            }
        }

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('sonar') {
                    sh '''
                        mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:3.11.0.3922:sonar
                    '''
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Unit Tests') {
            steps {
                sh 'mvn -B test'
            }
        }

        stage('Docker Build & Push') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'dockerhub-creds',
                        usernameVariable: 'DH_USER',
                        passwordVariable: 'DH_PASS'
                    )
                ]) {
                    sh '''
                        set -e
                        echo "$DH_PASS" | docker login \
                            -u "$DH_USER" --password-stdin

                        docker build \
                            -t "$IMAGE_NAME:$BUILD_NUMBER" .

                        docker push "$IMAGE_NAME:$BUILD_NUMBER"
                        docker logout
                    '''
                }
            }
        }

        stage('Update Kubernetes Manifest') {
            steps {
                sh '''
                    set -e

                    sed -i -E \
                      "s|(image:[[:space:]]*${IMAGE_NAME}:).*|\\1${BUILD_NUMBER}|" \
                      k8s/deployment.yaml

                    grep -F \
                      "image: ${IMAGE_NAME}:${BUILD_NUMBER}" \
                      k8s/deployment.yaml
                '''
            }
        }

        stage('Commit & Push to GitHub') {
            steps {
                withCredentials([
                    string(
                        credentialsId: 'github-token',
                        variable: 'GITHUB_TOKEN'
                    )
                ]) {
                    script {
                        sh '''
                            set -e
                            set +x

                            git config user.name "Senthil Kumar"
                            git config user.email "shaolinsenthil@gmail.com"

                            git add k8s/deployment.yaml

                            git diff --cached --quiet && {
                                echo "No Kubernetes manifest change found"
                                exit 1
                            }

                            git commit -m "Deploy myapp image ${BUILD_NUMBER}"

                            AUTH=$(printf 'x-access-token:%s' "$GITHUB_TOKEN" \
                                | base64 | tr -d '\\n')

                            git -c http.extraheader="AUTHORIZATION: basic $AUTH" \
                                push origin HEAD:main

                            unset AUTH
                        '''

                        env.GITOPS_COMMIT = sh(
                            script: 'git rev-parse HEAD',
                            returnStdout: true
                        ).trim()

                        echo "GitOps commit: ${env.GITOPS_COMMIT}"
                    }
                }
            }
        }

        stage('Argo CD Sync') {
            steps {
                withCredentials([
                    string(
                        credentialsId: 'k8s-jenkins-token',
                        variable: 'K8S_TOKEN'
                    )
                ]) {
                    sh '''
                        set -e

                        KUBECTL="kubectl \
                          --server=$K8S_SERVER \
                          --certificate-authority=/var/jenkins_home/cluster-ca.crt \
                          --token=$K8S_TOKEN"

                        echo "Requesting Argo CD sync for $GITOPS_COMMIT"

                        $KUBECTL patch application "$ARGO_APP" \
                          -n "$ARGO_NAMESPACE" \
                          --type merge \
                          -p "{\\"operation\\":{\\"sync\\":{\\"revision\\":\\"$GITOPS_COMMIT\\"}}}"

                        echo "Waiting for Argo CD synchronization..."

                        for i in $(seq 1 60); do
                            SYNC=$($KUBECTL get application "$ARGO_APP" \
                              -n "$ARGO_NAMESPACE" \
                              -o jsonpath='{.status.sync.status}')

                            HEALTH=$($KUBECTL get application "$ARGO_APP" \
                              -n "$ARGO_NAMESPACE" \
                              -o jsonpath='{.status.health.status}')

                            REVISION=$($KUBECTL get application "$ARGO_APP" \
                              -n "$ARGO_NAMESPACE" \
                              -o jsonpath='{.status.sync.revision}')

                            echo "Sync=$SYNC Health=$HEALTH Revision=$REVISION"

                            if [ "$SYNC" = "Synced" ] &&
                               [ "$HEALTH" = "Healthy" ] &&
                               [ "$REVISION" = "$GITOPS_COMMIT" ]; then
                                echo "Argo CD synchronization successful"
                                exit 0
                            fi

                            sleep 5
                        done

                        echo "Argo CD synchronization failed or timed out"
                        exit 1
                    '''
                }
            }
        }

        stage('Kubernetes Rollout Verification') {
            steps {
                withCredentials([
                    string(
                        credentialsId: 'k8s-jenkins-token',
                        variable: 'K8S_TOKEN'
                    )
                ]) {
                    sh '''
                        set -e

                        kubectl \
                          --server="$K8S_SERVER" \
                          --certificate-authority=/var/jenkins_home/cluster-ca.crt \
                          --token="$K8S_TOKEN" \
                          rollout status deployment/"$APP_NAME" \
                          -n "$APP_NAMESPACE" \
                          --timeout=180s

                        DEPLOYED_IMAGE=$(kubectl \
                          --server="$K8S_SERVER" \
                          --certificate-authority=/var/jenkins_home/cluster-ca.crt \
                          --token="$K8S_TOKEN" \
                          get deployment "$APP_NAME" \
                          -n "$APP_NAMESPACE" \
                          -o jsonpath='{.spec.template.spec.containers[0].image}')

                        EXPECTED_IMAGE="$IMAGE_NAME:$BUILD_NUMBER"

                        echo "Expected image: $EXPECTED_IMAGE"
                        echo "Deployed image: $DEPLOYED_IMAGE"

                        test "$DEPLOYED_IMAGE" = "$EXPECTED_IMAGE"
                    '''
                }
            }
        }

        stage('Application Health Check') {
            steps {
                withCredentials([
                    string(
                        credentialsId: 'k8s-jenkins-token',
                        variable: 'K8S_TOKEN'
                    )
                ]) {
                    sh '''
                        set -e

                        kubectl \
                          --server="$K8S_SERVER" \
                          --certificate-authority=/var/jenkins_home/cluster-ca.crt \
                          --token="$K8S_TOKEN" \
                          port-forward \
                          service/myapp-service 18080:8080 \
                          -n "$APP_NAMESPACE" \
                          >/tmp/myapp-port-forward.log 2>&1 &

                        PF_PID=$!
                        trap 'kill "$PF_PID" 2>/dev/null || true' EXIT

                        echo "Checking application HTTP endpoint..."

                        for i in $(seq 1 30); do
                            if curl --fail --silent \
                              http://127.0.0.1:18080/; then
                                echo
                                echo "Application health check successful"
                                exit 0
                            fi

                            sleep 2
                        done

                        cat /tmp/myapp-port-forward.log
                        echo "Application health check failed"
                        exit 1
                    '''
                }
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true,
                  testResults: 'target/surefire-reports/*.xml'
        }

        success {
            echo "SUCCESS: ${env.JOB_NAME} #${env.BUILD_NUMBER} - CI/CD completed"
        }

        failure {
            echo "FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER} - CI/CD failed"
        }
    }
}
JENKINSFILE
