package io.fabric8.maven.rt.rules;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.arquillian.smart.testing.rules.git.GitClone;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import org.junit.runners.model.Statement;

public class Project implements TestRule {

   private final GitClone gitClone;

   private String targetRepoPerTestFolder;

   private String targetPath;

   public Project(GitClone gitClone) {
      this.gitClone = gitClone;
   }

   @Override
   public Statement apply(Statement statement, Description description) {
      return this.statement(statement, description);
   }

   private Statement statement(final Statement base, final Description description) {

      return new Statement() {
         public void evaluate() throws Throwable {
            before(description);
            List<Throwable> errors = new ArrayList<>();

            try {
               base.evaluate();
            } catch (Throwable e) {
               errors.add(e);
            }
            MultipleFailureException.assertEmpty(errors);
         }
      };
   }

   private void before(Description description) throws IOException {
      targetRepoPerTestFolder = targetRepoPerTestFolder(description);
      targetPath = createPerTestRepository().toString();
   }

   private String targetRepoPerTestFolder(Description description) {
      return gitClone.getGitRepoFolder()
         + "_"
         + description.getTestClass().getSimpleName()
         + "_"
         + description.getMethodName();
   }

   private Path createPerTestRepository() throws IOException {
      final Path source = Paths.get(gitClone.getGitRepoFolder().toURI());
      final Path target = Paths.get(targetRepoPerTestFolder);
      copyDirectory(source, target);
      return target;
   }

   private void copyDirectory(Path source, Path target) throws IOException {
      FileUtils.copyDirectory(source.toFile(), target.toFile());
   }

   public String getTargetPath() {
      return targetPath;
   }
}
