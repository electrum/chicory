package com.dylibso.chicory.python;

import com.dylibso.chicory.aot.AotMachine;
import com.dylibso.chicory.log.SystemLogger;
import com.dylibso.chicory.runtime.ExternalValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import java.io.File;
import java.util.List;

public final class ChicoryPythonWlr {
    private static final File PYTHON_WASM =
            new File(System.getProperty("user.home"), "Downloads/python-3.12.0.wasm");

    private ChicoryPythonWlr() {}

    public static void main(String[] args) {
        System.setProperty(
                "java.util.logging.SimpleFormatter.format", "%1$tF %1$tT.%1$tL %4$s %5$s%6$s%n");

        WasiOptions wasiOptions =
                WasiOptions.builder()
                        .withStdin(System.in)
                        .withStdout(System.out)
                        .withStderr(System.out)
                        .withArguments(List.of("python", "-v", "-c", "print('Hello, World!')"))
                        .build();

        WasiPreview1 wasi = new WasiPreview1(new SystemLogger(), wasiOptions);

        Instance.builder(Parser.parse(PYTHON_WASM))
                .withExternalValues(new ExternalValues(wasi.toHostFunctions()))
                .withMachineFactory(AotMachine::new)
                .build();
    }
}
