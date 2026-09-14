package com.bedwarsbot.world.canonical;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public final class CanonicalMapValidatorMain {
    private CanonicalMapValidatorMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream output, PrintStream error) {
        if (args == null || args.length != 1) {
            error.println("Usage: validate-canonical-map <map-directory>");
            return 2;
        }
        Path directory = Paths.get(args[0]).toAbsolutePath().normalize();
        try {
            CanonicalMap map = new CanonicalMapSerializer().load(directory);
            ValidationReport validation = new CanonicalMapValidator().validate(map);
            if (!validation.isValid()) {
                printInvalid(output, directory, validation);
                return 1;
            }
            CanonicalMapStatistics statistics = CanonicalMapStatistics.calculate(map);
            CanonicalMapManifest manifest = map.getManifest();
            output.println("Map: " + manifest.getDisplayName());
            output.println("Map ID: " + manifest.getMapId());
            output.println("Revision: " + manifest.getMapRevision());
            output.println("Mode: " + manifest.getBedwarsMode());
            output.println("Schema: " + manifest.getSchemaVersion());
            output.println("Minecraft: " + manifest.getMinecraftVersion());
            output.println("Dimension: " + manifest.getDimension());
            output.println("Bounds: " + manifest.getBounds());
            output.println("Chunks: " + statistics.getChunkCount());
            output.println("Sections: " + statistics.getSectionCount());
            output.println("Complete sections: " + statistics.getCompleteSections());
            output.println("Partial sections: " + statistics.getPartialSections());
            output.println("Unknown/missing sections: "
                + statistics.getUnknownOrMissingSections());
            output.println("Known positions: " + statistics.getKnownPositions());
            output.println("Known air: " + statistics.getKnownAirPositions());
            output.println("Known non-air: " + statistics.getKnownNonAirPositions());
            output.println("Palette entries: " + statistics.getPaletteEntries()
                + " (min=" + statistics.getMinimumPaletteSize()
                + ", max=" + statistics.getMaximumPaletteSize() + ')');
            output.println("Landmarks: " + statistics.getLandmarkCount());
            for (Map.Entry<LandmarkType, Integer> entry
                : statistics.getLandmarksByType().entrySet()) {
                if (entry.getValue().intValue() > 0) {
                    output.println("  " + entry.getKey() + ": " + entry.getValue());
                }
            }
            output.println("Geometry SHA-256: " + statistics.getGeometrySha256());
            output.println("Validation errors: 0");
            output.println("Result: VALID");
            return 0;
        } catch (CanonicalMapFormatException invalid) {
            printInvalid(output, directory, invalid.getValidationReport());
            return 1;
        } catch (IOException failure) {
            output.println("Map directory: " + directory);
            output.println("Validation errors: 1");
            output.println("  IO_FAILURE: " + failure.getMessage());
            output.println("Result: INVALID");
            return 1;
        } catch (RuntimeException failure) {
            output.println("Map directory: " + directory);
            output.println("Validation errors: 1");
            output.println("  INTERNAL_VALIDATION_FAILURE: " + failure.getMessage());
            output.println("Result: INVALID");
            return 1;
        }
    }

    private static void printInvalid(
        PrintStream output,
        Path directory,
        ValidationReport validation
    ) {
        output.println("Map directory: " + directory);
        output.println("Validation errors: " + validation.getErrorCount());
        for (ValidationIssue issue : validation.getErrors()) {
            output.println("  " + issue);
        }
        output.println("Result: INVALID");
    }
}
