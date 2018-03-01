package io.fabric8.maven.rt.rules;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.maven.rt.helper.OpenshiftClientOperations;
import io.fabric8.maven.rt.helper.PomModifier;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import java.io.IOException;
import org.arquillian.smart.testing.rules.git.GitClone;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

public class TestBed extends GitClone {

   private String testClassNamespace;
   private OpenShiftClient openShiftClient;
   private PomModifier pomModifier;

   public OpenshiftClientOperations getClientOperations() {
      return clientOperations;
   }

   private OpenshiftClientOperations clientOperations;

   public TestBed(String repositoryUrl) {
      super(repositoryUrl);
   }

   public OpenShiftClient getOpenShiftClient() {
      return openShiftClient;
   }

   public String getTestClassNamespace() {
      return testClassNamespace;
   }

   protected void before() throws Throwable {
      super.before();
      openShiftClient = new DefaultOpenShiftClient(new ConfigBuilder().build());
      testClassNamespace = openShiftClient.getNamespace();
      clientOperations = new OpenshiftClientOperations(openShiftClient, testClassNamespace);
      try {
         pomModifier = new PomModifier(getGitRepoFolder().getAbsolutePath());
         pomModifier.modifyPomFileToProjectVersion();
      } catch (IOException e) {
         throw new RuntimeException("Failed to update pom file for latest fabric8 maven plugin", e);
      } catch (XmlPullParserException e) {
         throw new IllegalArgumentException("Failed to update pom file for latest fabric8 maven plugin", e);
      }
   }

   protected void after() {
      super.after();
      openShiftClient.close();
   }

   public PomModifier getPomModifier() {
      return pomModifier;
   }
}
