import com.ft.jenkins.cluster.ClusterType
import com.ft.jenkins.cluster.Region
import com.ft.jenkins.deployment.Deployments
import com.ft.jenkins.cluster.Environment
import com.ft.jenkins.cluster.EnvsRegistry
import com.ft.jenkins.provision.ClusterUpdateInfo
import com.ft.jenkins.provision.Provisioners

def call() {
    node("docker") {
        stage('initial cleanup') {
            cleanWs()
        }

        Deployments deployments = new Deployments()
        String app = "upp-dex-config"
        String gitBranch = env."Dex Config Git branch"
        String chartFolderLocation = "helm/" + app

        def configMap = readJSON text: env."Dex config"

        configMap.each { String clusterName, Map<String, String> secrets ->
            stage(clusterName) {
                ClusterUpdateInfo clusterUpdateInfo = Provisioners.getClusterUpdateInfo(clusterName)
                if (clusterUpdateInfo == null || clusterUpdateInfo.clusterType == null || clusterUpdateInfo.region == null) {
                    throw new IllegalArgumentException("Cannot extract cluster info from cluster name " + clusterName)
                }
                ClusterType targetCluster = ClusterType.toClusterType(clusterUpdateInfo.clusterType)
                if (targetCluster == null) {
                    if (clusterName.contains("pac")) {
                        targetCluster = ClusterType.PAC
                    } else {
                        throw new IllegalArgumentException("Unknown cluster" + clusterUpdateInfo.clusterType)
                    }
                }
                Region targetRegion = clusterUpdateInfo.region
                if (targetRegion == null) {
                    throw new IllegalArgumentException("Cannot determine region from cluster name: " + clusterName)
                }
                Environment targetEnv
                for (Environment env in EnvsRegistry.envs) {
                    if (clusterName == env.getClusterSubDomain(targetCluster, targetRegion)) {
                        targetEnv = env
                        break
                    }
                }
                if (targetEnv == null) {
                    throw new IllegalArgumentException("Cannot determine target env from cluster name: " + clusterName)
                }
                checkoutDexConfig(app, gitBranch)

                String valuesFile = "values.yaml"
                writeFile([file: valuesFile, text: buildHelmValues2(secrets, clusterName)])

                String helmDryRunOutput = "output.txt"
                deployments.runWithK8SCliTools(targetEnv, targetCluster, targetRegion, {
                    sh "helm upgrade --debug --dry-run ${app} ${chartFolderLocation} -i --timeout 1200 -f ${valuesFile} > ${helmDryRunOutput}"
                })

                String dexSecretFile = writeDexSecret(helmDryRunOutput)

                encodeDexSecrets(dexSecretFile)
                sh "rm ${chartFolderLocation}/templates/dex-config.yaml"
                sh "mv ${dexSecretFile} ${chartFolderLocation}/templates/dex-config.yaml"

                deployments.runWithK8SCliTools(targetEnv, targetCluster, targetRegion, {
                    sh """
                        kubectl apply -f ${chartFolderLocation}/templates/dex-config.yaml --validate=false;
                        sleep 5; kubectl scale deployment content-auth-dex --replicas=0;
                        sleep 5; kubectl scale deployment content-auth-dex --replicas=2;
                        sleep 15; kubectl get pod --selector=app=content-auth-dex"""
                })
            }
        }

        stage('cleanup') {
            cleanWs()
        }
    }

}

private void encodeDexSecrets(String dexSecretFile) {
    docker.image("ruby:2.5.1-slim-stretch").inside() {
        sh "gem install kube_secrets_encode"
        sh "kube_secrets --file=${dexSecretFile} --yes > /dev/null"
    }
}

private String writeDexSecret(String helmDryRunOutput) {
    String output = readFile(helmDryRunOutput)
    def dexSecret = ""
    def writeLine = false
    String[] lines = output.split("\n")
    lines.each { String line ->
        if (writeLine) {
            dexSecret = dexSecret + "\n" + line
        }
        if (line.contains("dex-config.yaml")) {
            writeLine = true
        }
        if (writeLine && line.contains("enablePasswordDB")) {
            writeLine = false
        }
    }
    String dexSecretFile = "secrets.txt"
    writeFile([file: dexSecretFile, text: dexSecret])
    dexSecretFile
}

private Object checkoutDexConfig(String app, String gitBranch) {
    checkout([$class           : 'GitSCM',
              branches         : [[name: gitBranch]],
              userRemoteConfigs: [[url: "git@github.com:Financial-Times/${app}.git", credentialsId: "ft-upp-team"]]
    ])
}

private static String buildHelmValues2(Map<String, String> secrets, String clusterName) {
    String helmValues = "kubectl:\n  login:\n    secret: " +
            '"' + secrets."kubectl.login.secret" + '"' + "\ncluster:\n  name: " + '"' + clusterName + '"' +
            "\nldap:\n  host: " + '"' + secrets."ldap.host" + '"' + "\n  bindDN: " + '"' + secrets."ldap.bindDN" + '"' +
            "\n  bindPW: " + '"' + secrets."ldap.bindPW" + '"' + "\n"
    helmValues
}
