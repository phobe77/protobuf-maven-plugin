package com.google.protobuf.maven;

import com.google.common.collect.ImmutableSet;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.io.RawInputStreamFacade;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static com.google.common.base.Join.join;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Sets.newHashSet;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.list;
import static org.codehaus.plexus.util.FileUtils.cleanDirectory;
import static org.codehaus.plexus.util.FileUtils.copyStreamToFile;
import static org.codehaus.plexus.util.FileUtils.forceDeleteOnExit;
import static org.codehaus.plexus.util.FileUtils.getFiles;

/**
 * Abstract Mojo implementation.
 * <p/>
 * This class is extended by {@link com.google.protobuf.maven.ProtocCompileMojo} and
 * {@link com.google.protobuf.maven.ProtocTestCompileMojo} in order to override the specific configuration for
 * compiling the main or test classes respectively.
 *
 * @author Gregory Kick
 * @author David Trott
 * @author Brice Figureau
 */
abstract class AbstractProtocMojo extends AbstractMojo {

    private static final String PROTO_FILE_SUFFIX = ".proto";

    private static final String DEFAULT_INCLUDES = "**/*" + PROTO_FILE_SUFFIX;

    /**
     * The current Maven project.
     *
     * @parameter default-value="${project}"
     * @readonly
     * @required
     */
    protected MavenProject project;

    /**
     * A helper used to add resources to the project.
     *
     * @component
     * @required
     */
    protected MavenProjectHelper projectHelper;

    /**
     * This is the path to the {@code protoc} executable. By default it will
     * search the {@code $PATH}.
     *
     * @parameter default-value="protoc"
     * @required
     */
    private String protocExecutable;

    /**
     * @parameter
     */
    private File[] additionalProtopathElements = new File[]{};

    /**
     * Since {@code protoc} cannot access jars, proto files in dependencies are
     * extracted to this location and deleted on exit. This directory is always
     * cleaned during execution.
     *
     * @parameter expression="${project.build.directory}/protoc-dependencies"
     * @required
     */
    private File temporaryProtoFileDirectory;

    /**
     * This is the path to the local maven {@code repository}.
     *
     * @parameter default-value="${localRepository}"
     * @required
     */
    private DefaultArtifactRepository localRepository;

    /**
     * @parameter
     */
    private Set<String> includes = ImmutableSet.of(DEFAULT_INCLUDES);

    /**
     * @parameter
     */
    private Set<String> excludes = ImmutableSet.of();

    /**
     * Executes the mojo.
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        checkParameters();
        final File protoSourceRoot = getProtoSourceRoot();
        if (protoSourceRoot.exists()) {
            try {
                ImmutableSet<File> protoFiles = findProtoFilesInDirectory(protoSourceRoot);
                if (protoFiles.isEmpty()) {
                    getLog().info("No proto files to compile.");
                } else {
                    ImmutableSet<File> derivedProtopathElements =
                            makeProtopathFromJars(temporaryProtoFileDirectory, getDependencyArtifactFiles());
                    final File outputDirectory = getOutputDirectory();
                    outputDirectory.mkdirs();
                    Protoc protoc = new Protoc.Builder(protocExecutable, outputDirectory)
                            .addProtopathElement(protoSourceRoot)
                            .addProtopathElements(derivedProtopathElements)
                            .addProtopathElements(asList(additionalProtopathElements))
                            .addProtoFiles(protoFiles)
                            .build();
                    final int exitStatus = protoc.compile();
                    if (exitStatus != 0) {
                        getLog().error("Protoc failed output: " + protoc.getOutput());
                        getLog().error("Protoc failed error: " + protoc.getError());
                        throw new MojoFailureException(
                                "Protoc did not exit cleanly.  Review output for more information.");
                    }
                    attachFiles();
                }
            } catch (IOException e) {
                throw new MojoExecutionException("An IO error occured", e);
            } catch (IllegalArgumentException e) {
                throw new MojoFailureException("Protoc failed to execute because: " + e.getMessage(), e);
            } catch (CommandLineException e) {
                throw new MojoExecutionException("An error occured while invoking protoc.", e);
            }
        } else {
            getLog().info(format("%s does not exist.  Review the configuration or consider disabling the plugin.",
                    protoSourceRoot));
        }
    }

    private void checkParameters() {
        checkNotNull(project, "project");
        checkNotNull(projectHelper, "projectHelper");
        checkNotNull(protocExecutable, "protocExecutable");
        final File protoSourceRoot = getProtoSourceRoot();
        checkNotNull(protoSourceRoot);
        checkArgument(!protoSourceRoot.isFile(), "protoSourceRoot is a file, not a diretory");
        checkNotNull(temporaryProtoFileDirectory, "temporaryProtoFileDirectory");
        checkState(!temporaryProtoFileDirectory.isFile(), "temporaryProtoFileDirectory is a file, not a directory");
        final File outputDirectory = getOutputDirectory();
        checkNotNull(outputDirectory);
        checkState(!outputDirectory.isFile(), "the outputDirectory is a file, not a directory");
    }

    protected abstract File getProtoSourceRoot();

    protected abstract List<Artifact> getDependencyArtifacts();

    protected abstract File getOutputDirectory();

    protected abstract void attachFiles();

    /**
     * Gets the {@link File} for each dependency artifact.
     *
     * @return A set of all dependency artifacts.
     */
    private ImmutableSet<File> getDependencyArtifactFiles() {
        Set<File> dependencyArtifactFiles = newHashSet();
        for (Artifact artifact : getDependencyArtifacts()) {
            dependencyArtifactFiles.add(artifact.getFile());
        }
        return ImmutableSet.copyOf(dependencyArtifactFiles);
    }

    /**
     * @throws IOException
     */
    ImmutableSet<File> makeProtopathFromJars(File temporaryProtoFileDirectory, Iterable<File> classpathElementFiles)
            throws IOException {
        checkNotNull(classpathElementFiles, "classpathElementFiles");
        // clean the temporary directory to ensure that stale files aren't used
        if (temporaryProtoFileDirectory.exists()) {
            cleanDirectory(temporaryProtoFileDirectory);
        }
        Set<File> protoDirectories = newHashSet();
        for (File classpathElementFile : classpathElementFiles) {
            if (classpathElementFile.isFile() && classpathElementFile.canRead()) {
                JarFile classpathJar;
                try {
                    classpathJar = new JarFile(classpathElementFile);
                } catch (IOException e) {
                    throw new IllegalArgumentException(format(
                            "%s was not a readable artifact", classpathElementFile));
                }
                for (JarEntry jarEntry : list(classpathJar.entries())) {
                    final String jarEntryName = jarEntry.getName();
                    if (jarEntry.getName().endsWith(PROTO_FILE_SUFFIX)) {
                        final File uncompressedCopy =
                                new File(new File(temporaryProtoFileDirectory,
                                        truncatePath(classpathJar.getName())), jarEntryName);
                        uncompressedCopy.getParentFile().mkdirs();
                        copyStreamToFile(new RawInputStreamFacade(classpathJar
                                .getInputStream(jarEntry)), uncompressedCopy);
                        protoDirectories.add(uncompressedCopy.getParentFile());
                    }
                }
            } else if (classpathElementFile.isDirectory()) {
                File[] protoFiles = classpathElementFile.listFiles(new FilenameFilter() {
                    public boolean accept(File dir, String name) {
                        return name.endsWith(PROTO_FILE_SUFFIX);
                    }
                });

                if (protoFiles.length > 0) {
                    protoDirectories.add(classpathElementFile);
                }
            }
        }
        forceDeleteOnExit(temporaryProtoFileDirectory);
        return ImmutableSet.copyOf(protoDirectories);
    }

    ImmutableSet<File> findProtoFilesInDirectory(File directory) throws IOException {
        checkNotNull(directory);
        checkArgument(directory.isDirectory(), "%s is not a directory", directory);
        // TODO(gak): plexus-utils needs generics
        @SuppressWarnings("unchecked")
        List<File> protoFilesInDirectory = getFiles(directory, join(",", includes), join(",", excludes));
        return ImmutableSet.copyOf(protoFilesInDirectory);
    }

    ImmutableSet<File> findProtoFilesInDirectories(Iterable<File> directories) throws IOException {
        checkNotNull(directories);
        Set<File> protoFiles = newHashSet();
        for (File directory : directories) {
            protoFiles.addAll(findProtoFilesInDirectory(directory));
        }
        return ImmutableSet.copyOf(protoFiles);
    }

    /**
     * Truncates the path of jar files so that they are relative to the local repository.
     *
     * @param jarPath the full path of a jar file.
     * @return the truncated path relative to the local repository or root of the drive.
     */
    String truncatePath(final String jarPath) {
        String repository = localRepository.getBasedir().replace('\\', '/');
        if (!repository.endsWith("/")) {
            repository += "/";
        }

        String path = jarPath.replace('\\', '/');
        int repositoryIndex = path.indexOf(repository);
        if (repositoryIndex != -1) {
            path = path.substring(repositoryIndex + repository.length());
        }

        // By now the path should be good, but do a final check to fix windows machines.
        int colonIndex = path.indexOf(':');
        if (colonIndex != -1) {
            // 2 = :\ in C:\
            path = path.substring(colonIndex + 2);
        }

        return path;
    }
}