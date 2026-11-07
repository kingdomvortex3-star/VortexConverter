
package id.vortexconverter.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import id.vortexconverter.mappings.ItemsAdderCustomMappingGenerator;
import id.vortexconverter.mappings.ItemsAdderMappingExporter;
import id.vortexconverter.mappings.ItemsAdderMappingValidator;
import id.vortexconverter.scanner.ItemsAdderConfigParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ItemsAdderBedrockConversionService {

    private static final String PACK_FILE_NAME =
            "VortexConverter-Bedrock.mcpack";

    private static final String MAPPING_FILE_NAME =
            "vortexconverter-itemsadder.json";

    private static final String ATLAS_ENTRY =
            "textures/item_texture.json";

    private static final String MANIFEST_ENTRY =
            "manifest.json";

    private static final String RENDER_CONTROLLER_ENTRY =
            "render_controllers/vortexconverter.render_controllers.json";

    private static final String RENDER_CONTROLLER_IDENTIFIER =
            "controller.render.default";

    private final ItemsAdderConfigParser parser;
    private final ItemsAdderCustomMappingGenerator mappingGenerator;
    private final ItemsAdderMappingValidator validator;
    private final ItemsAdderMappingExporter exporter;
    private final ItemsAdderBedrockResourcePackBuilder packBuilder;

    public ItemsAdderBedrockConversionService() {
        this.parser = new ItemsAdderConfigParser();
        this.mappingGenerator = new ItemsAdderCustomMappingGenerator();
        this.validator = new ItemsAdderMappingValidator();
        this.exporter = new ItemsAdderMappingExporter();
        this.packBuilder = new ItemsAdderBedrockResourcePackBuilder();
    }

    public ConversionResult convert(
            File itemsAdderContents,
            File geyserDataDirectory
    ) throws IOException {

        if (itemsAdderContents == null
                || !itemsAdderContents.isDirectory()) {
            throw new IOException(
                    "Folder ItemsAdder contents tidak ditemukan."
            );
        }

        File itemsAdderDataDirectory =
                itemsAdderContents.getParentFile();

        if (itemsAdderDataDirectory == null) {
            throw new IOException(
                    "Folder data ItemsAdder tidak valid."
            );
        }

        File generatedZip = new File(
                itemsAdderDataDirectory,
                "output/generated.zip"
        );

        if (!generatedZip.isFile()) {
            throw new IOException(
                    "ItemsAdder generated.zip tidak ditemukan: "
                            + generatedZip.getPath()
                            + ". Jalankan /iazip terlebih dahulu."
            );
        }

        if (geyserDataDirectory == null
                || (!geyserDataDirectory.isDirectory()
                && !geyserDataDirectory.mkdirs())) {
            throw new IOException(
                    "Folder data Geyser tidak dapat diakses."
            );
        }

        ItemsAdderConfigParser.ParseResult parseResult =
                parser.parse(itemsAdderContents);

        if (parseResult == null || parseResult.getFilesRead() == 0) {
            throw new IOException(
                    "Tidak ada konfigurasi ItemsAdder yang berhasil dibaca."
            );
        }

        ItemsAdderCustomMappingGenerator.MappingResult mappingResult =
                mappingGenerator.generate(parseResult);

        if (mappingResult == null || !mappingResult.hasMappings()) {
            throw new IOException(
                    "Tidak ada custom item mapping yang dapat dibuat."
            );
        }

        ItemsAdderMappingValidator.ValidationResult validation =
                validator.validate(mappingResult);

        if (!validation.isSuccessful()) {
            throw new IOException(
                    "Mapping tidak valid: "
                            + String.join(" | ", validation.errors())
            );
        }

        Path geyserPath = geyserDataDirectory
                .toPath()
                .toAbsolutePath()
                .normalize();

        Files.createDirectories(geyserPath);

        Path packsDirectory = geyserPath.resolve("packs");
        Path mappingsDirectory = geyserPath.resolve("custom_mappings");

        Files.createDirectories(packsDirectory);
        Files.createDirectories(mappingsDirectory);

        Path packTarget = packsDirectory.resolve(PACK_FILE_NAME);
        Path mappingTarget = mappingsDirectory.resolve(MAPPING_FILE_NAME);

        FileBackup packBackup = null;
        FileBackup mappingBackup = null;

        boolean rollbackFailed = false;

        try {
            packBackup = createBackup(packTarget);
            mappingBackup = createBackup(mappingTarget);

            ItemsAdderBedrockResourcePackBuilder.PackResult packResult =
                    packBuilder.build(
                            itemsAdderContents,
                            generatedZip,
                            geyserDataDirectory,
                            parseResult,
                            mappingResult
                    );

            if (packResult == null || !packResult.isSuccessful()) {
                throw new IOException(
                        "Resource pack gagal dibuat dari generated.zip."
                );
            }

            File packFile = packResult.packFile();

            if (packFile == null || !packFile.isFile()) {
                throw new IOException(
                        "File resource pack tidak ditemukan setelah build."
                );
            }

            PackValidationResult packValidation =
                    validateResourcePack(
                            packFile,
                            mappingResult.mapping()
                    );

            ItemsAdderMappingExporter.ExportResult exportResult =
                    exporter.export(
                            mappingResult,
                            geyserDataDirectory
                    );

            if (exportResult == null || !exportResult.isSuccessful()) {
                throw new IOException(
                        "Gagal mengekspor custom mappings ke Geyser."
                );
            }

            File mappingFile = exportResult.mappingFile();

            if (mappingFile == null || !mappingFile.isFile()) {
                throw new IOException(
                        "File custom mappings tidak ditemukan setelah export."
                );
            }

            validateExportedMapping(mappingFile);

            List<String> warnings = new ArrayList<>(
                    mappingResult.warnings()
            );

            warnings.addAll(validation.warnings());
            warnings.addAll(packResult.warnings());

            warnings.add(
                    "Sumber metadata: ItemsAdder/contents."
            );

            warnings.add(
                    "Sumber model dan tekstur: "
                            + generatedZip.getPath()
            );

            warnings.add(
                    "Resource pack berhasil melewati pemeriksaan ZIP."
            );

            warnings.add(
                    "Manifest dan texture atlas berhasil diperiksa."
            );

            warnings.add(
                    "PNG yang dirujuk atlas: "
                            + packValidation.checkedTextures()
            );

            warnings.add(
                    "Geometry JSON diperiksa: "
                            + packValidation.checkedGeometries()
            );

            warnings.add(
                    "Attachable JSON diperiksa: "
                            + packValidation.checkedAttachables()
            );

            if (packValidation.renderControllerFound()) {
                warnings.add(
                        "Render Controller ditemukan dan struktur dasarnya valid."
                );
            } else {
                warnings.add(
                        "Tidak ada attachable yang memerlukan Render Controller."
                );
            }

            warnings.add(
                    "Semua icon mapping telah diperiksa terhadap texture atlas."
            );

            warnings.add(
                    "Mapping berhasil diekspor setelah pemeriksaan resource pack."
            );

            warnings.add(
                    "File output berada di Geyser packs dan custom_mappings."
            );

            warnings.add(
                    "Validasi struktur JSON tidak menjamin seluruh model "
                            + "akan tampil sempurna di client Bedrock."
            );

            return new ConversionResult(
                    true,
                    parseResult.getFilesRead(),
                    parseResult.getAssets().size(),
                    packResult.discoveredItems(),
                    packResult.copiedTextures(),
                    packResult.skippedItems(),
                    warnings,
                    packResult.skippedDetails(),
                    packFile,
                    mappingFile
            );

        } catch (IOException | RuntimeException exception) {

            IOException rollbackError = null;

            try {
                if (mappingBackup != null) {
                    restoreBackup(mappingBackup);
                }
            } catch (IOException restoreException) {
                rollbackError = restoreException;
            }

            try {
                if (packBackup != null) {
                    restoreBackup(packBackup);
                }
            } catch (IOException restoreException) {
                if (rollbackError == null) {
                    rollbackError = restoreException;
                } else {
                    rollbackError.addSuppressed(restoreException);
                }
            }

            if (rollbackError != null) {
                rollbackFailed = true;
                exception.addSuppressed(rollbackError);

                exception.addSuppressed(
                        new IOException(
                                "Backup dipertahankan untuk pemulihan manual. "
                                        + "Mapping backup: "
                                        + backupPath(mappingBackup)
                                        + "; pack backup: "
                                        + backupPath(packBackup)
                        )
                );
            }

            throw exception;

        } finally {
            if (!rollbackFailed) {
                deleteBackup(packBackup);
                deleteBackup(mappingBackup);
            }
        }
    }

    private PackValidationResult validateResourcePack(
            File packFile,
            JsonObject mapping
    ) throws IOException {

        if (packFile == null || !packFile.isFile()) {
            throw new IOException(
                    "Resource pack tidak ditemukan."
            );
        }

        try (ZipFile zip = new ZipFile(packFile)) {

            Set<String> entries = new HashSet<>();

            var enumeration = zip.entries();

            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();

                if (!entry.isDirectory()) {
                    entries.add(entry.getName());
                }
            }

            JsonObject manifest = readJsonObject(
                    zip,
                    MANIFEST_ENTRY
            );

            if (!manifest.has("header")
                    || !manifest.get("header").isJsonObject()
                    || !manifest.has("modules")
                    || !manifest.get("modules").isJsonArray()
                    || manifest.getAsJsonArray("modules").isEmpty()) {
                throw new IOException(
                        "Struktur manifest.json tidak lengkap."
                );
            }

            JsonObject header = manifest.getAsJsonObject("header");

            if (!header.has("uuid")
                    || !header.has("version")
                    || !header.has("min_engine_version")) {
                throw new IOException(
                        "Header manifest tidak lengkap."
                );
            }

            JsonObject atlas = readJsonObject(
                    zip,
                    ATLAS_ENTRY
            );

            if (!atlas.has("texture_data")
                    || !atlas.get("texture_data").isJsonObject()) {
                throw new IOException(
                        "item_texture.json tidak memiliki texture_data."
                );
            }

            JsonObject textureData =
                    atlas.getAsJsonObject("texture_data");

            int checkedTextures = validateAtlasTextures(
                    textureData,
                    entries
            );

            int checkedIcons = validateMappingIcons(
                    mapping,
                    textureData
            );

            int checkedGeometries = validateGeometryFiles(
                    zip,
                    entries
            );

            int checkedAttachables = validateAttachables(
                    zip,
                    entries
            );

            boolean renderControllerFound =
                    entries.contains(RENDER_CONTROLLER_ENTRY);

            if (checkedAttachables > 0 && !renderControllerFound) {
                throw new IOException(
                        "Attachable ditemukan tetapi Render Controller "
                                + "tidak ada di resource pack."
                );
            }

            if (renderControllerFound) {
                validateRenderController(zip);

                if (checkedAttachables == 0) {
                    throw new IOException(
                            "Render Controller tersedia tetapi tidak ada "
                                    + "attachable yang menggunakannya."
                    );
                }
            }

            return new PackValidationResult(
                    checkedTextures,
                    checkedIcons,
                    checkedGeometries,
                    checkedAttachables,
                    renderControllerFound
            );
        }
    }

    private JsonObject readJsonObject(
            ZipFile zip,
            String entryName
    ) throws IOException {

        ZipEntry entry = zip.getEntry(entryName);

        if (entry == null || entry.isDirectory()) {
            throw new IOException(
                    "File resource pack tidak ditemukan: " + entryName
            );
        }

        try (InputStream input = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(
                     input,
                     StandardCharsets.UTF_8
             )) {

            JsonElement element;

            try {
                element = JsonParser.parseReader(reader);
            } catch (RuntimeException exception) {
                throw new IOException(
                        "JSON tidak valid: " + entryName,
                        exception
                );
            }

            if (element == null || !element.isJsonObject()) {
                throw new IOException(
                        "JSON bukan object: " + entryName
                );
            }

            return element.getAsJsonObject();
        }
    }

    private int validateAtlasTextures(
            JsonObject textureData,
            Set<String> entries
    ) throws IOException {

        int checked = 0;

        for (Map.Entry<String, JsonElement> entry
                : textureData.entrySet()) {

            if (!entry.getValue().isJsonObject()) {
                throw new IOException(
                        "Texture atlas entry bukan object: "
                                + entry.getKey()
                );
            }

            JsonObject textureEntry = entry.getValue().getAsJsonObject();

            if (!textureEntry.has("textures")) {
                throw new IOException(
                        "Texture atlas tidak memiliki textures: "
                                + entry.getKey()
                );
            }

            JsonElement texturesElement = textureEntry.get("textures");

            if (!texturesElement.isJsonArray()) {
                throw new IOException(
                        "Texture atlas textures harus berupa array: "
                                + entry.getKey()
                );
            }

            JsonArray textures = texturesElement.getAsJsonArray();

            if (textures.isEmpty()) {
                throw new IOException(
                        "Texture atlas textures kosong: "
                                + entry.getKey()
                );
            }

            for (JsonElement textureElement : textures) {

                if (!textureElement.isJsonPrimitive()
                        || !textureElement.getAsJsonPrimitive().isString()) {
                    throw new IOException(
                            "Path texture tidak valid: " + entry.getKey()
                    );
                }

                String texturePath = textureElement.getAsString();

                String normalizedPath = normalizePackPath(texturePath);

                if (!entries.contains(normalizedPath + ".png")) {
                    throw new IOException(
                            "File PNG tidak ditemukan di pack: "
                                    + normalizedPath + ".png"
                    );
                }

                checked++;
            }
        }

        if (checked == 0) {
            throw new IOException(
                    "Tidak ada file texture yang berhasil diperiksa."
            );
        }

        return checked;
    }

    private int validateMappingIcons(
            JsonObject mapping,
            JsonObject textureData
    ) throws IOException {

        if (mapping == null
                || !mapping.has("items")
                || !mapping.get("items").isJsonObject()) {
            throw new IOException(
                    "Mapping tidak memiliki objek items."
            );
        }

        JsonObject items = mapping.getAsJsonObject("items");

        int checked = 0;

        for (Map.Entry<String, JsonElement> itemEntry
                : items.entrySet()) {

            if (!itemEntry.getValue().isJsonArray()) {
                throw new IOException(
                        "Mapping definitions bukan array: "
                                + itemEntry.getKey()
                );
            }

            for (JsonElement definitionElement
                    : itemEntry.getValue().getAsJsonArray()) {

                if (!definitionElement.isJsonObject()) {
                    throw new IOException(
                            "Mapping definition bukan object: "
                                    + itemEntry.getKey()
                    );
                }

                JsonObject definition =
                        definitionElement.getAsJsonObject();

                if (!definition.has("bedrock_options")
                        || !definition.get("bedrock_options").isJsonObject()) {
                    throw new IOException(
                            "bedrock_options tidak ditemukan: "
                                    + itemEntry.getKey()
                    );
                }

                JsonObject options =
                        definition.getAsJsonObject("bedrock_options");

                if (!options.has("icon")
                        || !options.get("icon").isJsonPrimitive()
                        || !options.get("icon").getAsJsonPrimitive().isString()) {
                    throw new IOException(
                            "Icon mapping tidak valid: "
                                    + itemEntry.getKey()
                    );
                }

                String icon = options.get("icon").getAsString();

                if (!textureData.has(icon)) {
                    throw new IOException(
                            "Tekstur atlas untuk icon '"
                                    + icon
                                    + "' tidak ditemukan. Java item: "
                                    + itemEntry.getKey()
                    );
                }

                checked++;
            }
        }

        if (checked == 0) {
            throw new IOException(
                    "Tidak ada icon mapping yang berhasil diperiksa."
            );
        }

        return checked;
    }

    private int validateGeometryFiles(
            ZipFile zip,
            Set<String> entries
    ) throws IOException {

        int checked = 0;

        for (String entryName : entries) {

            if (!entryName.startsWith("models/entity/")
                    || !entryName.endsWith(".geo.json")) {
                continue;
            }

            JsonObject root = readJsonObject(zip, entryName);

            if (!root.has("minecraft:geometry")
                    || !root.get("minecraft:geometry").isJsonArray()) {
                throw new IOException(
                        "Geometry JSON tidak memiliki minecraft:geometry: "
                                + entryName
                );
            }

            JsonArray geometries =
                    root.getAsJsonArray("minecraft:geometry");

            if (geometries.isEmpty()) {
                throw new IOException(
                        "Geometry array kosong: " + entryName
                );
            }

            for (JsonElement geometryElement : geometries) {

                if (!geometryElement.isJsonObject()) {
                    throw new IOException(
                            "Geometry entry bukan object: " + entryName
                    );
                }

                JsonObject geometry = geometryElement.getAsJsonObject();

                if (!geometry.has("description")
                        || !geometry.get("description").isJsonObject()
                        || !geometry.has("bones")
                        || !geometry.get("bones").isJsonArray()) {
                    throw new IOException(
                            "Struktur geometry tidak lengkap: " + entryName
                    );
                }

                JsonObject description =
                        geometry.getAsJsonObject("description");

                if (!description.has("identifier")
                        || !description.has("texture_width")
                        || !description.has("texture_height")) {
                    throw new IOException(
                            "Geometry description tidak lengkap: "
                                    + entryName
                    );
                }

                JsonArray bones = geometry.getAsJsonArray("bones");

                if (bones.isEmpty()) {
                    throw new IOException(
                            "Geometry tidak memiliki bones: " + entryName
                    );
                }

                for (JsonElement boneElement : bones) {

                    if (!boneElement.isJsonObject()) {
                        throw new IOException(
                                "Bone bukan object: " + entryName
                        );
                    }

                    JsonObject bone = boneElement.getAsJsonObject();

                    if (!bone.has("name")
                            || !bone.has("cubes")
                            || !bone.get("cubes").isJsonArray()) {
                        throw new IOException(
                                "Bone tidak memiliki name/cubes: "
                                        + entryName
                        );
                    }

                    if (bone.getAsJsonArray("cubes").isEmpty()) {
                        throw new IOException(
                                "Bone tidak memiliki cube: " + entryName
                        );
                    }
                }
            }

            checked++;
        }

        return checked;
    }

    private int validateAttachables(
            ZipFile zip,
            Set<String> entries
    ) throws IOException {

        int checked = 0;

        for (String entryName : entries) {

            if (!entryName.startsWith("attachables/")
                    || !entryName.endsWith(".json")) {
                continue;
            }

            JsonObject root = readJsonObject(zip, entryName);

            if (!root.has("minecraft:attachable")
                    || !root.get("minecraft:attachable").isJsonObject()) {
                throw new IOException(
                        "Attachable JSON tidak memiliki minecraft:attachable: "
                                + entryName
                );
            }

            JsonObject attachable =
                    root.getAsJsonObject("minecraft:attachable");

            if (!attachable.has("description")
                    || !attachable.get("description").isJsonObject()) {
                throw new IOException(
                        "Attachable tidak memiliki description: "
                                + entryName
                );
            }

            JsonObject description =
                    attachable.getAsJsonObject("description");

            if (!description.has("identifier")
                    || !description.has("geometry")
                    || !description.get("geometry").isJsonObject()
                    || !description.has("textures")
                    || !description.get("textures").isJsonObject()
                    || !description.has("materials")
                    || !description.get("materials").isJsonObject()
                    || !description.has("render_controllers")
                    || !description.get("render_controllers").isJsonArray()) {
                throw new IOException(
                        "Struktur attachable tidak lengkap: " + entryName
                );
            }

            JsonObject geometries =
                    description.getAsJsonObject("geometry");

            JsonObject textures =
                    description.getAsJsonObject("textures");

            JsonArray controllers =
                    description.getAsJsonArray("render_controllers");

            if (geometries.isEmpty() || textures.isEmpty()
                    || controllers.isEmpty()) {
                throw new IOException(
                        "Attachable memiliki referensi kosong: " + entryName
                );
            }

            for (Map.Entry<String, JsonElement> geometryEntry
                    : geometries.entrySet()) {

                if (!geometryEntry.getValue().isJsonPrimitive()) {
                    throw new IOException(
                            "Geometry reference tidak valid: " + entryName
                    );
                }

                String geometryIdentifier =
                        geometryEntry.getValue().getAsString();

                if (!containsGeometryIdentifier(
                        zip,
                        entries,
                        geometryIdentifier
                )) {
                    throw new IOException(
                            "Geometry tidak ditemukan untuk attachable "
                                    + entryName + ": "
                                    + geometryIdentifier
                    );
                }
            }

            for (Map.Entry<String, JsonElement> textureEntry
                    : textures.entrySet()) {

                if (!textureEntry.getValue().isJsonPrimitive()) {
                    throw new IOException(
                            "Texture reference tidak valid: " + entryName
                    );
                }

                String texturePath = normalizePackPath(
                        textureEntry.getValue().getAsString()
                );

                if (!entries.contains(texturePath + ".png")) {
                    throw new IOException(
                            "Texture attachable tidak ditemukan: "
                                    + texturePath + ".png"
                    );
                }
            }

            boolean controllerMatched = false;

            for (JsonElement controllerElement : controllers) {

                if (!controllerElement.isJsonPrimitive()
                        || !controllerElement.getAsJsonPrimitive().isString()) {
                    throw new IOException(
                            "Render controller reference tidak valid: "
                                    + entryName
                    );
                }

                if (RENDER_CONTROLLER_IDENTIFIER.equals(
                        controllerElement.getAsString()
                )) {
                    controllerMatched = true;
                }
            }

            if (!controllerMatched) {
                throw new IOException(
                        "Attachable tidak menggunakan controller yang "
                                + "dibuat VortexConverter: " + entryName
                );
            }

            checked++;
        }

        return checked;
    }

    private boolean containsGeometryIdentifier(
            ZipFile zip,
            Set<String> entries,
            String identifier
    ) throws IOException {

        for (String entryName : entries) {

            if (!entryName.startsWith("models/entity/")
                    || !entryName.endsWith(".geo.json")) {
                continue;
            }

            JsonObject root = readJsonObject(zip, entryName);

            JsonArray geometries =
                    root.getAsJsonArray("minecraft:geometry");

            if (geometries == null) {
                continue;
            }

            for (JsonElement geometryElement : geometries) {

                if (!geometryElement.isJsonObject()) {
                    continue;
                }

                JsonObject geometry = geometryElement.getAsJsonObject();

                if (!geometry.has("description")
                        || !geometry.get("description").isJsonObject()) {
                    continue;
                }

                JsonObject description =
                        geometry.getAsJsonObject("description");

                if (description.has("identifier")
                        && identifier.equals(
                                description.get("identifier").getAsString()
                        )) {
                    return true;
                }
            }
        }

        return false;
    }

    private void validateRenderController(
            ZipFile zip
    ) throws IOException {

        JsonObject root = readJsonObject(
                zip,
                RENDER_CONTROLLER_ENTRY
        );

        if (!root.has("render_controllers")
                || !root.get("render_controllers").isJsonObject()) {
            throw new IOException(
                    "Render Controller tidak memiliki render_controllers."
            );
        }

        JsonObject controllers =
                root.getAsJsonObject("render_controllers");

        if (!controllers.has(RENDER_CONTROLLER_IDENTIFIER)
                || !controllers.get(RENDER_CONTROLLER_IDENTIFIER).isJsonObject()) {
            throw new IOException(
                    "Controller " + RENDER_CONTROLLER_IDENTIFIER
                            + " tidak ditemukan."
            );
        }

        JsonObject controller =
                controllers.getAsJsonObject(RENDER_CONTROLLER_IDENTIFIER);

        if (!controller.has("geometry")
                || !controller.get("geometry").isJsonPrimitive()
                || !controller.has("materials")
                || !controller.get("materials").isJsonArray()
                || !controller.has("textures")
                || !controller.get("textures").isJsonArray()) {
            throw new IOException(
                    "Struktur Render Controller tidak lengkap."
            );
        }

        if (controller.getAsJsonArray("materials").isEmpty()
                || controller.getAsJsonArray("textures").isEmpty()) {
            throw new IOException(
                    "Render Controller memiliki materials/textures kosong."
            );
        }

        for (JsonElement materialElement
                : controller.getAsJsonArray("materials")) {

            if (!materialElement.isJsonObject()
                    || materialElement.getAsJsonObject().isEmpty()) {
                throw new IOException(
                        "Render Controller materials entry tidak valid."
                );
            }
        }
    }

    private String normalizePackPath(String path) throws IOException {

        if (path == null || path.isBlank()) {
            throw new IOException(
                    "Path resource pack kosong."
            );
        }

        String normalized = path.replace('\\', '/');

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        if (normalized.contains("../")
                || normalized.equals("..")
                || normalized.contains(":")) {
            throw new IOException(
                    "Path resource pack tidak aman: " + path
            );
        }

        return normalized;
    }

    private void validateExportedMapping(File mappingFile)
            throws IOException {

        JsonElement element;

        try {
            element = JsonParser.parseString(
                    Files.readString(
                            mappingFile.toPath(),
                            StandardCharsets.UTF_8
                    )
            );
        } catch (RuntimeException exception) {
            throw new IOException(
                    "JSON mapping hasil ekspor tidak valid.",
                    exception
            );
        }

        if (!element.isJsonObject()) {
            throw new IOException(
                    "File hasil ekspor mapping bukan JSON object."
            );
        }

        JsonObject mapping = element.getAsJsonObject();

        ItemsAdderMappingValidator.ValidationResult result =
                validator.validate(mapping);

        if (!result.isSuccessful()) {
            throw new IOException(
                    "Mapping hasil ekspor tidak valid: "
                            + String.join(" | ", result.errors())
            );
        }
    }

    private FileBackup createBackup(Path target)
            throws IOException {

        boolean existed = Files.isRegularFile(target);

        if (!existed) {
            return new FileBackup(target, null, false);
        }

        Path backup = Files.createTempFile(
                target.getParent(),
                "vortexconverter-backup-",
                ".tmp"
        );

        try {
            Files.copy(
                    target,
                    backup,
                    StandardCopyOption.REPLACE_EXISTING
            );

            return new FileBackup(target, backup, true);

        } catch (IOException exception) {
            Files.deleteIfExists(backup);
            throw exception;
        }
    }

    private void restoreBackup(FileBackup backup)
            throws IOException {

        if (backup == null) {
            return;
        }

        if (backup.existed()) {

            if (backup.backup() == null
                    || !Files.isRegularFile(backup.backup())) {
                throw new IOException(
                        "File backup tidak ditemukan untuk "
                                + backup.target()
                );
            }

            Files.copy(
                    backup.backup(),
                    backup.target(),
                    StandardCopyOption.REPLACE_EXISTING
            );

        } else {
            Files.deleteIfExists(backup.target());
        }
    }

    private String backupPath(FileBackup backup) {

        if (backup == null || backup.backup() == null) {
            return "tidak tersedia";
        }

        return backup.backup().toString();
    }

    private void deleteBackup(FileBackup backup) {

        if (backup == null || backup.backup() == null) {
            return;
        }

        try {
            Files.deleteIfExists(backup.backup());
        } catch (IOException ignored) {
            // Backup sementara dapat dibersihkan secara manual.
        }
    }

    private record FileBackup(
            Path target,
            Path backup,
            boolean existed
    ) {
    }

    private record PackValidationResult(
            int checkedTextures,
            int checkedIcons,
            int checkedGeometries,
            int checkedAttachables,
            boolean renderControllerFound
    ) {
    }

    public record ConversionResult(
            boolean successful,
            int filesRead,
            int totalAssets,
            int discoveredItems,
            int convertedTextures,
            int skippedItems,
            List<String> warnings,
            List<String> skippedDetails,
            File packFile,
            File mappingFile
    ) {

        public boolean isSuccessful() {
            return successful
                    && packFile != null
                    && packFile.isFile()
                    && mappingFile != null
                    && mappingFile.isFile();
        }
    }
}
