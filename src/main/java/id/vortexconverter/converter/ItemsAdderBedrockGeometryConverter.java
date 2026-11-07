
package id.vortexconverter.converter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ItemsAdderBedrockGeometryConverter {

    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private static final double COORDINATE_OFFSET = 8.0;
    private static final double UV_REFERENCE_SIZE = 16.0;
    private static final double EPSILON = 0.000001;

    private static final int MAX_ELEMENTS = 4096;

    public ConversionResult convert(
            ItemsAdderModelParser.ModelData model,
            String geometryIdentifier,
            int textureWidth,
            int textureHeight
    ) {

        List<String> warnings = new ArrayList<>();

        if (model == null) {
            return ConversionResult.failure(
                    "Data model tidak tersedia."
            );
        }

        if (geometryIdentifier == null
                || geometryIdentifier.isBlank()) {
            return ConversionResult.failure(
                    "Geometry identifier tidak boleh kosong."
            );
        }

        if (textureWidth <= 0 || textureHeight <= 0) {
            return ConversionResult.failure(
                    "Ukuran texture harus lebih besar dari nol."
            );
        }

        if (!model.hasElements()) {
            return ConversionResult.failure(
                    "Model tidak memiliki elements."
            );
        }

        if (model.elements().size() > MAX_ELEMENTS) {
            return ConversionResult.failure(
                    "Jumlah element melebihi batas aman: "
                            + MAX_ELEMENTS
            );
        }

        JsonObject geometry = new JsonObject();
        geometry.addProperty("format_version", "1.12.0");

        JsonObject geometryObject = new JsonObject();
        JsonObject description = new JsonObject();

        description.addProperty(
                "identifier",
                normalizeIdentifier(geometryIdentifier)
        );

        description.addProperty("texture_width", textureWidth);
        description.addProperty("texture_height", textureHeight);
        description.addProperty("visible_bounds_width", 4.0);
        description.addProperty("visible_bounds_height", 4.0);

        description.add(
                "visible_bounds_offset",
                vector(0.0, 1.0, 0.0)
        );

        geometryObject.add("description", description);

        JsonArray bones = new JsonArray();

        int convertedElements = 0;
        int convertedFaces = 0;
        int skippedFaces = 0;
        int unsupportedRotations = 0;
        int unsupportedUvRotations = 0;

        double uvScaleX = textureWidth / UV_REFERENCE_SIZE;
        double uvScaleY = textureHeight / UV_REFERENCE_SIZE;

        for (ItemsAdderModelParser.ElementData element
                : model.elements()) {

            if (element == null
                    || element.from() == null
                    || element.to() == null
                    || element.from().size() != 3
                    || element.to().size() != 3) {

                warnings.add(
                        "Element dilewati karena koordinat tidak valid."
                );
                continue;
            }

            double fromX = element.from().get(0);
            double fromY = element.from().get(1);
            double fromZ = element.from().get(2);

            double toX = element.to().get(0);
            double toY = element.to().get(1);
            double toZ = element.to().get(2);

            if (!isFinite(
                    fromX, fromY, fromZ,
                    toX, toY, toZ
            )) {
                warnings.add(
                        "Element " + element.index()
                                + " dilewati karena koordinat tidak finite."
                );
                continue;
            }

            double sizeX = toX - fromX;
            double sizeY = toY - fromY;
            double sizeZ = toZ - fromZ;

            if (sizeX <= EPSILON
                    || sizeY <= EPSILON
                    || sizeZ <= EPSILON) {

                warnings.add(
                        "Element " + element.index()
                                + " dilewati karena ukuran cube tidak valid."
                );
                continue;
            }

            JsonObject cube = new JsonObject();

            cube.add(
                    "origin",
                    vector(
                            fromX - COORDINATE_OFFSET,
                            COORDINATE_OFFSET - toY,
                            fromZ - COORDINATE_OFFSET
                    )
            );

            cube.add(
                    "size",
                    vector(sizeX, sizeY, sizeZ)
            );

            JsonObject faceUv = new JsonObject();

            if (element.faces() != null) {

                for (ItemsAdderModelParser.FaceData face
                        : element.faces()) {

                    if (face == null || !face.hasExplicitUv()) {
                        skippedFaces++;
                        continue;
                    }

                    String direction =
                            convertDirection(face.direction());

                    if (direction == null) {
                        skippedFaces++;

                        warnings.add(
                                "Face element " + element.index()
                                        + " dilewati karena arah tidak dikenal."
                        );
                        continue;
                    }

                    double u1 = face.getU1();
                    double v1 = face.getV1();
                    double u2 = face.getU2();
                    double v2 = face.getV2();

                    if (!isFinite(u1, v1, u2, v2)) {
                        skippedFaces++;

                        warnings.add(
                                "Face " + direction
                                        + " element " + element.index()
                                        + " dilewati karena UV tidak valid."
                        );
                        continue;
                    }

                    double scaledU1 = u1 * uvScaleX;
                    double scaledV1 = v1 * uvScaleY;
                    double scaledU2 = u2 * uvScaleX;
                    double scaledV2 = v2 * uvScaleY;

                    double uvWidth = scaledU2 - scaledU1;
                    double uvHeight = scaledV2 - scaledV1;

                    if (Math.abs(uvWidth) < EPSILON
                            || Math.abs(uvHeight) < EPSILON) {

                        skippedFaces++;
                        continue;
                    }

                    JsonObject uv = new JsonObject();

                    uv.add(
                            "uv",
                            vector(scaledU1, scaledV1)
                    );

                    uv.add(
                            "uv_size",
                            vector(uvWidth, uvHeight)
                    );

                    faceUv.add(direction, uv);

                    if (face.rotation() != 0) {
                        unsupportedUvRotations++;

                        warnings.add(
                                "Rotasi UV " + face.rotation()
                                        + " derajat pada face "
                                        + direction
                                        + " element " + element.index()
                                        + " belum didukung."
                        );
                    }

                    convertedFaces++;
                }
            }

            if (faceUv.isEmpty()) {
                warnings.add(
                        "Element " + element.index()
                                + " dilewati karena tidak memiliki UV valid."
                );
                continue;
            }

            cube.add("uv", faceUv);

            JsonObject bone = new JsonObject();

            bone.addProperty(
                    "name",
                    "vortex_element_" + element.index()
            );

            ItemsAdderModelParser.ElementRotation rotation =
                    element.rotation();

            if (rotation != null) {

                if (rotation.origin() == null
                        || rotation.origin().size() != 3) {

                    unsupportedRotations++;

                    warnings.add(
                            "Rotasi element " + element.index()
                                    + " dilewati karena pivot tidak valid."
                    );

                } else if (!isFinite(
                        rotation.origin().get(0),
                        rotation.origin().get(1),
                        rotation.origin().get(2),
                        rotation.angle()
                )) {

                    unsupportedRotations++;

                    warnings.add(
                            "Rotasi element " + element.index()
                                    + " dilewati karena nilai tidak valid."
                    );

                } else {

                    JsonArray pivot = convertPivot(
                            rotation.origin()
                    );

                    JsonArray rotationArray =
                            convertRotation(rotation);

                    if (pivot != null && rotationArray != null) {
                        bone.add("pivot", pivot);
                        bone.add("rotation", rotationArray);
                    } else {
                        unsupportedRotations++;

                        warnings.add(
                                "Rotasi element " + element.index()
                                        + " tidak didukung."
                        );
                    }
                }
            }

            JsonArray cubes = new JsonArray();
            cubes.add(cube);

            bone.add("cubes", cubes);
            bones.add(bone);

            convertedElements++;
        }

        if (convertedElements == 0) {
            return new ConversionResult(
                    false,
                    null,
                    0,
                    convertedFaces,
                    skippedFaces,
                    warnings,
                    "Tidak ada element yang berhasil dikonversi."
            );
        }

        geometryObject.add("bones", bones);

        JsonArray geometries = new JsonArray();
        geometries.add(geometryObject);

        geometry.add("minecraft:geometry", geometries);

        if (skippedFaces > 0) {
            warnings.add(
                    skippedFaces
                            + " face dilewati karena UV tidak tersedia "
                            + "atau tidak valid."
            );
        }

        if (unsupportedRotations > 0) {
            warnings.add(
                    unsupportedRotations
                            + " rotasi element tidak berhasil diterapkan."
            );
        }

        if (unsupportedUvRotations > 0) {
            warnings.add(
                    unsupportedUvRotations
                            + " rotasi UV face belum diterapkan."
            );
        }

        warnings.add(
                "Koordinat model Java telah disesuaikan ke sumbu Bedrock."
        );

        warnings.add(
                "UV telah diskalakan berdasarkan ukuran texture."
        );

        warnings.add(
                "Arah face up/down dipertahankan sesuai orientasi model."
        );

        warnings.add(
                "Model dengan elemen berotasi perlu diuji di client Bedrock."
        );

        return new ConversionResult(
                true,
                geometry,
                convertedElements,
                convertedFaces,
                skippedFaces,
                warnings,
                null
        );
    }

    public void write(
            ConversionResult result,
            File destination
    ) throws IOException {

        if (result == null
                || !result.successful()
                || result.geometry() == null) {

            throw new IOException(
                    result == null
                            ? "Hasil konversi tidak tersedia."
                            : result.error() == null
                            ? "Geometry tidak berhasil dibuat."
                            : result.error()
            );
        }

        if (destination == null) {
            throw new IOException(
                    "Lokasi file geometry tidak tersedia."
            );
        }

        File parent = destination.getParentFile();

        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        Files.writeString(
                destination.toPath(),
                GSON.toJson(result.geometry()),
                StandardCharsets.UTF_8
        );
    }

    private JsonArray convertPivot(
            List<Double> origin
    ) {

        if (origin == null || origin.size() != 3) {
            return null;
        }

        double x = origin.get(0);
        double y = origin.get(1);
        double z = origin.get(2);

        if (!isFinite(x, y, z)) {
            return null;
        }

        return vector(
                x - COORDINATE_OFFSET,
                COORDINATE_OFFSET - y,
                z - COORDINATE_OFFSET
        );
    }

    private JsonArray convertRotation(
            ItemsAdderModelParser.ElementRotation rotation
    ) {

        if (rotation == null
                || rotation.axis() == null
                || !Double.isFinite(rotation.angle())) {
            return null;
        }

        double angle = rotation.angle();

        double x = 0.0;
        double y = 0.0;
        double z = 0.0;

        switch (rotation.axis().toLowerCase(Locale.ROOT)) {
            case "x" -> x = -angle;
            case "y" -> y = angle;
            case "z" -> z = -angle;
            default -> {
                return null;
            }
        }

        return vector(x, y, z);
    }

    private String convertDirection(String direction) {

        if (direction == null) {
            return null;
        }

        return switch (direction.toLowerCase(Locale.ROOT)) {
            case "north" -> "north";
            case "south" -> "south";
            case "east" -> "east";
            case "west" -> "west";
            case "up" -> "up";
            case "down" -> "down";
            default -> null;
        };
    }

    private String normalizeIdentifier(String identifier) {

        String normalized = identifier.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]", "_");

        if (!normalized.startsWith("geometry.")) {
            normalized = "geometry." + normalized;
        }

        return normalized;
    }

    private JsonArray vector(double... values) {

        JsonArray array = new JsonArray();

        for (double value : values) {
            array.add(value);
        }

        return array;
    }

    private boolean isFinite(double... values) {

        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }

        return true;
    }

    public record ConversionResult(
            boolean successful,
            JsonObject geometry,
            int convertedElements,
            int convertedFaces,
            int skippedFaces,
            List<String> warnings,
            String error
    ) {

        public ConversionResult {
            warnings = warnings == null
                    ? List.of()
                    : List.copyOf(warnings);
        }

        public static ConversionResult failure(String error) {
            return new ConversionResult(
                    false,
                    null,
                    0,
                    0,
                    0,
                    List.of(),
                    error
            );
        }
    }
}
