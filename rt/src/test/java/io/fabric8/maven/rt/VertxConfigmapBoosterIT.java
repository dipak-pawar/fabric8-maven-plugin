/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.rt;

import io.fabric8.maven.rt.helper.OpenshiftClientOperations;
import io.fabric8.maven.rt.helper.PomModifier;
import io.fabric8.maven.rt.rules.Project;
import io.fabric8.maven.rt.rules.TestBed;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;
import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;

public class VertxConfigmapBoosterIT extends BaseBoosterIT {

   @ClassRule
   public static final TestBed TEST_BED =
      new TestBed("https://github.com/openshiftio-vertx-boosters/vertx-configmap-booster.git");

   @Rule
   public final Project project = new Project(TEST_BED);

   private final String TESTSUITE_CONFIGMAP_NAME = "app-config";

   private final String EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL =
      "fabric8:deploy -Dfabric8.openshift.trimImageInContainerSpec=true -DskipTests",
      EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE = "openshift";

   private final String ANNOTATION_KEY = "vertx-configmap-testKey", ANNOTATION_VALUE = "vertx-configmap-testValue";

   private final String appConfigFile = "/app-config.yml";

   private OpenShiftClient openShiftClient;
   private PomModifier pomModifier;
   private OpenshiftClientOperations clientOperations;

   @Before
   public void setUp() {
      openShiftClient = TEST_BED.getOpenShiftClient();
      pomModifier = TEST_BED.getPomModifier();
      clientOperations = TEST_BED.getClientOperations();
   }

   @Test
   public void deploy_vertx_app_once() throws Exception {
      addViewRoleToDefaultServiceAccount();
      createConfigMapResourceForApp(TESTSUITE_CONFIGMAP_NAME);

      pomModifier.addRedeploymentAnnotations("deploymentType", "deployOnce",
         FMP_PLUGIN_CONFIG_FILE);

      deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
      clientOperations.waitTillApplicationPodStarts("deploymentType", "deployOnce", pomModifier.getArtifactId());
      TimeUnit.SECONDS.sleep(20);
      assertDeployment(false);
   }

   @Test
   public void redeploy_vertx_app() throws Exception {
      // Make some changes in ConfigMap and rollout
      createConfigMapResourceForApp(TESTSUITE_CONFIGMAP_NAME);

      pomModifier.addCommonLangDependency();
      pomModifier.addRedeploymentAnnotations(ANNOTATION_KEY, ANNOTATION_VALUE,
         FMP_PLUGIN_CONFIG_FILE);

      // 2. Re-Deployment
      deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
        /*
         * Since the maintainers of this booster project have moved the configmap to
         * src/main/fabric8 directory the configmap resource gets created during the
         * time of compilation.
         */
      editConfigMapResourceForApp(TESTSUITE_CONFIGMAP_NAME);
      waitAfterDeployment(true);
      assertDeployment(true);
   }

   @After
   public void removeApp() {
      openShiftClient
         .configMaps()
         .inNamespace(TEST_BED.getTestClassNamespace())
         .withName(TESTSUITE_CONFIGMAP_NAME)
         .delete();
   }

   private void waitAfterDeployment(boolean bIsRedeployed) throws Exception {
      // Waiting for application pod to start.
      if (bIsRedeployed) {
         clientOperations.waitTillApplicationPodStarts(ANNOTATION_KEY, ANNOTATION_VALUE, pomModifier.getArtifactId());
      } else {
         clientOperations.waitTillApplicationPodStarts(pomModifier.getArtifactId());
      }
      // Wait for Services, Route, ConfigMaps to refresh according to the deployment.
      TimeUnit.SECONDS.sleep(20);
   }

   private void assertDeployment(boolean bIsRedeployed) throws Exception {
      final String projectArtifactId = pomModifier.getArtifactId();
      assertThat(openShiftClient).deployment(projectArtifactId);
      assertThat(openShiftClient).service(projectArtifactId);

      RouteAssert.assertRoute(openShiftClient, projectArtifactId);
      if (bIsRedeployed) {
         assert assertApplicationEndpoint("content", "Bonjour, World from a ConfigMap !");
      } else {
         assert assertApplicationEndpoint("content", "Hello, World from a ConfigMap !");
      }
   }

   private boolean assertApplicationEndpoint(String key, String value) throws Exception {
      Route applicationRoute = clientOperations.applicationRouteWithName(pomModifier.getArtifactId());
      String hostUrl = applicationRoute.getSpec().getHost() + "/api/greeting";
      Response response = makeHttpRequest(HttpRequestType.GET, "http://" + hostUrl, null);
      return new JSONObject(response.body().string()).getString(key).equals(value);
   }

   private void createConfigMapResourceForApp(String configMapName) throws Exception {
      Map<String, String> configMapData = new HashMap<>();
      File aConfigMapFile = new File(getClass().getResource(appConfigFile).getFile());

      configMapData.put("app-config.yml", FileUtils.readFileToString(aConfigMapFile));

      clientOperations.createConfigMapResource(configMapName, configMapData);
   }

   private void editConfigMapResourceForApp(String configMapName) throws Exception {
      Map<String, String> configMapData = new HashMap<>();
      File aConfigMapFile = new File(getClass().getResource(appConfigFile).getFile());

      String content = FileUtils.readFileToString(aConfigMapFile);
      content = content.replace("Hello", "Bonjour");
      configMapData.put("app-config.yml", content);

      clientOperations.createOrReplaceConfigMap(configMapName, configMapData);
   }
}
