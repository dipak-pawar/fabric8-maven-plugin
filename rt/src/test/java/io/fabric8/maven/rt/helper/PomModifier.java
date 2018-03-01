package io.fabric8.maven.rt.helper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import static io.fabric8.maven.rt.BaseBoosterIT.FABRIC8_MAVEN_PLUGIN_KEY;
import static io.fabric8.maven.rt.BaseBoosterIT.POM_XML;

public class PomModifier {

   private static final Logger logger = Logger.getLogger(PomModifier.class.getName());
   private static final String FMP_GROUP_ID = "io.fabric8";
   private static final String FMP_ARTIFACT_ID = "fabric8-maven-plugin";

   private final String projectRoot;
   private String artifactId;

   public PomModifier(String projectRoot) {
      this.projectRoot = projectRoot;
   }

   private Model readPomModelFromFile(File pomFile) throws XmlPullParserException, IOException {
      return new MavenXpp3Reader().read(new FileInputStream(pomFile));
   }

   public void modifyPomFileToProjectVersion() throws IOException, XmlPullParserException {
        /*
          Read Maven model from the project pom file(Here the pom file is not the test repository is cloned
          for the test suite. It refers to the rt/ project and fetches the current version of fabric8-maven-plugin
          (any SNAPSHOT version) and updates that accordingly in the sample cloned project's pom.
         */
      final Model currentProjectPomModel = readPomModelFromFile(new File(POM_XML));
      final String fmpCurrentVersion = currentProjectPomModel.getVersion();
      File clonedRepositoryPomFile = Paths.get(projectRoot, "pom.xml").toFile();
      Model model = readPomModelFromFile(clonedRepositoryPomFile);
      artifactId = model.getArtifactId();

      // Check if fmp is not present in openshift profile
      model = updatePomIfFmpNotPresent(model, clonedRepositoryPomFile);
      Build build = model.getBuild();

        /*
         * Handle the scenarios where build is in outermost scope or present
         * specifically in openshift profile.
         */
      List<Profile> profiles = model.getProfiles();
      if (build != null && build.getPluginsAsMap().get(FMP_GROUP_ID + ":" + FMP_ARTIFACT_ID) != null) {
         build.getPluginsAsMap().get(FMP_GROUP_ID + ":" + FMP_ARTIFACT_ID).setVersion(fmpCurrentVersion);
      } else {
         for (Profile profile : profiles) {
            if (profile.getBuild() != null
               && profile.getBuild().getPluginsAsMap().get(FMP_GROUP_ID + ":" + FMP_ARTIFACT_ID) != null) {
               profile.getBuild().getPluginsAsMap()
                  .get(FMP_GROUP_ID + ":" + FMP_ARTIFACT_ID)
                  .setVersion(fmpCurrentVersion);
            }
         }
      }

      // Write back the updated model to the pom file
      writePomModelToFile(clonedRepositoryPomFile, model);
   }

   private Model updatePomIfFmpNotPresent(Model projectModel, File pomFile) throws XmlPullParserException, IOException {
      if (getProfileIndexUsingFmp(projectModel, FABRIC8_MAVEN_PLUGIN_KEY) < 0) {

         projectModel = writeOpenShiftProfileInPom(projectModel, pomFile);
      }

      return projectModel;
   }

   private Model writeOpenShiftProfileInPom(Model projectModel, File pomFile) throws XmlPullParserException, IOException {

      final PluginExecution aPluginExecution = new PluginExecution();
      aPluginExecution.setId("fmp");
      aPluginExecution.addGoal("resource");
      aPluginExecution.addGoal("build");

      final List<PluginExecution> executions = new ArrayList<>();
      executions.add(aPluginExecution);

      final Plugin plugin = new Plugin();
      plugin.setGroupId(FMP_GROUP_ID);
      plugin.setArtifactId(FMP_ARTIFACT_ID);
      plugin.setExecutions(executions);

      Build build = new Build();
      build.getPlugins().add(plugin);

      int nOpenShiftIndex;
      Profile fmpProfile;
      if ((nOpenShiftIndex = getProfileIndexWithName(projectModel, "openshift")) > 0) { // update existing profile
         fmpProfile = projectModel.getProfiles().get(nOpenShiftIndex);
         fmpProfile.setBuild(build);
         projectModel.getProfiles().set(nOpenShiftIndex, fmpProfile);
      } else { // if not present, simply create a profile names openshift which would contain fmp.
         fmpProfile = new Profile();
         fmpProfile.setId("openshift");
         fmpProfile.setBuild(build);
         projectModel.addProfile(fmpProfile);
      }
      writePomModelToFile(pomFile, projectModel);

      return projectModel;
   }

   private void writePomModelToFile(File pomFile, Model model) throws IOException {
      final MavenXpp3Writer mavenXpp3Writer = new MavenXpp3Writer();
      mavenXpp3Writer.write(new FileOutputStream(pomFile), model);
   }

   public String getArtifactId() {
      return artifactId;
   }

   private int getProfileIndexUsingFmp(Model aPomModel, String pluginName) {
      List<Profile> profiles = aPomModel.getProfiles();
      for (int nIndex = 0; nIndex < profiles.size(); nIndex++) {
         if (profiles.get(nIndex).getBuild() != null
            && profiles.get(nIndex).getBuild().getPluginsAsMap().containsKey(pluginName)) {
            return nIndex;
         }
      }
      logger.log(Level.WARNING, "No profile found in project's pom.xml using fmp");
      return -1;
   }

   private int getProfileIndexWithName(Model aPomModel, String profileId) {
      List<Profile> profiles = aPomModel.getProfiles();
      for (int nIndex = 0; nIndex < profiles.size(); nIndex++) {
         if (profiles.get(nIndex).getId().equals(profileId)) {
            return nIndex;
         }
      }
      logger.log(Level.WARNING, String.format("No profile found in project's pom.xml containing %s", profileId));
      return -1;
   }

   /**
    * Appends some annotation properties to the fmp's configuration in test repository's pom
    * just to distinguish whether the application is re-deployed or not.
    */
   public void addRedeploymentAnnotations(String annotationKey,
      String annotationValue, String fmpConfigFragmentFile) throws IOException, XmlPullParserException {
      File pomFile = Paths.get(projectRoot, POM_XML).toFile();
      Model model = new MavenXpp3Reader().read(new FileInputStream(pomFile));

      File pomFragment = new File(getClass().getResource(fmpConfigFragmentFile).getFile());
      String pomFragmentStr =
         String.format(FileUtils.readFileToString(pomFragment), annotationKey, annotationValue, annotationKey,
            annotationValue);

      Xpp3Dom configurationDom = Xpp3DomBuilder.build(
         new ByteArrayInputStream(pomFragmentStr.getBytes()),
         "UTF-8");

      int nOpenShiftProfile = getProfileIndexUsingFmp(model, FABRIC8_MAVEN_PLUGIN_KEY);
      model.getProfiles()
         .get(nOpenShiftProfile)
         .getBuild()
         .getPluginsAsMap()
         .get(FABRIC8_MAVEN_PLUGIN_KEY)
         .setConfiguration(configurationDom);
      writePomModelToFile(pomFile, model);
   }

   public void addCommonLangDependency() throws XmlPullParserException, IOException {
      final File pomFile = Paths.get(projectRoot, POM_XML).toFile();
      Model model = new MavenXpp3Reader().read(new FileInputStream(pomFile));

      Dependency dependency = new Dependency();
      dependency.setGroupId("org.apache.commons");
      dependency.setArtifactId("commons-lang3");
      dependency.setVersion("3.5");
      model.getDependencies().add(dependency);

      new MavenXpp3Writer().write(new FileOutputStream(pomFile), model);
   }

}
