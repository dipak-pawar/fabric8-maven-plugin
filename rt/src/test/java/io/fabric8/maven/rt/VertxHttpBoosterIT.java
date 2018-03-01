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
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.Response;
import org.apache.http.HttpStatus;
import org.assertj.core.api.Assertions;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;

public class VertxHttpBoosterIT extends BaseBoosterIT {

   @ClassRule
   public static final TestBed TEST_BED =
      new TestBed("https://github.com/openshiftio-vertx-boosters/vertx-http-booster.git");

   @Rule
   public final Project project = new Project(TEST_BED);

   private final String EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL =
      "fabric8:deploy -Dfabric8.openshift.trimImageInContainerSpec=true", EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE =
      "openshift";

   private final String ANNOTATION_KEY = "vertx-testKey", ANNOTATION_VALUE = "vertx-testValue";

   private OpenShiftClient openShiftClient;
   private PomModifier pomModifier;
   private OpenshiftClientOperations clientOperations;
   private Logger logger = Logger.getLogger(VertxHealthchecksBooster.class.getName());

   @Before
   public void setUp() {
      openShiftClient = TEST_BED.getOpenShiftClient();
      pomModifier = TEST_BED.getPomModifier();
      clientOperations = TEST_BED.getClientOperations();
   }

   @Test
   public void deploy_vertx_app_once() throws Exception {

      pomModifier.addRedeploymentAnnotations("deploymentType", "deployOnce",
         FMP_PLUGIN_CONFIG_FILE);

      deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
      clientOperations.waitTillApplicationPodStarts("deploymentType", "deployOnce", pomModifier.getArtifactId());
      TimeUnit.SECONDS.sleep(20);
      assertDeployment();
   }

   @Test
   public void redeploy_vertx_app() throws Exception {
      final String targetPath = project.getTargetPath();

      // change the source code
      final PomModifier modifier = new PomModifier(targetPath);
      modifier.addCommonLangDependency();
      modifier.addRedeploymentAnnotations(ANNOTATION_KEY, ANNOTATION_VALUE,
         FMP_PLUGIN_CONFIG_FILE);

      // re-deploy
      deploy(targetPath, EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
      waitUntilDeployment(true);
      assertDeployment();
      Assertions.assertThat(clientOperations.checkDeploymentsForAnnotation(ANNOTATION_KEY)).isTrue();
   }

   private void assertDeployment() throws Exception {
      String testClassRepositoryArtifactId = pomModifier.getArtifactId();
      assertThat(openShiftClient).deployment(testClassRepositoryArtifactId);
      assertThat(openShiftClient).service(testClassRepositoryArtifactId);

      RouteAssert.assertRoute(openShiftClient, testClassRepositoryArtifactId);
      assertThatWeServeAsExpected(clientOperations.applicationRouteWithName(testClassRepositoryArtifactId));
   }

   private void waitUntilDeployment(boolean bIsReployed) throws Exception {
      if (bIsReployed) {
         clientOperations.waitTillApplicationPodStarts(ANNOTATION_KEY, ANNOTATION_VALUE, pomModifier.getArtifactId());
      } else {
         clientOperations.waitTillApplicationPodStarts(pomModifier.getArtifactId());
      }
   }

   private void assertThatWeServeAsExpected(Route applicationRoute) throws Exception {
      String hostUrl = "http://" + applicationRoute.getSpec().getHost() + "/api/greeting";

      int nTries = 0;
      Response readResponse;
      do {
         readResponse = makeHttpRequest(HttpRequestType.GET, hostUrl, null);
         nTries++;
         TimeUnit.SECONDS.sleep(10);
      } while (nTries < 3 && readResponse != null && readResponse.code() != HttpStatus.SC_OK);

      String responseContent = readResponse.body().string();
      try {
         assert new JSONObject(responseContent).getString("content").equals("Hello, World!");
      } catch (JSONException jsonException) {
         logger.log(Level.SEVERE, "Unexpected response, expecting json. Actual : " + responseContent);
         logger.log(Level.SEVERE, jsonException.getMessage(), jsonException);
      }
   }
}
