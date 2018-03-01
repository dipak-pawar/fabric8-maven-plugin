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

import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.eclipse.jetty.http.HttpStatus;
import org.jboss.shrinkwrap.resolver.api.maven.embedded.EmbeddedMaven;
import org.json.JSONObject;

import static io.fabric8.maven.rt.helper.CommandExecutor.execCommand;

public abstract class BaseBoosterIT {

    static final String FMP_PLUGIN_CONFIG_FILE = "/fmp-plugin-config.xml";
    private final static Logger logger = Logger.getLogger(BaseBoosterIT.class.getSimpleName());
    public static final String FABRIC8_MAVEN_PLUGIN_KEY = "io.fabric8:fabric8-maven-plugin";
    public static final String POM_XML = "pom.xml";

    void deploy(String targetPath, String buildGoal, String buildProfile) throws Exception {
        runEmbeddedMavenBuild(targetPath, buildGoal, buildProfile);
    }

    private void runEmbeddedMavenBuild(String targetPath, String goals, String profiles) {
        EmbeddedMaven.forProject(Paths.get(targetPath, POM_XML).toString())
                .setGoals(goals)
                .setProfiles(profiles)
                .build();
    }

    enum HttpRequestType {
        GET, POST, PUT, DELETE;
    }


    /**
     * Just makes a basic GET request to the url provided as parameter and returns Response.
     *
     * @param hostUrl
     * @return
     * @throws Exception
     */
    Response makeHttpRequest(HttpRequestType requestType, String hostUrl, String params)
       throws IOException, IllegalStateException {
        OkHttpClient okHttpClient = new OkHttpClient();
        MediaType json = MediaType.parse("application/json; charset=utf-8");
        params = (params == null ? new JSONObject().toString() : params);
        Request request;
        RequestBody requestBody = RequestBody.create(json, params);

        switch (requestType) {
            case GET:
                request = new Request.Builder().url(hostUrl).get().build();
                break;
            case POST:
                request = new Request.Builder().url(hostUrl).post(requestBody).build();
                break;
            case PUT:
                request = new Request.Builder().url(hostUrl).put(requestBody).build();
                break;
            case DELETE:
                request = new Request.Builder().url(hostUrl).delete(requestBody).build();
                break;
            default:
                logger.info("No valid Http request type specified, using GET instread.");
                request = new Request.Builder().url(hostUrl).get().build();
        }

        // Sometimes nip.io is not up, so handling that case too.
        try {
            Response response = okHttpClient.newCall(request).execute();
            if (logger.isLoggable(Level.INFO)) {
                logger.info(String.format("[%s] %s %s", requestType.name(), hostUrl, HttpStatus.getCode(response.code())));
            }

            return response;
        } catch (UnknownHostException unknownHostException) {
            throw new IllegalStateException("No Host with name " + hostUrl + "found, maybe nip.io is down!");
        }
    }

    void addViewRoleToDefaultServiceAccount() throws IOException, InterruptedException {
        execCommand("oc policy add-role-to-user view -z default");
    }
}
