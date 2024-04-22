package com.dylibso.chicory.python;

import static com.dylibso.chicory.wasi.Files.copyDirectory;

import com.dylibso.chicory.aot.AotMachine;
import com.dylibso.chicory.log.SystemLogger;
import com.dylibso.chicory.runtime.ExternalValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiExitException;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;

public final class ChicoryPython {
    private static final File PYTHON_ROOT = new File("/Users/dphillips/tmp/cpython");
    private static final File PYTHON_WASM =
            new File(PYTHON_ROOT, "cross-build/wasm32-wasi/python.wasm");
    private static final String PYTHON_PATH =
            "/cross-build/wasm32-wasi/build/lib.wasi-wasm32-3.13-pydebug";
    private static final String HELLO_WORLD = "print('Hello, World!')";

    //    private static final String PRETTY_PRINT =
    //            "import sys; from pprint import pprint as pp; pp(sys.path); pp(sys.platform)";

    static {
        System.setProperty(
                "java.util.logging.SimpleFormatter.format",
                "%1$tY-%1$tm-%1$tdT%1$tH:%1$tM:%1$tS.%1$tL%1$tz %4$s %5$s%6$s%n");
    }

    private ChicoryPython() {}

    public static void main(String[] args) throws IOException {
        python();
    }

    private static void python() throws IOException {
        FileSystem fs =
                Jimfs.newFileSystem(
                        Configuration.unix().toBuilder().setAttributeViews("unix").build());

        Path root = fs.getPath("/");
        copyDirectory(PYTHON_ROOT.toPath(), root);

        WasiOptions wasiOptions =
                WasiOptions.builder()
                        .withStdin(System.in)
                        .withStdout(System.out)
                        .withStderr(System.out)
                        .withDirectory("/", root)
                        // .withArguments(List.of("python", "-V"))
                        .withArguments(List.of("python", "-c", HELLO_WORLD))
                        // .withArguments(List.of("python", "-c", PRETTY_PRINT))
                        .withEnvironment("PYTHONPATH", PYTHON_PATH)
                        .build();

        WasiPreview1 wasi = new WasiPreview1(new SystemLogger(), wasiOptions);

        Instance instance =
                Instance.builder(Parser.parse(PYTHON_WASM))
                        .withExternalValues(new ExternalValues(wasi.toHostFunctions()))
                        .withMachineFactory(AotMachine::new)
                        .withStart(false)
                        .build();

        try {
            instance.export(Instance.START_FUNCTION_NAME).apply();
        } catch (WasiExitException e) {
            System.out.println(e.getCause().getMessage());
        }
    }
}
