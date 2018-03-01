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
import org.apache.http.HttpStatus;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;

public class SpringbootHttpBoosterIT extends BaseBoosterIT {

   @ClassRule
   public static final TestBed TEST_BED =
      new TestBed("https://github.com/snowdrop/spring-boot-http-booster.git");

   @Rule
   public final Project project = new Project(TEST_BED);

   private final String EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL = "fabric8:deploy -Dfabric8.openshift.trimImageInContainerSpec=true", EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE = "openshift";

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
    public void deploy_springboot_app_once() throws Exception {
       final String projectArtifactId = pomModifier.getArtifactId();
       pomModifier.addRedeploymentAnnotations("deploymentType", "deployOnce",
          FMP_PLUGIN_CONFIG_FILE);

       deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
       clientOperations.waitTillApplicationPodStarts("deploymentType", "deployOnce", projectArtifactId);
        TimeUnit.SECONDS.sleep(20);

       assertThat(openShiftClient).deployment(projectArtifactId);
       assertThat(openShiftClient).service(projectArtifactId);
       RouteAssert.assertRoute(openShiftClient, projectArtifactId);
       assertApplicationPodRoute(clientOperations.applicationRouteWithName(projectArtifactId));
    }

    @Test
    public void redeploy_springboot_app() throws Exception {

        // change the source code
       final String projectArtifactId = pomModifier.getArtifactId();
       pomModifier.addCommonLangDependency();
       final String annotationKey = "testKey";
       final String annotationValue = "testValue";
       pomModifier.addRedeploymentAnnotations(annotationKey, annotationValue, FMP_PLUGIN_CONFIG_FILE);

        // redeploy and assert
       deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);

       clientOperations.waitTillApplicationPodStarts(annotationKey, annotationValue, projectArtifactId);

       assertThat(openShiftClient).deployment(projectArtifactId);
       assertThat(openShiftClient).service(projectArtifactId);
       RouteAssert.assertRoute(openShiftClient, projectArtifactId);
       assertApplicationPodRoute(clientOperations.applicationRouteWithName(projectArtifactId));
       org.assertj.core.api.Assertions.assertThat(clientOperations.checkDeploymentsForAnnotation(annotationKey)).isTrue();
    }

   //*
   // * Fetches Route information corresponding to the application pod and checks whether the
   // * endpoint is a valid url or not.
   // *
   // * @throws Exception

   private void assertApplicationPodRoute(Route applicationRoute) throws Exception {
      String hostRoute;
        if (applicationRoute != null) {
            hostRoute = applicationRoute.getSpec().getHost();
            if (hostRoute != null) {
                assert makeHttpRequest(HttpRequestType.GET,"http://" + hostRoute, null).code() == HttpStatus.SC_OK;;
            }
        } else {
           throw new AssertionError("[No route found for: " + pomModifier.getArtifactId() + "]\n");
        }
    }
}
