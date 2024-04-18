package com.dylibso.chicory.wasi;

import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.getFileAttributeView;
import static java.nio.file.Files.isSymbolicLink;
import static java.nio.file.Files.walkFileTree;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

public final class Files {
    private Files() {}

    public static void copyDirectory(Path source, Path target) throws IOException {
        System.Logger logger = System.getLogger("wasiFiles");
        walkFileTree(
                source,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        if (isSymbolicLink(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        logger.log(System.Logger.Level.INFO, "preVisitDirectory: dir=" + dir);
                        Path directory = target.resolve(source.relativize(dir).toString());
                        logger.log(
                                System.Logger.Level.INFO,
                                "preVisitDirectory: directory=" + directory);

                        if (!directory.toString().equals("/")) {
                            FileAttribute<?>[] attributes = new FileAttribute[0];
                            var attributeView =
                                    getFileAttributeView(dir, PosixFileAttributeView.class);
                            if (attributeView != null) {
                                var permissions = attributeView.readAttributes().permissions();
                                var attribute = PosixFilePermissions.asFileAttribute(permissions);
                                attributes = new FileAttribute[] {attribute};
                            }
                            logger.log(
                                    System.Logger.Level.INFO,
                                    "preVisitDirectory: attributes=" + Arrays.toString(attributes));

                            createDirectory(directory, attributes);
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        logger.log(System.Logger.Level.INFO, "visitFile: file=" + file);
                        Path path = target.resolve(source.relativize(file).toString());
                        logger.log(System.Logger.Level.INFO, "visitFile: path=" + path);
                        copy(file, path, StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
