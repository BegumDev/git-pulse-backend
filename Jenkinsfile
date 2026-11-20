pipeline {
    agent any

    triggers {
        githubPush()
    }

    environment {
        IMAGE_NAME = 'begumdev/git-pulse-backend'
        GIT_SHORT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
    }

    stages {
        stage('Build') {
            steps {
                echo 'Compiling Quarkus backend with Maven...'
                sh './mvnw -B clean compile'
            }
        }

        stage('Test') {
            steps {
                echo 'Running JUnit 5 & Testcontainers/WireMock automated tests...'
                sh './mvnw -B test'
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'target/surefire-reports/TEST-*.xml'
                }
            }
        }

        stage('Package & Containerize') {
            steps {
                echo 'Packaging Quarkus fast-jar and building Docker image...'
                sh './mvnw -B package -DskipTests'
                sh """
                    docker build \
                      -t ${IMAGE_NAME}:${BUILD_NUMBER} \
                      -t ${IMAGE_NAME}:${GIT_SHORT_SHA} \
                      -t ${IMAGE_NAME}:latest .
                """
            }
        }

        stage('Release') {
            when {
                branch 'main'
            }
            steps {
                echo "Releasing versioned container artifact ${IMAGE_NAME}:${BUILD_NUMBER} (${GIT_SHORT_SHA})..."
                // Uncomment when Docker Hub credentials ('dockerhub-creds') are configured in Jenkins:
                // withDockerRegistry([credentialsId: 'dockerhub-creds', url: '']) {
                //     sh "docker push ${IMAGE_NAME}:${BUILD_NUMBER}"
                //     sh "docker push ${IMAGE_NAME}:latest"
                // }
            }
        }

        stage('Deploy') {
            when {
                branch 'main'
            }
            steps {
                echo 'Deploying updated backend container via Docker Compose...'
                sh 'docker compose up -d --build'
            }
        }
    }

    post {
        always {
            sh 'docker image prune -f'
        }
        success {
            echo "Pipeline succeeded for commit ${GIT_SHORT_SHA}!"
        }
        failure {
            echo "Pipeline failed for commit ${GIT_SHORT_SHA}."
        }
    }
}
