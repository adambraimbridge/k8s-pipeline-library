package com.ft.jenkins.deployment

import com.ft.jenkins.appconfigs.AppConfig
import com.ft.jenkins.appconfigs.AppConfigs
import com.ft.jenkins.aws.Aws
import com.ft.jenkins.cluster.*
import com.ft.jenkins.exceptions.ClusterAuthenticationException
import com.ft.jenkins.exceptions.ConfigurationNotFoundException
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.Lists
import com.google.common.collect.Multimap
import com.google.common.collect.Sets
import org.apache.commons.lang3.SerializationUtils

import java.util.regex.Matcher

import static DeploymentsConstants.K8S_CLI_IMAGE
import static com.ft.jenkins.deployment.DeploymentsConstants.EKS_PROVISIONER_IMAGE
import static com.ft.jenkins.deployment.HelmConstants.*

void deployApps(String chartName, String chartVersion, Environment targetEnv, ClusterType deployOnlyInCluster, Region deployOnlyInRegion, Closure codeToRun) {
  fetchChart(chartName, chartVersion)
  AppConfigs appConfigs = new AppConfigs()
  Multimap<ClusterType, AppConfig> clusterTypeToAppConfigMap = appConfigs.getClusterTypeToAppConfigPairsFromAppsInChart(chartName)

  List<AppConfig> filteredAppConfigs = []
  List<AppConfig> invalidAppConfigs = []
  for (ClusterType clusterType in clusterTypeToAppConfigMap.keySet()) {
    List<AppConfig> appConfigsPerClusterType = Lists.newArrayList(clusterTypeToAppConfigMap.get(clusterType))

    List<AppConfig> currentInvalidAppConfigs = appConfigsPerClusterType.findAll { it.isInvalidClusterType || it.isInvalidEnvironment || it.isInvalidRegion }
    invalidAppConfigs.addAll(currentInvalidAppConfigs)
    appConfigsPerClusterType.removeAll(currentInvalidAppConfigs)

    List<AppConfig> currentAppConfigs = AppConfigs.filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(targetEnv, appConfigsPerClusterType, deployOnlyInCluster, deployOnlyInRegion)
    filteredAppConfigs.addAll(currentAppConfigs)
  }
  filteredAppConfigs = AppConfigs.filterAppConfigsBasedOnMostSpecificDeployments(filteredAppConfigs, deployOnlyInRegion)

  logInvalidAppConfigs(invalidAppConfigs)
  logDeploymentCandidateAppConfigs(filteredAppConfigs)

  List<AppConfig> deployedApps = deployAppsInChartWithHelm(chartName, filteredAppConfigs, targetEnv, deployOnlyInCluster, deployOnlyInRegion)
//  Multimap<ClusterType, AppConfig> deployedClusterTypeToAppConfigMap = AppConfigs.toClusterTypeAppConfigMultimap(deployedApps)
  // Disable sending Slack notifications at the end of the build pipeline. This should be fixed!
  Multimap<ClusterType, AppConfig> deployedClusterTypeToAppConfigMap = ArrayListMultimap.create()

  String buildDesc = getBuildDescription(deployedApps, chartVersion, deployOnlyInRegion, targetEnv)
  codeToRun.call(deployedClusterTypeToAppConfigMap, targetEnv, buildDesc)
}

private logDeploymentCandidateAppConfigs(List<AppConfig> filteredAppConfigs) {
  List<String> filteredAppConfigNames = filteredAppConfigs.collect { it.toConfigFileName() }
  if (!filteredAppConfigs.isEmpty()) {
    echo """
Preparing following app configs as deployment candidates: 
---------------------------------------------------------
${filteredAppConfigNames.join("\n")}"""
  } else {
    echo "WARNING: No available deployment candidate app configs"
  }
}

private void logInvalidAppConfigs(List<AppConfig> invalidAppConfigs) {
  List<String> invalidAppConfigNamesWithCauses = invalidAppConfigs.collect { "${it.origConfigFileName} caused by -> ${it.toConfigFileName()}".toString() }
  if (!invalidAppConfigNamesWithCauses.isEmpty()) {
    echo """
Invalid app configs that will NOT be deployed and have to be fixed:
-------------------------------------------------------------------
${invalidAppConfigNamesWithCauses.join("\n")}
"""
  } else {
    echo "No invalid app configs"
  }
}

List<AppConfig> deployAppsInChartWithHelm(String chartFolderLocation,
                                          List<AppConfig> appsToDeploy,
                                          Environment targetEnv,
                                          ClusterType deployOnlyInCluster = null,
                                          Region deployOnlyToRegion = null) {
  List<AppConfig> appsWithRegion = appsToDeploy.findAll { it.region }
  echo "APPS WITH REGION: ${appsWithRegion.collect { it.toConfigFileName() }}"
  List<AppConfig> appsWithoutRegion = appsToDeploy.findAll { !it.region }
  echo "APPS WITHOUT REGION: ${appsWithoutRegion.collect { it.toConfigFileName() }}"

  appsWithRegion = executeDeploymentForAppsWithRegion(
          appsWithRegion, chartFolderLocation, deployOnlyInCluster, deployOnlyToRegion)

  List<AppConfig> appsWithoutRegionAllDeployments = executeDeploymentForAppsWithoutRegion(
          appsWithoutRegion, targetEnv, deployOnlyToRegion, chartFolderLocation, deployOnlyInCluster)

  List<AppConfig> deployedApps = appsWithRegion + appsWithoutRegionAllDeployments

  deployedApps
}

List<AppConfig> executeDeploymentForAppsWithRegion(
        List<AppConfig> appsWithRegion,
        String chartFolderLocation,
        ClusterType deployOnlyInCluster,
        Region deployOnlyToRegion) {
  for (AppConfig app : appsWithRegion) {
    List<Region> regionsToDeployTo = app.environment.getRegionsToDeployTo(deployOnlyToRegion)
    if (regionsToDeployTo.contains(app.region)) {
      executeAppDeployment(app, chartFolderLocation, app.environment, deployOnlyInCluster, app.region)
    } else {
      app.ignoredDeployment = true
      app.ignoreCauseMessage = "Deployment with non allowed region"
    }
  }
  appsWithRegion
}

List<AppConfig> executeDeploymentForAppsWithoutRegion(List<AppConfig> appsWithoutRegion,
                                                      Environment targetEnv,
                                                      Region deployOnlyToRegion,
                                                      String chartFolderLocation,
                                                      ClusterType deployOnlyInCluster) {
  List<AppConfig> appsWithoutRegionAllDeployments = []
  for (AppConfig app : appsWithoutRegion) {
    // if app config has no specified environment - search for it based on its cluster type and the environment name from the build
    Environment currentEnv = app.environment ?: EnvsRegistry.getEnvironment(app.clusterType, targetEnv.name)
    // app config has no region so deploy to all available regions in the environment or allowed from the current build
    if (currentEnv) {
      List<Region> regionsToDeployTo = currentEnv.getRegionsToDeployTo(deployOnlyToRegion)
      echo "REGIONS TO DEPLOY TO for ${app.origConfigFileName}: ${regionsToDeployTo}"
      for (Region region : regionsToDeployTo) {
        AppConfig currentApp = SerializationUtils.clone(app)
        executeAppDeployment(currentApp, chartFolderLocation, currentEnv, deployOnlyInCluster, region)
        currentApp.region = region
        appsWithoutRegionAllDeployments.add(currentApp)
      }
    } else {
      echo "Cannot deploy ${app.origConfigFileName} on ${app.clusterType} because ${targetEnv.name} does not exist on it"
      app.ignoredDeployment = true
      app.ignoreCauseMessage = "Non existent environment ${targetEnv.name} on ${app.clusterType}"
      appsWithoutRegionAllDeployments.add(app)
    }
  }
  appsWithoutRegionAllDeployments
}

void executeAppDeployment(AppConfig app, String chartFolderLocation, Environment targetEnv, ClusterType deployOnlyInCluster = null, Region region = null) {
  echo "STARTING DEPLOYMENT of ${app.origConfigFileName} in ${region}..."
  boolean deployOnlyClusterAvailable = deployOnlyInCluster && deployOnlyInCluster != ClusterType.ALL_IN_CHART
  ClusterType realClusterType = deployOnlyClusterAvailable ? deployOnlyInCluster : targetEnv.cluster.clusterType

  runWithK8SCliTools(targetEnv, realClusterType, region, {
    AppConfigs appConfigs = new AppConfigs()
    String configurationFileName = appConfigs.getAppConfigFileName(app, chartFolderLocation)
    if (!configurationFileName) {
      throw new ConfigurationNotFoundException(
              "Cannot find app configuration file ${configurationFileName}. Maybe it does not meet the naming conventions.")
    }

    Map result = app.shouldBeIgnoredForDeployment(targetEnv, realClusterType, region)
    echo result.message.toString()
    if (result.ignored) {
      return
    }

    String targetClusterUrl = targetEnv.getClusterSubDomain(realClusterType, region)
    echo "Using app config file ${configurationFileName} to deploy with helm"
    def params = [(CHART_NAME_COMMAND_PARAM)               : chartFolderLocation,
                  (CHART_RELEASE_NAME_COMMAND_PARAM)       : app.appName,
                  (VALUES_FILE_PATH_COMMAND_PARAM)         : configurationFileName,
                  (REGION_COMMAND_PARAM)                   : region.name,
                  (TARGET_ENV_COMMAND_PARAM)               : targetEnv.name,
                  (TARGET_CLUSTER_SUB_DOMAIN_COMMAND_PARAM): targetClusterUrl,
                  (CLUSTER_URLS_COMMAND_PARAM)             : getClusterUrlsAsHelmValues(targetEnv, realClusterType, region),
                  (GLB_URLS_COMMAND_PARAM)                 : getGlbUrlsAsHelmValues(targetEnv),
                  (TARGET_ENV_NAMESPACE_PARAM)             : targetEnv.namespace
    ]
    sh Helm.generateCommand(HelmCommand.UPGRADE, params, targetEnv, region)
  })
}

static String getClusterUrlsAsHelmValues(Environment environment, ClusterType clusterType, Region region) {
  String clusterUrl = environment.getClusterMapEntry(clusterType, region)?.publicEndpoint
  def result = " --set cluster.${clusterType.label}.url=${clusterUrl}"
  result
}

static String getGlbUrlsAsHelmValues(Environment environment) {
  def result = ""
  environment.glbMap.each { entry ->
    result += " --set glb.${entry.key.toLowerCase()}.url=${entry.value}"
  }
  result
}

// WARNING: This is not tested, probably has to be reworked.
void removeAppsInChartWithHelm(String chartName, String chartVersion, Environment targetEnv,
                               ClusterType onlyFromCluster = null, Region region = null) {
  /*  fetch the chart locally */
  fetchChart(chartName, chartVersion)
  AppConfigs appConfigs = new AppConfigs()
  Multimap<ClusterType, AppConfig> clusterTypeToAppConfigMap = appConfigs.getClusterTypeToAppConfigPairsFromAppsInChart(chartName)

  List<Region> regionsToRemoveFrom = targetEnv.getRegionsToDeployTo(region)

  /*  delete the apps */
  for (ClusterType clusterType : clusterTypeToAppConfigMap.keySet()) {
    List<AppConfig> appsToRemove = Lists.newArrayList(clusterTypeToAppConfigMap.get(clusterType))

    if (regionsToRemoveFrom) {
      for (Region regionToRemoveFrom : regionsToRemoveFrom) {
        executeAppsRemoval(clusterType, appsToRemove, targetEnv, regionToRemoveFrom)
      }
    } else { // the environment has no region
      executeAppsRemoval(clusterType, appsToRemove, targetEnv)
    }
  }
}

void executeAppsRemoval(ClusterType targetCluster, List<AppConfig> appsToRemove, Environment targetEnv,
                        Region region = null) {
  runWithK8SCliTools(targetEnv, targetCluster, region, {
    for (AppConfig app : appsToRemove) {
      echo "Removing app ${app.appName} from ${targetEnv.getFullClusterName(targetCluster, region)}"
      def params = [(CHART_RELEASE_NAME_COMMAND_PARAM): app]
      sh Helm.generateCommand(HelmCommand.DELETE, params, targetEnv, region)
    }
  })
}

/**
 * Retrieves the repository of the Docker image configured in the Helm chart in the current folder.
 *
 * @return the Docker image repository. Example: "coco/people-rw-neo4j"
 */
String getDockerImageRepository() {
  String chartFolderName = getHelmChartFolderName()
  String valuesContents = readFile("${HELM_CONFIG_FOLDER}/${chartFolderName}/${DEFAULT_HELM_VALUES_FILE}")
  Matcher matcher = (valuesContents =~ /repository: (.*)\s/)
  /*  get the value matching the group */
  String dockerRepo = matcher[0][1]
  dockerRepo
}

def fetchChart(String chartName, String chartVersion) {
  runHelm3RepoOperations {
    def params = [(CHART_NAME_COMMAND_PARAM): chartName, (CHART_VERSION_COMMAND_PARAM): chartVersion]
    sh Helm.generateCommand(HelmCommand.FETCH, params, HelmVersion.V3)
  }
}

String getBuildDescription(List<AppConfig> deployedApps, String version, Region deployOnlyInRegion, Environment targetEnv) {
  Set<Region> deployRegions = Sets.newHashSet()
  deployedApps.each { AppConfig app ->
    Environment currentEnv = app.environment ?: targetEnv
    deployRegions.addAll(currentEnv.getRegionsToDeployTo(deployOnlyInRegion))

    String action = app.ignoredDeployment ? "Ignored" : "Deployed"
    String ignoreCause = app.ignoredDeployment ? "cause: ${app.ignoreCauseMessage}" : ""
    echo """${action} ${app.appName}
                      version ${version}
                      on ${currentEnv.name} environment
                      in ${app.clusterType} cluster - ${app.region} region 
                      with config name ${app.origConfigFileName}
                      ${ignoreCause}
                      """
  }
  Set<ClusterType> deployedAppsClusterTypes = Sets.newHashSet(deployedApps.findAll { !it.ignoredDeployment }.collect { it.clusterType })
  List<String> fullClusterNames = targetEnv.getFullClusterNames(deployedAppsClusterTypes, deployRegions)

  List<String> deployedAppConfigNames = Lists.newArrayList(deployedApps).findAll { !it.ignoredDeployment }.collect { "${it.origConfigFileName} in region ${it.region ?: Region.UNKNOWN}".toString() }
  List<String> ignoredAppConfigNames = Lists.newArrayList(deployedApps).findAll { it.ignoredDeployment }.collect { "${it.origConfigFileName} in region ${it.region ?: Region.UNKNOWN} - ${it.ignoreCauseMessage}".toString() }

  String deployedAppsText = "${deployedAppConfigNames.join("\n")}\n-> version ${version} in ${fullClusterNames}"

  if (!deployedAppConfigNames.isEmpty()) {
    echo """
Deployed Apps Summary: 
----------------------
${deployedAppsText}"""
  } else {
    echo "No deployed apps"
  }

  if (!ignoredAppConfigNames.isEmpty()) {
    echo """
Ignored Apps Summary: 
---------------------
${ignoredAppConfigNames.join("\n")}"""
  } else {
    echo "No ignored apps"
  }

  deployedAppsText
}

void runHelm3RepoOperations(Closure codeToRun) {
  withCredentials([awsCredsEnvVars(HELM_AWS_CREDENTIALS)]) {
    def awsEnvVars = "-e 'AWS_ACCESS_KEY_ID=${env.ACCESS_KEY_ID}' -e 'AWS_SECRET_ACCESS_KEY=${env.SECRET_ACCESS_KEY}'"
    docker.image(EKS_PROVISIONER_IMAGE).inside("-u root:root " + awsEnvVars) {
      sh Helm.generateCommand(HelmCommand.ADD_REPO, [:], HelmVersion.V3)
      codeToRun.call()
    }
  }
}

private void awsCredsEnvVars(String awsCredentials) {
  usernamePassword(
          credentialsId: awsCredentials,
          passwordVariable: 'SECRET_ACCESS_KEY',
          usernameVariable: 'ACCESS_KEY_ID'
  )
}

String publishHelmChart(String version) {
  String chartFolderName = getHelmChartFolderName()
  //  lock the helm repo, so other jobs will wait on this, as we need to upload the new index before any other
  // job starts download it for merging. If we don't do this we might end up with an incomplete index.
  lock("helm-repo") {
    // check in helm3
    runHelm3RepoOperations {
      updateChartDeps("${HELM_CONFIG_FOLDER}/${chartFolderName}")

      /*  pack the chart  */
      def packageParams = [(CHART_NAME_COMMAND_PARAM): chartFolderName, (CHART_VERSION_COMMAND_PARAM): version]
      sh Helm.generateCommand(HelmCommand.PACKAGE, packageParams, HelmVersion.V3)

      // update the repository index.yaml
      sh Helm.generateCommand(HelmCommand.UPDATE_REPO_INDEX, [:], HelmVersion.V3)

      /*  upload chart and updated index to S3  */
      Aws aws = new Aws()
      aws.uploadS3Files(HELM_S3_BUCKET, "${chartFolderName}-${version}.tgz".toString(), "index.yaml")
    }

    chartFolderName
  }
}

void updateChartDeps(String chartFolder) {
  def depParams = [(CHART_NAME_COMMAND_PARAM): chartFolder]
  sh Helm.generateCommand(HelmCommand.UPDATE_DEPENDENCY, depParams, HelmVersion.V3)
}

/**
 * Retrieves the folder name where the Helm chart is defined .
 */
private String getHelmChartFolderName() {
  def chartFile = findFiles(glob: CHART_LOCATION_REGEX)[0]
  if (chartFile == null) {
    throw new IllegalStateException("There is no Helm Chart.yaml defined in the children of the Helm folder")
  }
  String[] chartFilePathComponents = ((String) chartFile.path).split('/')
  /* return the parent folder of Chart.yaml */
  def helmChartDir = chartFilePathComponents[chartFilePathComponents.size() - 2]
  helmChartDir
}

void runWithK8SCliTools(Environment targetEnv, ClusterType clusterType, Region region = null, Closure codeToRun) {
  String tokenName = buildTokenName(targetEnv, region, clusterType)
  if (tokenName) {
    echo "Using token name -> ${tokenName} to authenticate to cluster"

    withCredentials([string(credentialsId: tokenName, variable: 'TOKEN')]) {
      String apiServer = targetEnv.getClusterMapEntry(clusterType, region)?.apiServer
      if (!tokenName.contains("eks")) {
        String currentDir = pwd()
        GString dockerRunArgs =
                "-e 'K8S_API_SERVER=${apiServer}' " +
                        "-e 'KUBECONFIG=${currentDir}/kubeconfig' " +
                        "-e 'K8S_TOKEN=${env.TOKEN}'"
        docker.image(K8S_CLI_IMAGE).inside(dockerRunArgs) {
          sh "/docker-entrypoint.sh"

          codeToRun.call(targetEnv, clusterType, region)
        }
      } else {
        GString eksDockerRunArgs =
                "-u root:root -e 'K8S_API_SERVER=${apiServer}' " +
                        "-e 'K8S_TOKEN=${env.TOKEN}'"
        docker.image(EKS_PROVISIONER_IMAGE).inside(eksDockerRunArgs) {
          sh "generate-simple-kubeconfig"
          codeToRun.call(targetEnv, clusterType, region)
        }
      }
    }
  } else {
    throw new ClusterAuthenticationException(
            "Jenkins secret named in the format 'ft.k8s-auth.<cluster_name>.token'. Please provide one if it missing for this cluster.")
  }
}

static String buildTokenName(Environment targetEnv, Region region, ClusterType cluster) {
  EnvClusterMapEntry clusterMapEntry = targetEnv.getClusterMapEntry(cluster, region)
  String clusterName
  if (clusterMapEntry?.isEks) {
    // use the EKS cluster as a token name when deploying on EKS
    clusterName = clusterMapEntry.eksClusterName
  } else {
    // use the URL subdomain of the hardcoded cluster URLs when deploying with kube-aws
    clusterName = targetEnv.getClusterSubDomain(cluster, region)
  }
  String tokenName = "ft.k8s-auth.${clusterName}.token"
  tokenName
}

static String getTeamFromReleaseCandidateTag(String rcTag) {
  String[] tagComponents = rcTag.split("-")
  if (tagComponents.length > 1) {
    return tagComponents[1]
  }
  throw new IllegalArgumentException(
          "The tag '${rcTag}' is not a valid release candidate tag. A good example is: 0.2.0-xp-test-release-rc2")
}

/**
 * Gets the environment name where to deploy from the specified branch name by getting the penultimate one path item.
 * <p>
 * Example:
 * <ol>
 *   <li> for a branch named "deploy-on-push/xp/test", it will return "xp".</li>
 *   <li> for a branch named "test", it will throw an IllegalArgumentException.</li>
 * </ol>
 * @param branchName the name of the branch
 * @return the environment name where to deploy the branch
 */
static String getEnvironmentName(String branchName) {
  String[] values = branchName.split('/')
  if (values.length > 2) {
    return values[1]
  }

  throw new IllegalArgumentException(
          "The branch '${branchName}' does not contain the environment where to deploy the application. A valid name is 'deploy-on-push/xp/test'")
}

/**
 * Gets the docker image version from a branch name by getting the last item after the last "/".
 * Example: for a branch name as "deploy-on-push/xp/test", it will return "test" and for "tags/v0.1.4" it will return "v0.1.4"
 *
 * @param branchName the name of the branch
 * @return the docker image version
 */
static String getReleaseCandidateName(String branchName) {
  String[] values = branchName.split('/')
  def rcName = values[values.length - 1]
  rcName
}
