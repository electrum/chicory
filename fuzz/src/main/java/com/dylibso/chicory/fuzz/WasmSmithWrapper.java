package com.dylibso.chicory.fuzz;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.dylibso.chicory.log.Logger;
import com.dylibso.chicory.log.SystemLogger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.RandomStringUtils;

public class WasmSmithWrapper {

    private static final Logger logger = new SystemLogger();

    public static final List<String> BINARY_NAME = List.of("wasm-tools", "smith");

    private int BASE_SEED_SIZE = 1000;
    private String seed = getSeed(BASE_SEED_SIZE);

    // A smaller size of the seed speeds up the execution
    @SuppressWarnings("deprecation")
    private static String getSeed(int size) {
        return RandomStringUtils.randomAlphabetic(size);
    }

    WasmSmithWrapper() {}

    public File run(String subfolder, String fileName, InstructionTypes instructionTypes)
            throws IOException {
        return run(subfolder, fileName, instructionTypes, "/smith.default.properties");
    }

    @SuppressWarnings("StringSplitter")
    public File run(
            String subfolder,
            String fileName,
            InstructionTypes instructionTypes,
            String smithProperties)
            throws IOException {
        var targetSubfolder = "target/fuzz/data/" + subfolder;
        var targetFolder = new File(targetSubfolder);
        targetFolder.mkdirs();
        var targetFile = new File(targetSubfolder + "/" + fileName);
        var seedFile = new File(targetSubfolder + "/seed.txt");

        var command = new ArrayList<>(BINARY_NAME);
        // --ensure-termination true -> breaks the execution
        var defaultProperties = new ArrayList<String>();
        var propsFile =
                new String(getClass().getResourceAsStream(smithProperties).readAllBytes(), UTF_8);
        var props = propsFile.split("\n");
        for (var prop : props) {
            if (!prop.isEmpty()) {
                var split = prop.split("=");
                defaultProperties.add("--" + split[0]);
                defaultProperties.add(split[1]);
            }
        }
        command.addAll(defaultProperties);
        command.addAll(
                List.of(
                        "--allowed-instructions",
                        instructionTypes.toString(),
                        "--output",
                        targetFile.getAbsolutePath()));
        logger.info(
                "Going to execute command:\n"
                        + String.join(" ", command)
                        + " < "
                        + seedFile.getAbsolutePath());

        var retry = 3;
        // Trying to use the smallest possible seed for speed reasons
        var seedSize = BASE_SEED_SIZE;
        while (retry > 0) {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File("."));

            // write the seed file
            try (var outputStream = new FileOutputStream(seedFile)) {
                outputStream.write((seed).getBytes(UTF_8));
                outputStream.flush();
            }

            pb.redirectInput(seedFile);
            Process ps;
            try {
                ps = pb.start();
                ps.waitFor(10, TimeUnit.SECONDS);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                // Renew the seed
                seed = getSeed(seedSize);
                seedSize = seedSize * 10;
            }

            if (ps.exitValue() != 0) {
                logger.error("wasm-smith exiting with:" + ps.exitValue());
                logger.error(new String(ps.getErrorStream().readAllBytes(), UTF_8));
                retry--;
            } else {
                break;
            }
        }

        return targetFile;
    }
}
