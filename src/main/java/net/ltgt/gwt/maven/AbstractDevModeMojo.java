package net.ltgt.gwt.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.io.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public abstract class AbstractDevModeMojo extends AbstractMojo {

  /**
   * Sets the level of logging detail. Defaults to Maven's own log level.
   */
  @Parameter(property = "gwt.logLevel")
  protected GwtOptions.LogLevel logLevel;

  /**
   * Comma-delimited list of the modules to run.
   * <p>
   * Defaults to the discovered module names from {@code gwt-app} projects.
   */
  @Parameter(property = "gwt.modules")
  protected String modules;

  /**
   * Comma-delimited list of the reactor projects to run.
   * <p>
   * Defaults to all the {@code gwt-app} projects in the reactor.
   */
  @Parameter(property = "gwt.projects")
  protected String projects;

  /**
   * The dependency scope to use for the classpath.
   * <p>The scope should be one of the scopes defined by org.apache.maven.artifact.Artifact. This includes the following:
   * <ul>
   * <li><i>compile</i> - system, provided, compile
   * <li><i>runtime</i> - compile, runtime
   * <li><i>compile+runtime</i> - system, provided, compile, runtime
   * <li><i>runtime+system</i> - system, compile, runtime
   * <li><i>test</i> - system, provided, compile, runtime, test
   * </ul>
   */
  @Parameter(defaultValue = Artifact.SCOPE_RUNTIME)
  protected String classpathScope;

  /**
   * Specifies Java source level.
   */
  @Parameter(property = "maven.compiler.source")
  protected String sourceLevel;

  /**
   * Arguments to be passed to the forked JVM (e.g. {@code -Xmx})
   */
  @Parameter
  protected List<String> jvmArgs;

  /**
   * List of system properties to pass to the GWT compiler.
   */
  @Parameter
  protected Map<String, String> systemProperties;

  @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
  protected List<MavenProject> reactorProjects;

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  protected MavenProject project;

  @Parameter(defaultValue = "${plugin}", required = true, readonly = true)
  PluginDescriptor pluginDescriptor;


  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    List<MavenProject> projectList = new ArrayList<>();
    if (StringUtils.isBlank(projects)) {
      if (reactorProjects.size() == 1) {
        projectList.add(reactorProjects.get(0));
      } else {
        for (MavenProject p : reactorProjects) {
          if (p.getPackaging().equals("gwt-app")) {
            projectList.add(p);
          }
        }
      }
    } else {
      Map<String, MavenProject> projectMap = new HashMap<>();
      Set<String> ambiguousProjectIds = new LinkedHashSet<>();
      for (MavenProject p : reactorProjects) {
        String key = p.getArtifactId();
        if (projectMap.put(key, p) != null) {
          projectMap.remove(key);
          ambiguousProjectIds.add(key);
        }
        key = ":" + key;
        if (projectMap.put(key, p) != null) {
          projectMap.remove(key);
          ambiguousProjectIds.add(key);
        }
        key = p.getGroupId() + key;
        if (projectMap.put(key, p) != null) {
          projectMap.remove(key);
          ambiguousProjectIds.add(key);
        }
      }
      for (String key : StringUtils.split(projects, ",")) {
        MavenProject p = projectMap.get(key);
        if (p == null) {
          if (ambiguousProjectIds.contains(key)) {
            throw new MojoExecutionException("Ambiguous project identifier, there are several matching projects in the reactor: " + key);
          } else {
            throw new MojoExecutionException("Could not find the selected project in the reactor: " + key);
          }
        }
        projectList.add(p);
      }
    }

    if (projectList.isEmpty()) {
      throw new MojoExecutionException("No project found");
    }

    List<String> moduleList = new ArrayList<>();
    if (StringUtils.isBlank(modules)) {
      List<String> nonGwtProjects = new ArrayList<>();
      for (MavenProject p : projectList) {
        Xpp3Dom configuration = p.getGoalConfiguration(pluginDescriptor.getGroupId(), pluginDescriptor.getArtifactId(), null, null);
        if (configuration == null) {
          nonGwtProjects.add(ArtifactUtils.versionlessKey(p.getGroupId(), p.getArtifactId()));
        } else {
          moduleList.add(configuration.getChild("moduleName").getValue());
        }
      }
      if (!nonGwtProjects.isEmpty()) {
        getLog().warn("Found projects without the gwt-maven-plugin's moduleName when discovering GWT modules; they've been ignored: "
            + StringUtils.join(nonGwtProjects.iterator(), ", "));
      }
    } else {
      moduleList.addAll(Arrays.asList(StringUtils.split(modules, ",")));
    }

    if (moduleList.isEmpty()) {
      throw new MojoExecutionException("No module found");
    }

    LinkedHashSet<String> sources = new LinkedHashSet<>();
    for (MavenProject p : projectList) {
      addSources(p, sources);
    }

    List<String> args = new ArrayList<>();
    if (jvmArgs != null) {
      args.addAll(jvmArgs);
    }
    if (systemProperties != null) {
      for (Map.Entry<String, String> entry : systemProperties.entrySet()) {
        args.add("-D" + entry.getKey() + "=" + entry.getValue());
      }
    }

    args.add(getMainClass());
    args.add("-logLevel");
    args.add((logLevel == null ? GwtOptions.LogLevel.getLogLevel(getLog()) : logLevel).name());
    args.add("-workDir");
    args.add(getWorkDir().getAbsolutePath());
    if (sourceLevel != null) {
      args.add("-sourceLevel");
      args.add(sourceLevel);
    }
    args.addAll(getSpecificArguments(sources));
    args.addAll(moduleList);

    LinkedHashSet<String> cp = new LinkedHashSet<>();
    if (prependSourcesToClasspath()) {
      cp.addAll(sources);
    }
    for (MavenProject p : projectList) {
      ScopeArtifactFilter artifactFilter = new ScopeArtifactFilter(classpathScope);
      cp.add(p.getBuild().getOutputDirectory());
      for (Artifact artifact : p.getArtifacts()) {
        if (!artifact.getArtifactHandler().isAddedToClasspath()) {
          continue;
        }
        if (!artifactFilter.include(artifact)) {
          continue;
        }
        cp.add(artifact.getFile().getPath());
      }
    }

    try {
      FileUtils.forceMkdir(new File(project.getBuild().getDirectory()));
      FileUtils.forceMkdir(getWorkDir());
      forceMkdirs();
    } catch (IOException ioe) {
      throw new MojoFailureException(ioe.getMessage(), ioe);
    }

    Commandline commandline = new Commandline();
    commandline.setWorkingDirectory(project.getBuild().getDirectory());
    commandline.setExecutable(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
    commandline.addEnvironment("CLASSPATH", StringUtils.join(cp.iterator(), File.pathSeparator));
    commandline.addArguments(args.toArray(new String[args.size()]));

    if (getLog().isDebugEnabled()) {
      getLog().debug("Arguments: " + StringUtils.join(commandline.getArguments(), " "));
      getLog().debug("Classpath: " + StringUtils.join(cp.iterator(), File.pathSeparator));
    }

    int result;
    try {
      result = CommandLineUtils.executeCommandLine(commandline,
          new StreamConsumer() {
            @Override
            public void consumeLine(String s) {
              getLog().info(s);
            }
          },
          new StreamConsumer() {
            @Override
            public void consumeLine(String s) {
              getLog().warn(s);
            }
          });
    } catch (CommandLineException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    }
    if (result != 0) {
      throw new MojoExecutionException("GWT exited with status " + result);
    }
  }

  protected abstract String getMainClass();

  protected abstract File getWorkDir();

  protected abstract Collection<String> getSpecificArguments(Set<String> sources);

  protected boolean prependSourcesToClasspath() {
    return false;
  }

  protected abstract void forceMkdirs() throws IOException;

  private void addSources(MavenProject p, LinkedHashSet<String> sources) {
    getLog().debug("Adding sources for " + p.getId());
    if (p.getExecutionProject() != null) {
      p = p.getExecutionProject();
    }
    sources.addAll(p.getCompileSourceRoots());
    ScopeArtifactFilter artifactFilter = new ScopeArtifactFilter(classpathScope);
    for (Artifact artifact : p.getDependencyArtifacts()) {
      if (!artifact.getArtifactHandler().isAddedToClasspath()) {
        continue;
      }
      if (!artifactFilter.include(artifact)) {
        continue;
      }
      if (!"gwt-lib".equals(artifact.getArtifactHandler().getPackaging()) &&
          !"sources".equals(artifact.getClassifier())) {
        getLog().debug("Ignoring " + artifact.getId() + "; neither a gwt-lib or jar:sources.");
        continue;
      }
      String key = ArtifactUtils.key(artifact);
      MavenProject reference = p.getProjectReferences().get(key);
      if (reference == null) {
        getLog().debug("Ignoring " + artifact.getId() + "; no corresponding project reference.");
        continue;
      }
      addSources(reference, sources);
    }
  }
}
