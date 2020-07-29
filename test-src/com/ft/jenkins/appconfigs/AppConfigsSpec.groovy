package com.ft.jenkins.appconfigs

import com.ft.jenkins.cluster.Cluster
import com.ft.jenkins.cluster.ClusterType
import com.ft.jenkins.cluster.Environment
import com.ft.jenkins.cluster.Region
import spock.lang.Specification

import static com.ft.jenkins.cluster.Environment.*

class AppConfigsSpec extends Specification {
  private static final List<String> TEST_APP_CONFIG_FILE_NAMES_1 = [
          "internal-k8s-nginx-ingress_delivery.yaml",
          "internal-k8s-nginx-ingress_delivery_prod.yaml",
          "internal-k8s-nginx-ingress_delivery_prod_eu.yaml",
          "internal-k8s-nginx-ingress_delivery_prod_us.yaml",
          "internal-k8s-nginx-ingress_delivery_staging_eu.yaml",
          "internal-k8s-nginx-ingress_delivery_staging_us.yaml",
          "internal-k8s-nginx-ingress_eks_pac_prod_eu.yaml",
          "internal-k8s-nginx-ingress_eks_pac_prod_us.yaml",
          "internal-k8s-nginx-ingress_eks_pac_staging_eu.yaml",
          "internal-k8s-nginx-ingress_eks_pac_staging_us.yaml",
          "internal-k8s-nginx-ingress_eks_pac_test_eu.yaml",
          "internal-k8s-nginx-ingress_eks_pac_test_us.yaml",
          "internal-k8s-nginx-ingress_pac_prod_eu.yaml",
          "internal-k8s-nginx-ingress_pac_prod_us.yaml",
          "internal-k8s-nginx-ingress_pac_prodpac_eu.yaml",
          "internal-k8s-nginx-ingress_pac_prodpac_us.yaml",
          "internal-k8s-nginx-ingress_pac_staging_eu.yaml",
          "internal-k8s-nginx-ingress_pac_staging_us.yaml",
          "internal-k8s-nginx-ingress_pac_stagingpac_eu.yaml",
          "internal-k8s-nginx-ingress_pac_stagingpac_us.yaml",
          "internal-k8s-nginx-ingress_publishing.yaml",
          "internal-k8s-nginx-ingress_publishing_prod_eu.yaml",
          "internal-k8s-nginx-ingress_publishing_prod_us.yaml",
          "internal-k8s-nginx-ingress_publishing_staging_eu.yaml",
          "internal-k8s-nginx-ingress_publishing_staging_us.yaml",
          "k8s-nginx-ingress_eks_pac_prod_eu.yaml",
          "k8s-nginx-ingress_eks_pac_prod_us.yaml",
          "k8s-nginx-ingress_eks_pac_staging_eu.yaml",
          "k8s-nginx-ingress_eks_pac_staging_us.yaml",
          "k8s-nginx-ingress_eks_pac_test_eu.yaml",
          "k8s-nginx-ingress_eks_pac_test_us.yaml",
          "k8s-nginx-ingress_pac_prodpac_eu.yaml",
          "k8s-nginx-ingress_pac_prodpac_us.yaml",
          "k8s-nginx-ingress_pac_stagingpac_eu.yaml",
          "k8s-nginx-ingress_pac_stagingpac_us.yaml",
  ]

  public static final List<String> TEST_APP_CONFIG_FILE_NAMES_2 = [
          "resilient-splunk-forwarder_delivery",
          "resilient-splunk-forwarder_eks_pac_prod",
          "resilient-splunk-forwarder_eks_pac_staging",
          "resilient-splunk-forwarder_eks_pac_test",
          "resilient-splunk-forwarder_pac",
          "resilient-splunk-forwarder_pac_prod",
          "resilient-splunk-forwarder_pac_prodpac",
          "resilient-splunk-forwarder_publishing"
  ]

  def "should parse app config file names correctly"() {
    given:
    List<String> expectedAppConfigFileNames = [
            "internal-k8s-nginx-ingress_delivery.yaml",
            "internal-k8s-nginx-ingress_delivery_prod.yaml",
            "internal-k8s-nginx-ingress_delivery_prod_eu.yaml",
            "internal-k8s-nginx-ingress_delivery_prod_us.yaml",
            "internal-k8s-nginx-ingress_delivery_staging_eu.yaml",
            "internal-k8s-nginx-ingress_delivery_staging_us.yaml",
            "internal-k8s-nginx-ingress_eks_pac_prod_eu.yaml",
            "internal-k8s-nginx-ingress_eks_pac_prod_us.yaml",
            "internal-k8s-nginx-ingress_eks_pac_staging_eu.yaml",
            "internal-k8s-nginx-ingress_eks_pac_staging_us.yaml",
            "internal-k8s-nginx-ingress_eks_pac_${UNKNOWN_ENV}_eu.yaml",
            "internal-k8s-nginx-ingress_eks_pac_${UNKNOWN_ENV}_us.yaml",
            "internal-k8s-nginx-ingress_pac_prod_eu.yaml",
            "internal-k8s-nginx-ingress_pac_prod_us.yaml",
            "internal-k8s-nginx-ingress_pac_${UNKNOWN_ENV}_eu.yaml",
            "internal-k8s-nginx-ingress_pac_${UNKNOWN_ENV}_us.yaml",
            "internal-k8s-nginx-ingress_pac_staging_eu.yaml",
            "internal-k8s-nginx-ingress_pac_staging_us.yaml",
            "internal-k8s-nginx-ingress_pac_${UNKNOWN_ENV}_eu.yaml",
            "internal-k8s-nginx-ingress_pac_${UNKNOWN_ENV}_us.yaml",
            "internal-k8s-nginx-ingress_publishing.yaml",
            "internal-k8s-nginx-ingress_publishing_prod_eu.yaml",
            "internal-k8s-nginx-ingress_publishing_prod_us.yaml",
            "internal-k8s-nginx-ingress_publishing_staging_eu.yaml",
            "internal-k8s-nginx-ingress_publishing_staging_us.yaml",
            "k8s-nginx-ingress_eks_pac_prod_eu.yaml",
            "k8s-nginx-ingress_eks_pac_prod_us.yaml",
            "k8s-nginx-ingress_eks_pac_staging_eu.yaml",
            "k8s-nginx-ingress_eks_pac_staging_us.yaml",
            "k8s-nginx-ingress_eks_pac_${UNKNOWN_ENV}_eu.yaml",
            "k8s-nginx-ingress_eks_pac_${UNKNOWN_ENV}_us.yaml",
            "k8s-nginx-ingress_pac_${UNKNOWN_ENV}_eu.yaml",
            "k8s-nginx-ingress_pac_${UNKNOWN_ENV}_us.yaml",
            "k8s-nginx-ingress_pac_${UNKNOWN_ENV}_eu.yaml",
            "k8s-nginx-ingress_pac_${UNKNOWN_ENV}_us.yaml",
    ]
    when:
    List<AppConfig> apps = parseAppConfigFileNames(TEST_APP_CONFIG_FILE_NAMES_1)
    def actualFileNames = apps.collect { it.toConfigFileName() + ".yaml" }
    then:
    expectedAppConfigFileNames.sort() == actualFileNames.sort()
  }

  def "should filter app configs based on environment and cluster type"() {
    given:
    Cluster pacCluster = new Cluster(ClusterType.PAC)
    Environment stagingEnv = new Environment(STAGING_NAME, pacCluster)
    stagingEnv.with {
      regions = [Region.EU, Region.US]
    }
    def expectedAppConfigFileNames = [
            "internal-k8s-nginx-ingress_eks_pac_staging_eu",
            "internal-k8s-nginx-ingress_eks_pac_staging_us",
            "internal-k8s-nginx-ingress_pac_staging_eu",
            "internal-k8s-nginx-ingress_pac_staging_us",
            "k8s-nginx-ingress_eks_pac_staging_eu",
            "k8s-nginx-ingress_eks_pac_staging_us"
    ]
    when:
    List<AppConfig> apps = parseAppConfigFileNames(TEST_APP_CONFIG_FILE_NAMES_1)
    List<String> filteredAppConfigFileNames = AppConfigs
            .filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(stagingEnv, apps, pacCluster.clusterType)
            .findAll { !it.isInvalidEnvironment }
            .collect({ it.toConfigFileName() })
    then:
    expectedAppConfigFileNames.sort() == filteredAppConfigFileNames.sort()
  }

  def "should filter app configs based on most specific ones when most specific configs are available"() {
    given:
    Cluster deliveryCluster = new Cluster(ClusterType.DELIVERY)
    Environment stagingEnv = new Environment(PROD_NAME, deliveryCluster)
    stagingEnv.with {
      regions = [Region.EU, Region.US]
    }

    def expectedAppConfigFileNames = [
            "internal-k8s-nginx-ingress_delivery_prod_eu",
            "internal-k8s-nginx-ingress_delivery_prod_us"
    ]
    when:
    List<AppConfig> apps = parseAppConfigFileNames(TEST_APP_CONFIG_FILE_NAMES_1)
    List<AppConfig> filteredAppConfigs = AppConfigs
            .filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(stagingEnv, apps, deliveryCluster.clusterType)
    List<String> filteredAppConfigFileNames = AppConfigs
            .filterAppConfigsBasedOnMostSpecificDeployments(filteredAppConfigs)
            .collect({ it.toConfigFileName() })

    then:
    expectedAppConfigFileNames.sort() == filteredAppConfigFileNames.sort()
  }

  def "should filter app configs based on most specific ones when most specific configs are not available"() {
    given:
    Cluster deliveryCluster = new Cluster(ClusterType.DELIVERY)
    Environment stagingEnv = new Environment(DEV_NAME, deliveryCluster)
    stagingEnv.with {
      regions = [Region.EU, Region.US]
    }

    def expectedAppConfigFileNames = [
            "internal-k8s-nginx-ingress_delivery"
    ]
    when:
    List<AppConfig> apps = parseAppConfigFileNames(TEST_APP_CONFIG_FILE_NAMES_1)
    List<AppConfig> filteredAppConfigs = AppConfigs
            .filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(stagingEnv, apps, deliveryCluster.clusterType)
    List<String> filteredAppConfigFileNames = AppConfigs
            .filterAppConfigsBasedOnMostSpecificDeployments(filteredAppConfigs)
            .collect({ it.toConfigFileName() })

    then:
    expectedAppConfigFileNames.sort() == filteredAppConfigFileNames.sort()
  }

  def "should filter app configs based on most specific ones when there are both specific and non specific configs available"() {
    given:
    Cluster pacCluster = new Cluster(ClusterType.PAC)
    Environment stagingEnv = new Environment(STAGING_NAME, pacCluster)
    stagingEnv.with {
      regions = [Region.EU, Region.US]
    }

    def expectedAppConfigFileNames = [
            "resilient-splunk-forwarder_eks_pac_staging",
            "resilient-splunk-forwarder_pac"
    ]
    when:
    List<AppConfig> apps = parseAppConfigFileNames(TEST_APP_CONFIG_FILE_NAMES_2)
    List<AppConfig> filteredAppConfigs = AppConfigs
            .filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(stagingEnv, apps, pacCluster.clusterType)
    List<String> filteredAppConfigFileNames = AppConfigs
            .filterAppConfigsBasedOnMostSpecificDeployments(filteredAppConfigs)
            .findAll { !it.isInvalidEnvironment }
            .collect({ it.toConfigFileName() })

    then:
    expectedAppConfigFileNames.sort() == filteredAppConfigFileNames.sort()
  }

  def "should filter app configs based on most specific ones when mentioned cluster type and region"() {
    given:
    Cluster pacCluster = new Cluster(ClusterType.PAC)
    Environment prodEnv = new Environment(PROD_NAME, pacCluster)
    prodEnv.with {
      regions = [Region.EU, Region.US]
    }

    def expectedAppConfigFileNames = [
            "resilient-splunk-forwarder_eks_pac_prod",
            "resilient-splunk-forwarder_pac_prod"
    ]
    when:
    List<AppConfig> apps = parseAppConfigFileNames(TEST_APP_CONFIG_FILE_NAMES_2)
    List<AppConfig> invalidAppConfigs = apps.findAll { it.isInvalidClusterType || it.isInvalidEnvironment || it.isInvalidRegion }
    apps.removeAll(invalidAppConfigs)
    List<AppConfig> filteredAppConfigs = AppConfigs
            .filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(prodEnv, apps, pacCluster.clusterType)
    List<String> filteredAppConfigFileNames = AppConfigs
            .filterAppConfigsBasedOnMostSpecificDeployments(filteredAppConfigs)
            .collect({ it.toConfigFileName() })

    then:
    expectedAppConfigFileNames.sort() == filteredAppConfigFileNames.sort()
  }

  def "should should filter app configs based on most specific ones when cluster type is all-in-chart"() {
    given:
    Cluster allInChartCluster = new Cluster(ClusterType.ALL_IN_CHART)
    Environment stagingEnv = new Environment(STAGING_NAME, allInChartCluster)
    stagingEnv.with {
      regions = [Region.EU, Region.US]
    }

    def expectedAppConfigFileNames = [
            "resilient-splunk-forwarder_delivery",
            "resilient-splunk-forwarder_eks_pac_staging",
            "resilient-splunk-forwarder_pac",
            "resilient-splunk-forwarder_publishing"
    ]
    when:
    List<AppConfig> apps = parseAppConfigFileNames(TEST_APP_CONFIG_FILE_NAMES_2)
    List<AppConfig> filteredAppConfigs = AppConfigs
            .filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(stagingEnv, apps, allInChartCluster.clusterType)
    List<String> filteredAppConfigFileNames = AppConfigs
            .filterAppConfigsBasedOnMostSpecificDeployments(filteredAppConfigs)
            .findAll { !it.isInvalidEnvironment }
            .collect({ it.toConfigFileName() })

    then:
    expectedAppConfigFileNames.sort() == filteredAppConfigFileNames.sort()
  }

  def "should detect invalid app configs"(String appConfigFileName, String expectedInvalidAppConfigResponse) {
    when:
    AppConfig appConfig = toAppConfig(appConfigFileName)
    then:
    appConfig.toConfigFileName() == expectedInvalidAppConfigResponse
    where:
    appConfigFileName                               | expectedInvalidAppConfigResponse
    "resilient-splunk-forwarder_deliverooo"         | "resilient-splunk-forwarder_${ClusterType.UNKNOWN.label}"
    "resilient-splunk-forwarder_delivery_pprod_eu"  | "resilient-splunk-forwarder_delivery_${UNKNOWN_ENV}_eu"
    "resilient-splunk-forwarder_delivery_prod_sa"   | "resilient-splunk-forwarder_delivery_prod_${Region.UNKNOWN.name}"
    "resilient-splunk-forwarder_deliveroo_pprod_eu" | "resilient-splunk-forwarder_${ClusterType.UNKNOWN.label}_${UNKNOWN_ENV}_eu"
    "resilient-splunk-forwarder_deliveroo_pprod_sa" | "resilient-splunk-forwarder_${ClusterType.UNKNOWN.label}_${UNKNOWN_ENV}_${Region.UNKNOWN.name}"
  }

  def "should parse publish and publishing cluster type correctly"(String appConfigFileName, String expectedInvalidAppConfigResponse) {
    when:
    AppConfig appConfig = toAppConfig(appConfigFileName)
    then:
    appConfig.toConfigFileName() == expectedInvalidAppConfigResponse
    where:
    appConfigFileName                            | expectedInvalidAppConfigResponse
    "k8s-pub-auth-varnish_eks_publish_dev"       | "k8s-pub-auth-varnish_eks_publish_dev"
    "k8s-pub-auth-varnish_eks_publishing_dev"    | "k8s-pub-auth-varnish_eks_publishing_dev"
    "k8s-pub-auth-varnish_eks_publishing"        | "k8s-pub-auth-varnish_eks_publishing"
    "k8s-pub-auth-varnish_eks_publish"           | "k8s-pub-auth-varnish_eks_publish"
    "k8s-pub-auth-varnish_eks_publish_dev_eu"    | "k8s-pub-auth-varnish_eks_publish_dev_eu"
    "k8s-pub-auth-varnish_eks_publishing_dev_eu" | "k8s-pub-auth-varnish_eks_publishing_dev_eu"
  }

  def "should detect correct deployment candidates when a deploy only region in specified"() {
    given:
    Cluster deliveryCluster = new Cluster(ClusterType.DELIVERY)
    Environment stagingEnv = new Environment(STAGING_NAME, deliveryCluster)
    stagingEnv.with {
      regions = [Region.EU, Region.US]
    }
    def expectedAppConfigFileNames = [
            "concept-events-notifications-reader_delivery",
            "concept-events-notifications_delivery_staging_us"
    ]
    when:
    List<AppConfig> apps = parseAppConfigFileNames([
            "concept-events-notifications-reader_delivery.yaml",
            "concept-events-notifications_delivery.yaml",
            "concept-events-notifications_delivery_prod_us.yaml",
            "concept-events-notifications_delivery_staging_us.yaml"
    ])
    List<AppConfig> filteredAppConfigs = AppConfigs
            .filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(stagingEnv, apps, deliveryCluster.clusterType)
    List<String> filteredAppConfigFileNames = AppConfigs
            .filterAppConfigsBasedOnMostSpecificDeployments(filteredAppConfigs, Region.ALL)
            .collect({ it.toConfigFileName() })
    then:
    expectedAppConfigFileNames.sort() == filteredAppConfigFileNames.sort()
  }

  def "should detect correct deployment candidates when a deploy only region is specified"(List<String> expectedAppConfigFileNames, Region region) {
    given:
    Cluster deliveryCluster = new Cluster(ClusterType.DELIVERY)
    Environment stagingEnv = new Environment(STAGING_NAME, deliveryCluster)
    stagingEnv.with {
      regions = [Region.EU, Region.US]
    }

    when:
    List<AppConfig> apps = parseAppConfigFileNames([
            "delivery-varnish_delivery",
            "delivery-varnish_delivery_prod_eu",
            "delivery-varnish_delivery_prod_us",
            "delivery-varnish_delivery_staging_eu",
            "delivery-varnish_delivery_staging_us",
            "delivery-varnish_eks_delivery_dev_eu",
            "delivery-varnish_eks_delivery_prod_eu",
            "delivery-varnish_eks_delivery_prod_us",
            "delivery-varnish_eks_delivery_staging_eu",
            "delivery-varnish_eks_delivery_staging_us",
            "delivery-varnish_eks_delivery_test_eu"
    ])
    List<AppConfig> filteredAppConfigs = AppConfigs
            .filterAppConfigsBasedOnEnvAndClusterTypeAndRegion(stagingEnv, apps, deliveryCluster.clusterType, region)
    List<String> filteredAppConfigFileNames = AppConfigs
            .filterAppConfigsBasedOnMostSpecificDeployments(filteredAppConfigs, region)
            .collect({ it.toConfigFileName() })
    then:
    expectedAppConfigFileNames.sort() == filteredAppConfigFileNames.sort()
    where:
    expectedAppConfigFileNames                                                                                                                                               | region
    ["delivery-varnish_delivery_staging_us", "delivery-varnish_eks_delivery_staging_us"]                                                                                     | Region.US
    ["delivery-varnish_delivery_staging_eu", "delivery-varnish_eks_delivery_staging_eu"]                                                                                     | Region.EU
    ["delivery-varnish_delivery_staging_eu", "delivery-varnish_delivery_staging_us", "delivery-varnish_eks_delivery_staging_eu", "delivery-varnish_eks_delivery_staging_us"] | Region.ALL
  }

  private static List<AppConfig> parseAppConfigFileNames(List<String> fileNames) {
    List<AppConfig> actualAppConfigs = []
    fileNames.each { String name ->
      AppConfig currentAppConfig = toAppConfig(name)
      actualAppConfigs.add(currentAppConfig)
    }
    actualAppConfigs
  }

  static AppConfig toAppConfig(String configFileName) {
    def nameComponents = configFileName.replace(".yaml", "").split("_")
    List<AppConfigNameComponentHandler> nameComponentHandlers = AppConfigs.buildAppConfigNameHandlers()
    AppConfig currentAppConfig = AppConfigs.populateAppConfig(nameComponentHandlers, nameComponents)
    currentAppConfig
  }
}
