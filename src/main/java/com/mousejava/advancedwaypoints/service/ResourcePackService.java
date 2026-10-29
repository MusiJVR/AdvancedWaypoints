package com.mousejava.advancedwaypoints.service;

import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ResourcePackService {
    private final JavaPlugin plugin;
    private final String namespace;
    private final Path resourcePackDirectory;
    private final Path assetsDirectory;
    private final Path buildDirectory;
    private final Path resourcePackFile;

    public ResourcePackService(JavaPlugin plugin, String namespace) {
        this.plugin = plugin;
        this.namespace = namespace;
        this.resourcePackDirectory = plugin.getDataFolder().toPath().resolve("resourcepack");
        this.assetsDirectory = resourcePackDirectory.resolve("assets");
        this.buildDirectory = resourcePackDirectory.resolve("build");
        this.resourcePackFile = buildDirectory.resolve("resourcepack.zip");
    }

    public void generateDefaultAssets() throws IOException {
        Path defaultAssetsDirectory = resourcePackDirectory.resolve("assets");
        Files.createDirectories(defaultAssetsDirectory);
        copyDefaultAssets("resourcepack/assets", defaultAssetsDirectory);
    }

    private void copyDefaultAssets(String resourcePath, Path targetDirectory) throws IOException {
        URL resource = plugin.getClass().getClassLoader().getResource(resourcePath);
        if (resource == null) {
            plugin.getLogger().warning("Default resource pack assets not found: " + resourcePath);
            return;
        }

        if (!resource.getProtocol().equals("jar"))
            throw new IOException("Default resource pack assets must be loaded from JAR, " + "but protocol is: " + resource.getProtocol());

        try (FileSystem fileSystem = FileSystems.newFileSystem(resource.toURI(), Collections.emptyMap())) {
            Path sourceDirectory = fileSystem.getPath("/" + resourcePath);

            try (Stream<Path> stream = Files.walk(sourceDirectory)) {
                stream.forEach(source -> {
                    try {
                        Path relativePath = sourceDirectory.relativize(source);
                        Path target = targetDirectory.resolve(relativePath.toString());

                        if (Files.isDirectory(source)) {
                            Files.createDirectories(target);
                            return;
                        }

                        if (Files.exists(target))
                            return;

                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
        } catch (URISyntaxException exception) {
            throw new IOException("Failed to access default resource pack assets: " + resourcePath, exception);
        }
    }

    public void buildResourcePack() throws IOException {
        createDirectories();
        deletePreviousResourcePack();

        List<Path> styles = findStyles();
        try (OutputStream outputStream = Files.newOutputStream(resourcePackFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            writePackFile(zip, "resourcepack/pack.png", "pack.png");
            writePackMcmeta(zip);
            writeTextures(zip, styles);
            writeWaypointStyles(zip, styles);
        }

        plugin.getLogger().info("Resource pack generated: " + resourcePackFile.toAbsolutePath());
    }

    private void createDirectories() throws IOException {
        Files.createDirectories(assetsDirectory);
        Files.createDirectories(buildDirectory);
    }

    private void deletePreviousResourcePack() throws IOException {
        Files.deleteIfExists(resourcePackFile);
    }

    private List<Path> findStyles() throws IOException {
        try (Stream<Path> stream = Files.list(assetsDirectory)) {
            return stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private void writePackFile(ZipOutputStream zip, String resourcePath, String zipPath) throws IOException {
        try (InputStream inputStream = plugin.getClass()
                .getClassLoader()
                .getResourceAsStream(resourcePath)) {

            if (inputStream == null)
                throw new IOException("Resource not found in plugin JAR: " + resourcePath);

            ZipEntry entry = new ZipEntry(zipPath);
            zip.putNextEntry(entry);
            inputStream.transferTo(zip);
            zip.closeEntry();
        }
    }

    private void writePackMcmeta(ZipOutputStream zip) throws IOException {
        try (InputStream inputStream = plugin.getClass()
                .getClassLoader()
                .getResourceAsStream("resourcepack/pack.mcmeta")) {

            if (inputStream == null)
                throw new IOException("Resource not found in plugin JAR: resourcepack/pack.mcmeta");

            String json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            PackFormat packFormat = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);

            json = json
                    .replace("<major>", Integer.toString(packFormat.major()))
                    .replace("<minor>", Integer.toString(packFormat.minor()));

            writeString(zip, "pack.mcmeta", json);
        }
    }

    private void writeTextures(ZipOutputStream zip, List<Path> styles) throws IOException {
        for (Path styleDirectory : styles) {
            String styleName = styleDirectory.getFileName().toString();
            validateResourceName(styleName);
            List<Path> images = findImages(styleDirectory, styleName);
            for (Path image : images) {
                String fileName = image.getFileName().toString();
                String zipPath = "assets/" + namespace + "/textures/gui/sprites/hud/locator_bar_dot/" + styleName + "/" + fileName;
                writeFile(zip, zipPath, image);
            }
        }
    }

    private void writeWaypointStyles(ZipOutputStream zip, List<Path> styles) throws IOException {
        for (Path styleDirectory : styles) {
            String styleName = styleDirectory.getFileName().toString();
            List<Path> images = findImages(styleDirectory, styleName);
            if (images.isEmpty()) {
                plugin.getLogger().warning("Skipping empty waypoint style: " + styleName);
                continue;
            }

            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"sprites\": [\n");
            for (int i = 0; i < images.size(); i++) {
                Path image = images.get(i);
                String fileName = image.getFileName().toString();
                String spriteName = fileName.substring(0, fileName.length() - ".png".length());

                json.append("    \"")
                        .append(namespace)
                        .append(":")
                        .append(styleName)
                        .append("/")
                        .append(spriteName)
                        .append("\"");

                if (i < images.size() - 1)
                    json.append(",");

                json.append("\n");
            }

            json.append("  ]\n");
            json.append("}\n");

            String zipPath = "assets/" + namespace + "/waypoint_style/" + styleName + ".json";
            writeString(zip, zipPath, json.toString());
        }
    }

    private List<Path> findImages(Path styleDirectory, String styleName) throws IOException {
        List<Path> images;
        try (Stream<Path> stream = Files.list(styleDirectory)) {
            images = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName()
                                .toString()
                                .toLowerCase(Locale.ROOT);

                        return name.endsWith(".png");
                    })
                    .sorted(Comparator.comparingInt(path -> getSpriteIndex(path, styleName)))
                    .toList();
        }

        return images;
    }

    private int getSpriteIndex(Path image, String styleName) {
        String fileName = image.getFileName().toString();
        String prefix = styleName + "_";
        String lowerName = fileName.toLowerCase(Locale.ROOT);

        if (!lowerName.startsWith(prefix.toLowerCase(Locale.ROOT)) || !lowerName.endsWith(".png"))
            throw new IllegalArgumentException("Invalid sprite name '" + fileName + "'. Expected format: " + styleName + "_0.png");

        String number = fileName.substring(prefix.length(), fileName.length() - ".png".length());

        try {
            return Integer.parseInt(number);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid sprite number in '" + fileName + "'. Expected format: " + styleName + "_0.png", exception);
        }
    }

    private void validateResourceName(String name) {
        if (!name.matches("[a-z0-9._-]+"))
            throw new IllegalArgumentException("Invalid resource name '" + name + "'. Only lowercase letters, numbers, '.', '_' and '-' are allowed.");
    }

    private void writeString(ZipOutputStream zip, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void writeFile(ZipOutputStream zip, String zipPath, Path source) throws IOException {
        ZipEntry entry = new ZipEntry(zipPath);
        zip.putNextEntry(entry);
        Files.copy(source, zip);
        zip.closeEntry();
    }
}
