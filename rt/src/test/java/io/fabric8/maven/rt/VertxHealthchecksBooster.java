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
import java.util.logging.Logger;
import okhttp3.Response;
import org.apache.http.HttpStatus;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;

public class VertxHealthchecksBooster extends BaseBoosterIT {

   @ClassRule
   public static final TestBed TEST_BED =
      new TestBed("https://github.com/openshiftio-vertx-boosters/vertx-health-checks-booster.git");

   @Rule
   public final Project project = new Project(TEST_BED);

   private final String EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL =
      "fabric8:deploy -Dfabric8.openshift.trimImageInContainerSpec=true", EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE =
      "openshift";

   private final String ANNOTATION_KEY = "vertx-healthcheck-testKey", ANNOTATION_VALUE = "vertx-healthcheck-testValue";

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
      pomModifier.addCommonLangDependency();
      pomModifier.addRedeploymentAnnotations(ANNOTATION_KEY, ANNOTATION_VALUE,
         FMP_PLUGIN_CONFIG_FILE);

      // redeploy and assert
      deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
      waitAfterDeployment(true);
      assertDeployment();
   }

   private void waitAfterDeployment(boolean bIsRedeployed) throws Exception {
      // Waiting for application pod to start.
      if (bIsRedeployed) {
         clientOperations.waitTillApplicationPodStarts(ANNOTATION_KEY, ANNOTATION_VALUE, pomModifier.getArtifactId());
      } else {
         clientOperations.waitTillApplicationPodStarts(pomModifier.getArtifactId());
      }
   }

   private void assertDeployment() throws Exception {
      final String projectArtifactId = pomModifier.getArtifactId();
      assertThat(openShiftClient).deployment(projectArtifactId);
      assertThat(openShiftClient).service(projectArtifactId);

      RouteAssert.assertRoute(openShiftClient, projectArtifactId);
      testHealthChecks(clientOperations.applicationRouteWithName(projectArtifactId));
   }

   private void testHealthChecks(Route applicationRoute) throws Exception {
      String hostUrl = "http://" + applicationRoute.getSpec().getHost();

      // Check service state
      assert isApplicationUpAndRunning(hostUrl);

      // Stop the service
      assert makeHttpRequest(HttpRequestType.GET, hostUrl + "/api/stop", null).code() == HttpStatus.SC_OK;

      // Check service state after shutdown
      Response serviceStatus = makeHttpRequest(HttpRequestType.GET, hostUrl + "/api/health/liveness", null);
      assert serviceStatus.code() != HttpStatus.SC_OK;
      assert new JSONObject(serviceStatus.body().string()).getString("outcome").equals("DOWN");

      // Wait for recovery
      assertApplicationRecovery(hostUrl + "/api/greeting", 120);
   }

   /**
    * Await for at most `awaitTimeInSeconds` to see if the application is able to recover
    * on its own or not.
    *
    * @throws Exception
    */
   private void assertApplicationRecovery(String hostUrl, long awaitTimeInSeconds) throws Exception {
      for (int nSeconds = 0; nSeconds < awaitTimeInSeconds; nSeconds++) {
         Response serviceResponse = makeHttpRequest(HttpRequestType.GET, hostUrl, null);
         if (serviceResponse.code() == HttpStatus.SC_OK) {
            logger.info("Application recovery successful");
            return;
         }
         TimeUnit.SECONDS.sleep(1);
      }
      throw new AssertionError("Application recovery failed");
   }

   private boolean isApplicationUpAndRunning(String hostUrl) throws Exception {
      return new JSONObject(makeHttpRequest(HttpRequestType.GET, hostUrl + "/api/health/liveness", null).body().string())
         .getString("outcome")
         .equals("UP")
         && new JSONObject(makeHttpRequest(HttpRequestType.GET, hostUrl + "/api/greeting", null).body().string())
         .getString("content")
         .equals("Hello, World!");
   }
}
