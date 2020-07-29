package com.ft.jenkins.cluster

import com.ft.jenkins.appconfigs.AppConfig
import com.google.common.collect.ArrayListMultimap
import com.google.common.collect.Multimap
import spock.lang.Specification

class EnvRegistrySpec extends Specification {

  def "should not get env by cluster full name for null value"() {
    expect:
    EnvsRegistry.getEnvironmentByFullName(null) == null
  }

  def "should not get env by cluster full name for non existing cluster"() {
    expect:
    EnvsRegistry.getEnvironmentByFullName("non-existing") == null
  }

  def "should get env by cluster full name for prod environment"() {
    when:
    def envByClusterTypeAndEnvName = EnvsRegistry.getEnvironment(ClusterType.DELIVERY, Environment.PROD_NAME)
    def envByEnvFullName = EnvsRegistry.getEnvironmentByFullName("prod-delivery-eu")
    then:
    envByClusterTypeAndEnvName == envByEnvFullName
  }

  def "should get env by cluster full name for staging environment"() {
    when:
    def envByClusterTypeAndEnvName = EnvsRegistry.getEnvironment(ClusterType.DELIVERY, Environment.STAGING_NAME)
    def envByEnvFullName = EnvsRegistry.getEnvironmentByFullName("staging-delivery-eu")
    then:
    envByClusterTypeAndEnvName == envByEnvFullName
  }

  def "should get env based on an app config map and env name"() {
    given:
    def expectedClusterTypes = [ClusterType.DELIVERY, ClusterType.PUBLISHING, ClusterType.PAC]
    when:
    Multimap<ClusterType, AppConfig> appsPerCluster = ArrayListMultimap.create()
    appsPerCluster.with {
      put(ClusterType.DELIVERY, new AppConfig())
      put(ClusterType.DELIVERY, new AppConfig())
      put(ClusterType.DELIVERY, new AppConfig())
      put(ClusterType.PUBLISHING, new AppConfig())
      put(ClusterType.PUBLISHING, new AppConfig())
      put(ClusterType.PAC, new AppConfig())
      put(ClusterType.UNKNOWN, new AppConfig())
    }
    Set<ClusterType> availableClusterTypes = appsPerCluster.keySet().findAll { it != ClusterType.UNKNOWN }
    List<Environment> availableEnvironments = availableClusterTypes.collect { EnvsRegistry.getEnvironment(it, Environment.DEV_NAME) }
    Environment environment = availableEnvironments.find { it }
    then:
    environment.name == Environment.DEV_NAME
  }
}
