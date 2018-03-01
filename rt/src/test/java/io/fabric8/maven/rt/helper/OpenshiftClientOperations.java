package io.fabric8.maven.rt.helper;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigList;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.client.OpenShiftClient;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class OpenshiftClientOperations {

   private static final Logger LOGGER = Logger.getLogger(OpenshiftClientOperations.class.getName());

   private final OpenShiftClient openShiftClient;
   private final String testClassNamespace;

   public OpenshiftClientOperations(OpenShiftClient openShiftClient, String testClassNamespace) {
      this.openShiftClient = openShiftClient;
      this.testClassNamespace = testClassNamespace;
   }

   public void createOrReplaceConfigMap(String name, Map<String, String> data) {
      openShiftClient.configMaps()
         .inNamespace(testClassNamespace)
         .withName(name)
         .edit()
         .withData(data)
         .done();
   }

   public void createConfigMapResource(String name, Map<String, String> data) {
      if (openShiftClient.configMaps().inNamespace(testClassNamespace).withName(name).get() == null) {
         openShiftClient.configMaps()
            .inNamespace(testClassNamespace)
            .createNew()
            .withNewMetadata()
            .withName(name)
            .endMetadata()
            .withData(data)
            .done();
      } else
         createOrReplaceConfigMap(name, data);
   }

   /**
    * A method to check Re-deployment scenario. We append some annotations in all the resources and
    * check that we have those after deployment for 2nd time. This is basically to distinguish
    * deployment's versions.
    *
    * @param key
    * @return
    */
   public boolean checkDeploymentsForAnnotation(String key) {
      DeploymentConfigList deploymentConfigs = openShiftClient.deploymentConfigs().inNamespace(testClassNamespace).list();
      for (DeploymentConfig aDeploymentConfig : deploymentConfigs.getItems()) {
         if (aDeploymentConfig.getMetadata() != null && aDeploymentConfig.getMetadata().getAnnotations() != null &&
            aDeploymentConfig.getMetadata().getAnnotations().containsKey(key))
            return true;
      }
      return false;
   }

   /**
    * It watches over application pod until it becomes ready to serve.
    *
    * @throws Exception
    */
   public void waitTillApplicationPodStarts(String testClassRepositoryArtifactId) throws InterruptedException {
      LOGGER.info("Waiting to application pod .... ");

      int nPolls = 0;
      // Keep polling till 5 minutes
      while (nPolls < 60) {
         PodList podList = openShiftClient.pods().withLabel("app", testClassRepositoryArtifactId).list();
         for (Pod pod : podList.getItems()) {
            LOGGER.info("waitTillApplicationPodStarts() -> Pod : " + pod.getMetadata().getName() + ", isReady : " + KubernetesHelper
               .isPodReady(pod));
            if (KubernetesHelper.isPodReady(pod)) {
               LOGGER.info("OK ✓ ... Pod wait over.");
               TimeUnit.SECONDS.sleep(10);
               return;
            }
         }
         TimeUnit.SECONDS.sleep(5);
         nPolls++;
      }
      throw new AssertionError("Pod wait timeout! Could not find application pod for " + testClassRepositoryArtifactId);
   }

   /**
    * This variation is used in order to check for the redeployment scenario, since some
    * annotations are added while making changes in source code, and those are checked so
    * that we are able to differentiate between the redeployed pod and the previously
    * existing pod instance from previous deployment.
    *
    * @param key
    * @param value
    * @throws Exception
    */
   public void waitTillApplicationPodStarts(String key, String value, String testClassRepositoryArtifactId) throws InterruptedException {
      LOGGER.info("Waiting for application pod .... ");

      int nPolls = 0;
      // Keep polling till 5 minutes
      while (nPolls < 120) {
         PodList podList = openShiftClient.pods().withLabel("app", testClassRepositoryArtifactId).list();
         for (Pod pod : podList.getItems()) {
            //                LOGGER.info("waitTillApplicationPodStarts(" + key + ", " + value + ") -> Pod : "
            //                        + pod.getMetadata().getName() + ", STATUS : " + KubernetesHelper.getPodStatus(pod) + ", isPodReady : " + KubernetesHelper.isPodReady(pod));

            if (pod.getMetadata().getAnnotations().containsKey(key)) {
               LOGGER.info(pod.getMetadata().getName() + " is redeployed pod.");
            }
            if (pod.getMetadata().getAnnotations().containsKey(key)
               && pod.getMetadata().getAnnotations().get(key).equalsIgnoreCase(value)
               && KubernetesHelper.isPodReady(pod)) {
               LOGGER.info("OK ✓ ... Pod wait over.");
               TimeUnit.SECONDS.sleep(10);
               return;
            }
         }
         nPolls++;
         TimeUnit.SECONDS.sleep(5);
      }
      throw new AssertionError("Pod wait timeout! Could not find application pod for " + testClassRepositoryArtifactId);
   }

   public Route applicationRouteWithName(String name) {
      RouteList aRouteList = openShiftClient.routes().inNamespace(testClassNamespace).list();
      for (Route aRoute : aRouteList.getItems()) {
         if (aRoute.getMetadata().getName().equals(name)) {
            return aRoute;
         }
      }
      return null;
   }
}
