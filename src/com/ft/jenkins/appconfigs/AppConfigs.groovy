package com.ft.jenkins.appconfigs

import com.cloudbees.groovy.cps.NonCPS
import com.ft.jenkins.appconfigs.AppConfig
import com.ft.jenkins.cluster.ClusterType
import com.ft.jenkins.cluster.Environment
import com.ft.jenkins.cluster.EnvsRegistry
import com.ft.jenkins.cluster.Region
import com.ft.jenkins.exceptions.InvalidAppConfigFileNameException
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.Multimaps

import static com.ft.jenkins.deployment.HelmConstants.APP_CONFIGS_FOLDER

Multimap<ClusterType, AppConfig> getClusterTypeToAppConfigPairsFromAppsInChart(String chartFolderLocation) {
  Multimap<ClusterType, AppConfig> clusterTypeToAppNameMap = ArrayListMultimap.create()
  def foundConfigFiles = findFiles(glob: "${chartFolderLocation}/${APP_CONFIGS_FOLDER}/*.yaml")

  for (def configFile : foundConfigFiles) {
    /*  compute the app name and the cluster where it will be deployed. The format is ${app-name}_${cluster}[_${env}].yaml */
    String configFileName = configFile.name
    /*  strip the yaml extension */
    configFileName = configFileName.replace(".yaml", "")
    String[] fileNameParts = configFileName.split("_")

    if (fileNameParts.length > 1) {
      List<AppConfigNameComponentHandler> nameComponentHandlers = buildAppConfigNameHandlers()
      AppConfig currentAppConfig = populateAppConfig(nameComponentHandlers, fileNameParts)
      clusterTypeToAppNameMap.put(currentAppConfig.clusterType, currentAppConfig)
    } else {
      throw new InvalidAppConfigFileNameException(
              "found invalid app configuration file name: ${configFileName} with path: ${configFile.path}")
    }
  }
  clusterTypeToAppNameMap
}

static Multimap<ClusterType, AppConfig> toClusterTypeAppConfigMultimap(List<AppConfig> appConfigs) {
  Multimaps.index(appConfigs, { app -> app.clusterType })
}

static List buildAppConfigNameHandlers() {
  def appNameHandler = new AppConfigNameComponentHandler() {
    @Override
    int handle(String[] nameComponents, AppConfig appConfig, int currentIndex) {
      appConfig.appName = nameComponents[currentIndex]
      currentIndex
    }
  }

  def clusterTypeHandler = new AppConfigNameComponentHandler() {
    @Override
    int handle(String[] nameComponents, AppConfig appConfig, int currentIndex) {
      String clusterTypeName = nameComponents[currentIndex]
      if (clusterTypeName == "eks") {
        appConfig.isEks = true
        clusterTypeName = nameComponents[++currentIndex]
      }
      ClusterType aliasedClusterType = ClusterType.values().find { it.alias == clusterTypeName }
      if (aliasedClusterType) {
        appConfig.withAliasedClusterType = true
      }

      ClusterType clusterType = ClusterType.toClusterType(clusterTypeName)
      appConfig.clusterType = clusterType
      if (clusterTypeName && appConfig.clusterType == ClusterType.UNKNOWN) {
        appConfig.isInvalidClusterType = true
      }
      currentIndex
    }
  }

  def envHandler = new AppConfigNameComponentHandler() {
    @Override
    int handle(String[] nameComponents, AppConfig appConfig, int currentIndex) {
      String envName = nameComponents[currentIndex]
      Environment env = EnvsRegistry.getEnvironment(appConfig.clusterType, envName)
      appConfig.environment = env
      if (envName && !appConfig.environment) {
        appConfig.isInvalidEnvironment = true
      }
      currentIndex
    }
  }

  def regionHandler = new AppConfigNameComponentHandler() {
    @Override
    int handle(String[] nameComponents, AppConfig appConfig, int currentIndex) {
      String regionName = nameComponents[currentIndex]
      Region region = Region.toRegion(regionName)
      appConfig.region = region
      if (regionName && !appConfig.region) {
        appConfig.isInvalidRegion = true
      }
      currentIndex
    }
  }

  [appNameHandler, clusterTypeHandler, envHandler, regionHandler]
}

String getAppConfigFileName(AppConfig app, String chartFolderLocation) {
  computeAppConfigFullPath(app.toConfigFileName(), chartFolderLocation)
}

static List<AppConfig> getAppsInFirstCluster(Multimap<ClusterType, AppConfig> appsPerCluster) {
  for (firstCluster in appsPerCluster.keys()) {
    Collection<AppConfig> apps = appsPerCluster.get(firstCluster)
    return apps
  }
  return []
}

static boolean areSameAppsInAllClusters(Multimap<ClusterType, AppConfig> appsPerCluster) {
  List<AppConfig> appsInFirstCluster = getAppsInFirstCluster(appsPerCluster)
  Boolean sameAppsInAllClusters = true
  for (cluster in appsPerCluster.keySet()) {
    List<AppConfig> appsInCluster = appsPerCluster.get(cluster)
    if (appsInFirstCluster != appsInCluster) {
      sameAppsInAllClusters = false
    }
  }
  sameAppsInAllClusters
}

static AppConfig populateAppConfig(List<AppConfigNameComponentHandler> handlers, String[] nameComponents) {
  AppConfig appConfig = new AppConfig()
  appConfig.origConfigFileName = nameComponents.join("_")

  int currentIndex = 0
  for (AppConfigNameComponentHandler h : handlers) {
    def shouldContinueProcessing = currentIndex >= 0 && currentIndex < nameComponents.size()
    if (shouldContinueProcessing) {
      currentIndex = h.handle(nameComponents, appConfig, currentIndex)
      currentIndex++
    } else {
      break
    }
  }
  appConfig
}

static List<AppConfig> filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(Environment targetEnv, List<AppConfig> appConfigsPerClusterType, ClusterType deployOnlyInCluster = null, Region deployOnlyRegion = null) {
  List<AppConfig> currentAppConfigs = []
  if (targetEnv.cluster.clusterType == ClusterType.ALL_IN_CHART) {
    currentAppConfigs = appConfigsPerClusterType.findAll { matchingTargetEnvNameOrMissingIt(it, targetEnv) }
  } else if (deployOnlyInCluster) {
    if (deployOnlyRegion && deployOnlyRegion != Region.ALL) {
      currentAppConfigs = appConfigsPerClusterType.findAll { matchingTargetEnvNameOrMissingIt(it, targetEnv) && it.clusterType == deployOnlyInCluster && it.region == deployOnlyRegion }
    } else {
      currentAppConfigs = appConfigsPerClusterType.findAll { matchingTargetEnvNameOrMissingIt(it, targetEnv) && it.clusterType == deployOnlyInCluster }
    }
  }
  currentAppConfigs
}


static List<AppConfig> filterAppConfigsBasedOnMostSpecificDeployments(List<AppConfig> apps, Region deployOnlyInRegion = null) {
  apps = sortAppConfigs(apps)
  apps = linkAppConfigHierarchy(apps, deployOnlyInRegion)
  // Extract all app configs that are leaf nodes (do not have a direct child)
  apps = apps.findAll { !it.child }
  apps
}

@NonCPS
private static List<AppConfig> sortAppConfigs(List<AppConfig> apps) {
  apps.toSorted({ a, b -> (a.toConfigFileName() <=> b.toConfigFileName()) })
}

private static List<AppConfig> linkAppConfigHierarchy(List<AppConfig> apps, Region deployOnlyInRegion = null) {
  AppConfig prevApp = apps.first()
  for (AppConfig currentApp : apps) {
    boolean potentialChildHasEnv = currentApp?.environment && !prevApp?.environment
    boolean potentialChildHasRegion = currentApp?.region && !prevApp?.region
    boolean potentialChildRegionIsNotSpecifiedRegion = deployOnlyInRegion && currentApp?.region != deployOnlyInRegion
    boolean sameClusterType = currentApp?.clusterType == prevApp?.clusterType
    boolean sameEksStatus = currentApp?.isEks == prevApp?.isEks

    if (sameClusterType && sameEksStatus) {
      if (potentialChildHasRegion && potentialChildRegionIsNotSpecifiedRegion) {
        prevApp.child = null
      }

      if (potentialChildHasEnv || potentialChildHasRegion) {
        prevApp.child = currentApp
      }
    }
    prevApp = currentApp
  }
  apps
}

private static boolean matchingTargetEnvNameOrMissingIt(AppConfig app, Environment targetEnv) {
  app.environment?.name == targetEnv.name || !app.environment
}

private void addAppToCluster(Multimap<ClusterType, AppConfig> result, ClusterType targetCluster, List<AppConfig> appConfigs) {
  for (AppConfig appConfig : appConfigs) {
    boolean appNameNotInserted = !result.get(targetCluster).contains(appConfig)
    if (appNameNotInserted) {
      result.put(targetCluster, appConfig)
      echo "App ${appConfig} will be deployed in cluster ${targetCluster.label}"
    }
  }
}

private String computeAppConfigFullPath(String appConfigFileName, String chartFolderLocation) {
  def appConfigPath = "${chartFolderLocation}/${APP_CONFIGS_FOLDER}/${appConfigFileName}.yaml".toString()
  echo "Checking if file exists: " + appConfigPath
  if (fileExists(appConfigPath)) {
    echo "Found " + appConfigPath
    return appConfigPath
  }
  echo "Not found"
  return null
}
