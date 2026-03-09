package io.github.chains_project.aotp.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;

import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.List;

/**
 * Instruments pom.xml files in the reactor to configure AOTCache merging for surefire tests.
 * The first module with tests gets -XX:AOTCacheOutput only; subsequent modules also merge
 * from the previous module's cache via -XX:AOTMode=merge and -XX:AOTCache.
 */
@Mojo(name = "configure", aggregator = true, requiresProject = true)
public class ConfigureAotCacheMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Override
    public void execute() throws MojoExecutionException {
        List<MavenProject> projects = session.getProjects();

        MavenProject previousTestProject = null;

        for (MavenProject project : projects) {
            if ("pom".equals(project.getPackaging())) {
                getLog().debug("Skipping aggregator " + project.getArtifactId());
                continue;
            }

            if (!hasTests(project)) {
                getLog().info("Skipping " + project.getArtifactId() + " (no test sources found)");
                continue;
            }

            getLog().info("Configuring AOT cache for " + project.getArtifactId()
                    + (previousTestProject == null ? " (first in chain)" : " (merging from " + previousTestProject.getArtifactId() + ")"));

            modifyPom(project, previousTestProject);
            previousTestProject = project;
        }
    }

    private boolean hasTests(MavenProject project) {
        String testSourceDir = project.getBuild().getTestSourceDirectory();
        if (testSourceDir == null) {
            return false;
        }
        File dir = new File(testSourceDir);
        return dir.exists() && containsJavaFiles(dir);
    }

    private boolean containsJavaFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.isDirectory() && containsJavaFiles(f)) {
                return true;
            }
            if (f.isFile() && f.getName().endsWith(".java")) {
                return true;
            }
        }
        return false;
    }

    private void modifyPom(MavenProject project, MavenProject previousProject) throws MojoExecutionException {
        String argLine = buildArgLine(project, previousProject);
        File pomFile = project.getFile();

        try (FileReader reader = new FileReader(pomFile)) {
            org.apache.maven.model.Model model = new MavenXpp3Reader().read(reader);

            Build build = model.getBuild();
            if (build == null) {
                build = new Build();
                model.setBuild(build);
            }

            Plugin surefirePlugin = build.getPlugins().stream()
                    .filter(p -> "maven-surefire-plugin".equals(p.getArtifactId()))
                    .findFirst()
                    .orElse(null);

            if (surefirePlugin == null) {
                surefirePlugin = new Plugin();
                surefirePlugin.setArtifactId("maven-surefire-plugin");
                build.addPlugin(surefirePlugin);
            }

            // Prefer editing argLine inside an existing execution's configuration.
            // Execution-level config takes precedence over plugin-level config, so if argLine
            // is already set in an execution we must edit it there or it will be ignored.
            Xpp3Dom executionArgLine = surefirePlugin.getExecutions().stream()
                    .map(e -> (Xpp3Dom) e.getConfiguration())
                    .filter(c -> c != null && c.getChild("argLine") != null)
                    .map(c -> c.getChild("argLine"))
                    .findFirst()
                    .orElse(null);

            if (executionArgLine != null) {
                executionArgLine.setValue(argLine);
            } else {
                // No execution has argLine — use plugin-level configuration.
                Xpp3Dom config = (Xpp3Dom) surefirePlugin.getConfiguration();
                if (config == null) {
                    config = new Xpp3Dom("configuration");
                    surefirePlugin.setConfiguration(config);
                }
                Xpp3Dom argLineNode = config.getChild("argLine");
                if (argLineNode == null) {
                    argLineNode = new Xpp3Dom("argLine");
                    config.addChild(argLineNode);
                }
                argLineNode.setValue(argLine);
            }

            try (FileWriter writer = new FileWriter(pomFile)) {
                new MavenXpp3Writer().write(writer, model);
            }

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to modify pom.xml for " + project.getArtifactId(), e);
        }
    }

    private String buildArgLine(MavenProject project, MavenProject previousProject) {
        if (previousProject == null) {
            return "@{surefireArgLine} -Xlog:aot+merge=info -XX:AOTCacheOutput=cache.aot";
        }

        Path currentDir = project.getBasedir().toPath();
        Path previousDir = previousProject.getBasedir().toPath();
        // relativize from current to previous, e.g. fontbox -> ../io
        String relativeCachePath = currentDir.relativize(previousDir).toString().replace('\\', '/') + "/cache.aot";

        return "@{surefireArgLine} -Xlog:aot+merge=debug -XX:AOTMode=merge -XX:AOTCache="
                + relativeCachePath + " -XX:AOTCacheOutput=cache.aot";
    }
}
