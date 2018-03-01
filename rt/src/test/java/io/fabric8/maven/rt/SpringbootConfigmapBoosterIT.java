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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;

public class SpringbootConfigmapBoosterIT extends BaseBoosterIT {

    @ClassRule
    public static final TestBed TEST_BED =
       new TestBed("https://github.com/snowdrop/spring-boot-configmap-booster.git");

    @Rule
    public final Project project = new Project(TEST_BED);

    private final String TESTSUITE_CONFIGMAP_NAME = "app-config";

    private final String EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL = "fabric8:deploy -Dfabric8.openshift.trimImageInContainerSpec=true -DskipTests", EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE = "openshift";

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
        //given
        pomModifier.addRedeploymentAnnotations("deploymentType", "deployOnce",
           FMP_PLUGIN_CONFIG_FILE);
        final String projectArtifactId = pomModifier.getArtifactId();
        addViewRoleToDefaultServiceAccount();
        clientOperations.createConfigMapResource(TESTSUITE_CONFIGMAP_NAME, configMapData("greeting.message: Hello World from a ConfigMap!"));

        // when
        deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
        clientOperations.waitTillApplicationPodStarts("deploymentType", "deployOnce", projectArtifactId);

        TimeUnit.SECONDS.sleep(20);

        // then
        assertThat(openShiftClient).deployment(projectArtifactId);
        assertThat(openShiftClient).service(projectArtifactId);
        RouteAssert.assertRoute(openShiftClient, projectArtifactId);
        assertApplicationEndpoint("Hello World from a ConfigMap!");
    }

    @Test
    public void redeploy_springboot_app() throws Exception {
        clientOperations.createConfigMapResource(TESTSUITE_CONFIGMAP_NAME, configMapData("greeting.message: Hello World from a ConfigMap!"));

        // Make some changes in ConfigMap and rollout
        pomModifier.addCommonLangDependency();
        final String projectArtifactId = pomModifier.getArtifactId();
        final String annotationKey = "springboot-configmap-testKey";
        final String annotationValue = "springboot-configmap-testValue";
        pomModifier.addRedeploymentAnnotations(annotationKey, annotationValue, FMP_PLUGIN_CONFIG_FILE);

        // 2. Re-Deployment
        deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
        clientOperations.createOrReplaceConfigMap(TESTSUITE_CONFIGMAP_NAME, configMapData("greeting.message: Bonjour World from a ConfigMap!"));
        clientOperations.waitTillApplicationPodStarts(annotationKey, annotationValue, projectArtifactId);

        TimeUnit.SECONDS.sleep(20);

        // then
        assertThat(openShiftClient).deployment(projectArtifactId);
        assertThat(openShiftClient).service(projectArtifactId);
        RouteAssert.assertRoute(openShiftClient, projectArtifactId);
        assertApplicationEndpoint("Bonjour World from a ConfigMap!");
    }

    @After
    public void removeApp() {
        openShiftClient.configMaps()
           .inNamespace(TEST_BED.getTestClassNamespace())
           .withName(TESTSUITE_CONFIGMAP_NAME)
           .delete();
    }

    private void assertApplicationEndpoint(String value) throws Exception {
        final Route route = clientOperations.applicationRouteWithName(pomModifier.getArtifactId());
        final String hostUrl = route.getSpec().getHost() + "/api/greeting";
        final Response response = makeHttpRequest(HttpRequestType.GET, "http://" + hostUrl, null);
        final String responseContent = new JSONObject(response.body().string()).getString("content");

        if (!responseContent.equals(value))
            throw new AssertionError(String.format("Actual : %s, Expected : %s", responseContent, value));
    }

    private Map<String, String> configMapData(String value) {
        Map<String, String> configMapData = new HashMap<>();
        configMapData.put("application.properties", value);

        return configMapData;
    }
}
