package com.ft.jenkins

/**
 * There is currently no way to define these constants directly in the DeploymentUtils script, so putting them in a separate class.
 */
public class DeploymentUtilsConstants {
  public static final String CREDENTIALS_DIR = "credentials"
  public static final String K8S_CLI_IMAGE = "coco/k8s-cli-utils:latest"
  public static final String HELM_CONFIG_FOLDER = "helm"
  /*  todo [sb] After a jenkins plugins update, the following line no longer works. Please try again later, as we'd like to reuse the value of a constant in other constants */
//  public static String HELM_CHART_LOCATION_REGEX = "${HELM_CONFIG_FOLDER}/**/Chart.yaml"
  public static final String HELM_CHART_LOCATION_REGEX =  "helm/**/Chart.yaml"
  public static final String APPS_CONFIG_FOLDER = "app-configs"
  public static final String DEFAULT_HELM_VALUES_FILE = "values.yaml"
  public static final String OPTION_ALL = "All"

}
