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
import okhttp3.Response;
import org.apache.http.HttpStatus;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;
import static io.fabric8.maven.rt.helper.CommandExecutor.execCommand;


public class SpringbootCrudBoosterIT extends BaseBoosterIT {

    @ClassRule
    public static final TestBed TEST_BED = new TestBed("https://github.com/snowdrop/spring-boot-crud-booster.git");

    @Rule
    public final Project project = new Project(TEST_BED);

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
        deployDatabaseUsingCLI();

        pomModifier.addRedeploymentAnnotations("deploymentType", "deployOnce",
           FMP_PLUGIN_CONFIG_FILE);
        final String projectArtifactId = pomModifier.getArtifactId();

        deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
        clientOperations.waitTillApplicationPodStarts("deploymentType", "deployOnce", projectArtifactId);
        TimeUnit.SECONDS.sleep(20);
        assertThat(openShiftClient).deployment(projectArtifactId);
        assertThat(openShiftClient).service(projectArtifactId);

        RouteAssert.assertRoute(openShiftClient, projectArtifactId);
        executeCRUDAssertions(clientOperations.applicationRouteWithName(projectArtifactId));
    }

    @Test
    public void redeploy_springboot_app() throws Exception {
        deployDatabaseUsingCLI();

        // Make some changes in ConfigMap and rollout
        final String projectArtifactId = pomModifier.getArtifactId();
        pomModifier.addCommonLangDependency();
        final String annotationKey = "springboot-crud-testKey";
        final String annotationValue = "springboot-crud-testValue";
        pomModifier.addRedeploymentAnnotations(annotationKey, annotationValue,
           FMP_PLUGIN_CONFIG_FILE);

        deploy(project.getTargetPath(), EMBEDDED_MAVEN_FABRIC8_BUILD_GOAL, EMBEDDED_MAVEN_FABRIC8_BUILD_PROFILE);
        clientOperations.waitTillApplicationPodStarts(annotationKey, annotationValue, projectArtifactId);

        TimeUnit.SECONDS.sleep(20);

        assertThat(openShiftClient).deployment(projectArtifactId);
        assertThat(openShiftClient).service(projectArtifactId);

        RouteAssert.assertRoute(openShiftClient, projectArtifactId);
        executeCRUDAssertions(clientOperations.applicationRouteWithName(projectArtifactId));
    }

    private void executeCRUDAssertions(Route applicationRoute) throws Exception {
        String hostUrl = "http://" + applicationRoute.getSpec().getHost() + "/api/fruits";
        JSONObject jsonRequest = new JSONObject();
        String testFruitName = "Pineapple";

        // limiting test scope, a basic write followed by read is sufficient.
        // (C) Create
        jsonRequest.put("name", testFruitName);
        Response createResponse = makeHttpRequest(HttpRequestType.POST, hostUrl, jsonRequest.toString());
        assert createResponse.code() == HttpStatus.SC_CREATED;
        Integer fruitId = new JSONObject(createResponse.body().string()).getInt("id");
        hostUrl += ("/" + fruitId.toString());

        // (R) Read
        Response readResponse = makeHttpRequest(HttpRequestType.GET, hostUrl, null);
        String fruitName = new JSONObject(readResponse.body().string()).getString("name");
        assert readResponse.code() == HttpStatus.SC_OK;
        assert fruitName.equals(testFruitName);
    }


    private void deployDatabaseUsingCLI() throws Exception {
        /*
          Currently kubernetes-client doesn't have any support for oc new-app. So for now
          doing this.
         */
        execCommand("oc new-app -ePOSTGRESQL_USER=luke -ePOSTGRESQL_PASSWORD=secret " +
           " -ePOSTGRESQL_DATABASE=my_data openshift/postgresql-92-centos7 --name=my-database");
    }
}
