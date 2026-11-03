def notifySlack(String msg) {
  withCredentials([string(credentialsId: 'slack-webhook', variable: 'HOOK')]) {
    withEnv(["MSG=${msg}"]) {
      sh '''
        if [ -n "$HOOK" ] && [ "$HOOK" != "none" ]; then
          curl -s -X POST -H 'Content-type: application/json' --data "{\\"text\\":\\"$MSG\\"}" "$HOOK" || true
        fi
      '''
    }
  }
}

pipeline {
  agent any

  options {
    timestamps()
    timeout(time: 30, unit: 'MINUTES')
  }

  stages {

    stage('Build (Maven)') {
      steps {
        sh 'mvn -B clean package -DskipTests'
      }
    }

    stage('SonarQube Analysis') {
      steps {
        withSonarQubeEnv('sonar') {
          sh 'mvn -B org.sonarsource.scanner.maven:sonar-maven-plugin:3.11.0.3922:sonar'
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

    stage('Tests') {
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
            echo "$DH_PASS" | docker login -u "$DH_USER" --password-stdin
            docker build -t "$DH_USER/myapp:$BUILD_NUMBER" .
            docker push "$DH_USER/myapp:$BUILD_NUMBER"
            docker logout
          '''
        }
      }
    }

    stage('Kubernetes Connectivity Test') {
      steps {
        withCredentials([
          string(
            credentialsId: 'k8s-jenkins-token',
            variable: 'K8S_TOKEN'
          )
        ]) {
          sh '''
            echo "Checking Kubernetes credential..."
            echo "Token length: ${#K8S_TOKEN}"

            kubectl \
              --server=https://poc3-cluster-control-plane:6443 \
              --certificate-authority=/var/jenkins_home/cluster-ca.crt \
              --token="$K8S_TOKEN" \
              auth can-i get deployments -n default
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
      script {
        notifySlack("SUCCESS: ${env.JOB_NAME} #${env.BUILD_NUMBER} - image pushed and Kubernetes connectivity verified ${env.BUILD_URL}")
      }
    }

    failure {
      script {
        notifySlack("FAILED: ${env.JOB_NAME} #${env.BUILD_NUMBER} - pipeline failed ${env.BUILD_URL}")
      }
    }
  }
}
