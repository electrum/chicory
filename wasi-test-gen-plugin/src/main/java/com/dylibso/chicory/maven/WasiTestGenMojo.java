package com.dylibso.chicory.maven;

import static com.dylibso.chicory.maven.StringUtils.capitalize;
import static com.dylibso.chicory.maven.StringUtils.escapedCamelCase;
import static java.util.stream.Collectors.toList;
import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_TEST_SOURCES;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.ConfigInvalidException;

/**
 * This plugin generates test classes for the WASI test suite.
 */
@Mojo(name = "wasi-test-gen", defaultPhase = GENERATE_TEST_SOURCES, threadSafe = true)
public class WasiTestGenMojo extends AbstractMojo {

    private static final ClassName JUNIT_TEST = ClassName.get("org.junit.jupiter.api", "Test");
    private static final ClassName WASI_TEST_RUNNER =
            ClassName.get("com.dylibso.chicory.wasi", "WasiTestRunner");

    private final Log log = new SystemStreamLog();

    /**
     * Repository of the test suite.
     */
    @Parameter(required = true, defaultValue = "https://github.com/WebAssembly/wasi-testsuite")
    private String testSuiteRepo;

    /**
     * Repository of the test suite.
     */
    @Parameter(required = true, defaultValue = "prod/testsuite-base")
    private String testSuiteRepoRef;

    /**
     * Location for the test suite.
     */
    @Parameter(required = true)
    private File testSuiteFolder;

    /**
     * Test suite files to process.
     */
    @Parameter(required = true)
    private FileSet testSuiteFiles;

    /**
     * Location for the junit generated sources.
     */
    @Parameter(
            required = true,
            defaultValue = "${project.build.directory}/generated-test-sources/test-gen")
    private File sourceDestinationFolder;

    /**
     * The current Maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            new WasiTestSuiteDownloader(log)
                    .downloadTestsuite(testSuiteRepo, testSuiteRepoRef, testSuiteFolder);
        } catch (GitAPIException | ConfigInvalidException | IOException e) {
            throw new MojoExecutionException("Failed to download testsuite: " + e.getMessage(), e);
        }

        if (testSuiteFiles.getDirectory() == null) {
            testSuiteFiles.setDirectory(testSuiteFolder.getAbsolutePath());
        }

        // find all *.wasm test cases
        FileSetManager fileSetManager = new FileSetManager();
        String[] includedFiles = fileSetManager.getIncludedFiles(testSuiteFiles);
        List<File> allFiles =
                Stream.of(includedFiles)
                        .map(file -> new File(testSuiteFiles.getDirectory(), file))
                        .sorted()
                        .collect(toList());
        if (allFiles.isEmpty()) {
            throw new MojoExecutionException("No files found in the test suite");
        }

        // validate and group files by test suite
        PathMatcher pathMatcher =
                FileSystems.getDefault().getPathMatcher("glob:**/tests/*/testsuite/*.wasm");

        Map<String, List<File>> filesBySuite = new LinkedHashMap<>();
        for (File file : allFiles) {
            Path path = file.toPath();
            if (!pathMatcher.matches(path)) {
                throw new MojoExecutionException("Invalid test suite file path: " + path);
            }
            String suiteName = path.getParent().getParent().getFileName().toString();
            filesBySuite.computeIfAbsent(suiteName, ignored -> new ArrayList<>()).add(file);
        }

        // generate test classes
        for (var entry : filesBySuite.entrySet()) {
            String testSuite = entry.getKey();
            List<File> files = entry.getValue();

            // generate test methods
            List<MethodSpec> methods = new ArrayList<>();
            for (File file : files) {
                String baseName =
                        file.getName().substring(0, file.getName().length() - ".wasm".length());

                Specification specification =
                        readSpecification(new File(file.getParentFile(), baseName + ".json"));

                CodeBlock codeBlock =
                        CodeBlock.builder()
                                .addStatement(
                                        "$1T test = new $1T($2S)", File.class, cannonicalFile(file))
                                .addStatement(
                                        "List<String> args = $L", listOf(specification.args()))
                                .addStatement(
                                        "List<String> dirs = $L", listOf(specification.dirs()))
                                .addStatement(
                                        "Map<String, String> env = $L", mapOf(specification.env()))
                                .addStatement("int exitCode = $L", specification.exitCode())
                                .addStatement("String stderr = $S", specification.stderr())
                                .addStatement("String stdout = $S", specification.stdout())
                                .addStatement(
                                        "$T.execute($L)",
                                        WASI_TEST_RUNNER,
                                        "test, args, dirs, env, exitCode, stderr, stdout")
                                .build();

                methods.add(
                        MethodSpec.methodBuilder("test" + escapedCamelCase(baseName))
                                .addModifiers(Modifier.PUBLIC)
                                .returns(void.class)
                                .addAnnotation(AnnotationSpec.builder(JUNIT_TEST).build())
                                .addCode(codeBlock)
                                .build());
            }

            // write the test class
            TypeSpec typeSpec =
                    TypeSpec.classBuilder("Suite" + capitalize(testSuite) + "Test")
                            .addModifiers(Modifier.PUBLIC)
                            .addMethods(methods)
                            .build();

            try {
                JavaFile.builder("com.dylibso.chicory.test.gen", typeSpec)
                        .build()
                        .writeTo(sourceDestinationFolder);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to generate test suite", e);
            }
        }

        // add generated sources to the project
        project.addTestCompileSourceRoot(sourceDestinationFolder.getAbsolutePath());
    }

    private static Specification readSpecification(File json) throws MojoExecutionException {
        if (!json.isFile()) {
            return Specification.createDefault();
        }
        try {
            return new ObjectMapper().readValue(json, Specification.class);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read specification file: " + json, e);
        }
    }

    private static CodeBlock listOf(List<String> list) {
        return CodeBlock.of(
                "$T.of($L)",
                List.class,
                list.stream()
                        .map(value -> CodeBlock.of("$S", value))
                        .collect(CodeBlock.joining(", ")));
    }

    private static CodeBlock mapOf(Map<String, String> map) {
        return CodeBlock.of(
                "$T.of($L)",
                Map.class,
                map.entrySet().stream()
                        .map(entry -> CodeBlock.of("$S, $S", entry.getKey(), entry.getValue()))
                        .collect(CodeBlock.joining(", ")));
    }

    private static File cannonicalFile(File file) throws MojoExecutionException {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to canonicalize path: " + file, e);
        }
    }
}
