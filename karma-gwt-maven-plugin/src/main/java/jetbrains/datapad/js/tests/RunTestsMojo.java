package jetbrains.datapad.js.tests;

/*
 * Copyright 2012-2016 JetBrains s.r.o
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;


@Mojo(name = "run-tests", defaultPhase = LifecyclePhase.INTEGRATION_TEST)
public class RunTestsMojo extends AbstractMojo {

  private enum Resource {
    LIB("lib"), ADAPTER("karmaGWT", "karma-gwt");

    private final String myResourceName;
    private final String myInstallName;

    Resource(String resourceName) {
      this(resourceName, resourceName);
    }

    Resource(String resourceName, String installName) {
      myResourceName = resourceName;
      myInstallName = installName;
    }
  }

  private static final Pattern BASE_PATH = Pattern.compile("%BASE_PATH%");
  private static final Pattern TEST_MODULE = Pattern.compile("'%TEST_MODULE%'");

  @Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
  private File outputDirectory;

  @Parameter(defaultValue = "${project.artifactId}")
  private String projectArtifactId;

  @Parameter(defaultValue = "${project.version}")
  private String projectVersion;

  @Parameter(property="testRunner")
  private String testRunner;

  @Parameter(property="testModules", required = true)
  private List<String> testModules;

  @Parameter(property="basePath")
  private String basePath;

  @Parameter(property="karmaPath")
  private Path karmaPath;

  private Path myKarmaConfig;

  private String myTestModules;

  public void execute()
      throws MojoExecutionException {

    initVars();
    runAction(this::setupKarma, "failed to install Karma");
    runAction(this::runKarma, "failed at karma");
  }

  private void initVars() {
    if (basePath == null) {
      if (testRunner != null) {
        basePath = outputDirectory.toPath().resolve(testRunner).toString();
      } else {
        basePath = outputDirectory.toPath().resolve(projectArtifactId + "-" + projectVersion).toString();
      }
    }
    karmaPath = outputDirectory.toPath().getParent();
    myTestModules = testModules.stream().map(testModule -> "'" + testModule + "'").collect(Collectors.joining(","));
    getLog().info("basePath        = " + basePath);
    getLog().info("karmaSetupPath  = " + karmaPath);
    getLog().info("testModules     = " + myTestModules);
  }

  private boolean setupKarma() throws URISyntaxException, IOException, InterruptedException {
    URI libs = this.getClass().getResource(Resource.LIB.myResourceName).toURI();
    processResources(libs, fs -> fs.provider().getPath(libs), resource -> {
      try (InputStreamReader inputStreamReader = new InputStreamReader(Files.newInputStream(resource));
           BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
        List<String> lines = new ArrayList<>();
        for (String line = bufferedReader.readLine(); line != null; line = bufferedReader.readLine()) {
          lines.add(line);
        }
        String rFileName = resource.getFileName().toString();
        Path rPath = karmaPath.resolve(rFileName);
        if (rFileName.equals("karma.conf.js")) {
          myKarmaConfig = rPath;
        }
        Files.write(
            rPath,
            lines.stream().map(
                s -> BASE_PATH.matcher(TEST_MODULE.matcher(s).replaceAll(myTestModules)).replaceAll(basePath)).collect(Collectors.toList()),
            Charset.defaultCharset(), StandardOpenOption.WRITE, StandardOpenOption.CREATE);
      }
    });
    return runProcess("npm", "install");
  }

  private boolean runProcess(String... command) throws IOException, InterruptedException {
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    Process installDepProcess = processBuilder.inheritIO().directory(karmaPath.toFile()).start();
    return installDepProcess.waitFor() == 0;
  }

  private boolean runKarma() throws URISyntaxException, IOException, InterruptedException {
    Path targetDirectory = karmaPath.resolve(Paths.get("node_modules", Resource.ADAPTER.myInstallName));
    Path karma = karmaPath.resolve(Paths.get("node_modules", ".bin", "karma"));
    Files.createDirectories(targetDirectory);
    URI resourceDirectory = this.getClass().getResource(Resource.ADAPTER.myResourceName).toURI();
    processResources(resourceDirectory, fs -> fs.provider().getPath(resourceDirectory),
        resource -> Files.copy(resource, targetDirectory.resolve(resource.getFileName().toString()), REPLACE_EXISTING));
    return runProcess(karma.toAbsolutePath().toString(), "start", myKarmaConfig.toAbsolutePath().toString());
  }

  private interface ResourceProcessor {
    void process(Path resource) throws IOException;
  }

  private interface ContainerProvider {
    Path getContainer(FileSystem fs) throws URISyntaxException, IOException;
  }

  private void processResources(URI contentUri, ContainerProvider containerProvider, ResourceProcessor processor) throws URISyntaxException, IOException {
    try (
        FileSystem fs = FileSystems.newFileSystem(contentUri, Collections.emptyMap())
    ) {
      Path containerPath = containerProvider.getContainer(fs);
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(containerPath)) {
        for (Path p : ds) {
          processor.process(p);
        }
      }
    }
  }

  private interface Action {
    boolean run() throws URISyntaxException, IOException, InterruptedException;
  }

  private void runAction(Action action, String errorMessage) throws MojoExecutionException {
    try {
      if (!action.run()) {
        throw new MojoExecutionException(errorMessage);
      }
    } catch (URISyntaxException | IOException | InterruptedException e) {
      e.printStackTrace();
      throw new MojoExecutionException(errorMessage);
    }
  }

}
