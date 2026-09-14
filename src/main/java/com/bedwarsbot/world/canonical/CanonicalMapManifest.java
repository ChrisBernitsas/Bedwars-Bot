package com.bedwarsbot.world.canonical;

public final class CanonicalMapManifest {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final int SUPPORTED_GEOMETRY_FORMAT_VERSION = 1;
    public static final int SUPPORTED_LANDMARK_FORMAT_VERSION = 1;

    private final int schemaVersion;
    private final String mapId;
    private final String displayName;
    private final String mapRevision;
    private final String bedwarsMode;
    private final String minecraftVersion;
    private final int dimension;
    private final MapBounds bounds;
    private final String sourceProvenance;
    private final String acquisitionDate;
    private final String sourceReference;
    private final String sourceChecksum;
    private final ValidationStatus validationStatus;
    private final String notes;
    private final String geometryFormat;
    private final int geometryFormatVersion;
    private final String landmarkFormat;
    private final int landmarkFormatVersion;

    public CanonicalMapManifest(
        int schemaVersion,
        String mapId,
        String displayName,
        String mapRevision,
        String bedwarsMode,
        String minecraftVersion,
        int dimension,
        MapBounds bounds,
        String sourceProvenance,
        String acquisitionDate,
        String sourceReference,
        String sourceChecksum,
        ValidationStatus validationStatus,
        String notes,
        String geometryFormat,
        int geometryFormatVersion,
        String landmarkFormat,
        int landmarkFormatVersion
    ) {
        this.schemaVersion = schemaVersion;
        this.mapId = mapId;
        this.displayName = displayName;
        this.mapRevision = mapRevision;
        this.bedwarsMode = bedwarsMode;
        this.minecraftVersion = minecraftVersion;
        this.dimension = dimension;
        this.bounds = bounds;
        this.sourceProvenance = sourceProvenance;
        this.acquisitionDate = acquisitionDate;
        this.sourceReference = sourceReference;
        this.sourceChecksum = sourceChecksum;
        this.validationStatus = validationStatus;
        this.notes = notes;
        this.geometryFormat = geometryFormat;
        this.geometryFormatVersion = geometryFormatVersion;
        this.landmarkFormat = landmarkFormat;
        this.landmarkFormatVersion = landmarkFormatVersion;
    }

    public int getSchemaVersion() { return schemaVersion; }
    public String getMapId() { return mapId; }
    public String getDisplayName() { return displayName; }
    public String getMapRevision() { return mapRevision; }
    public String getBedwarsMode() { return bedwarsMode; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public int getDimension() { return dimension; }
    public MapBounds getBounds() { return bounds; }
    public String getSourceProvenance() { return sourceProvenance; }
    public String getAcquisitionDate() { return acquisitionDate; }
    public String getSourceReference() { return sourceReference; }
    public String getSourceChecksum() { return sourceChecksum; }
    public ValidationStatus getValidationStatus() { return validationStatus; }
    public String getNotes() { return notes; }
    public String getGeometryFormat() { return geometryFormat; }
    public int getGeometryFormatVersion() { return geometryFormatVersion; }
    public String getLandmarkFormat() { return landmarkFormat; }
    public int getLandmarkFormatVersion() { return landmarkFormatVersion; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CanonicalMapManifest)) return false;
        CanonicalMapManifest that = (CanonicalMapManifest) other;
        return schemaVersion == that.schemaVersion
            && dimension == that.dimension
            && geometryFormatVersion == that.geometryFormatVersion
            && landmarkFormatVersion == that.landmarkFormatVersion
            && equal(mapId, that.mapId)
            && equal(displayName, that.displayName)
            && equal(mapRevision, that.mapRevision)
            && equal(bedwarsMode, that.bedwarsMode)
            && equal(minecraftVersion, that.minecraftVersion)
            && equal(bounds, that.bounds)
            && equal(sourceProvenance, that.sourceProvenance)
            && equal(acquisitionDate, that.acquisitionDate)
            && equal(sourceReference, that.sourceReference)
            && equal(sourceChecksum, that.sourceChecksum)
            && validationStatus == that.validationStatus
            && equal(notes, that.notes)
            && equal(geometryFormat, that.geometryFormat)
            && equal(landmarkFormat, that.landmarkFormat);
    }

    @Override
    public int hashCode() {
        int result = schemaVersion;
        result = 31 * result + hash(mapId);
        result = 31 * result + hash(displayName);
        result = 31 * result + hash(mapRevision);
        result = 31 * result + hash(bedwarsMode);
        result = 31 * result + hash(minecraftVersion);
        result = 31 * result + dimension;
        result = 31 * result + hash(bounds);
        result = 31 * result + hash(sourceProvenance);
        result = 31 * result + hash(acquisitionDate);
        result = 31 * result + hash(sourceReference);
        result = 31 * result + hash(sourceChecksum);
        result = 31 * result + hash(validationStatus);
        result = 31 * result + hash(notes);
        result = 31 * result + hash(geometryFormat);
        result = 31 * result + geometryFormatVersion;
        result = 31 * result + hash(landmarkFormat);
        result = 31 * result + landmarkFormatVersion;
        return result;
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static int hash(Object value) {
        return value == null ? 0 : value.hashCode();
    }
}
