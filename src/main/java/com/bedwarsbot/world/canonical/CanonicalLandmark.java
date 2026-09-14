package com.bedwarsbot.world.canonical;

public final class CanonicalLandmark implements Comparable<CanonicalLandmark> {
    private final String landmarkId;
    private final LandmarkType type;
    private final CanonicalPosition position;
    private final Facing facing;
    private final String team;
    private final String displayName;
    private final double confidence;
    private final String provenance;
    private final ValidationStatus validationStatus;
    private final String notes;

    public CanonicalLandmark(
        String landmarkId,
        LandmarkType type,
        CanonicalPosition position,
        Facing facing,
        String team,
        String displayName,
        double confidence,
        String provenance,
        ValidationStatus validationStatus,
        String notes
    ) {
        this.landmarkId = landmarkId;
        this.type = type;
        this.position = position;
        this.facing = facing;
        this.team = team;
        this.displayName = displayName;
        this.confidence = confidence;
        this.provenance = provenance;
        this.validationStatus = validationStatus;
        this.notes = notes;
    }

    public String getLandmarkId() { return landmarkId; }
    public LandmarkType getType() { return type; }
    public CanonicalPosition getPosition() { return position; }
    public Facing getFacing() { return facing; }
    public String getTeam() { return team; }
    public String getDisplayName() { return displayName; }
    public double getConfidence() { return confidence; }
    public String getProvenance() { return provenance; }
    public ValidationStatus getValidationStatus() { return validationStatus; }
    public String getNotes() { return notes; }

    @Override
    public int compareTo(CanonicalLandmark other) {
        if (landmarkId == null) return other.landmarkId == null ? 0 : -1;
        if (other.landmarkId == null) return 1;
        return landmarkId.compareTo(other.landmarkId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CanonicalLandmark)) return false;
        CanonicalLandmark that = (CanonicalLandmark) other;
        return Double.compare(confidence, that.confidence) == 0
            && equal(landmarkId, that.landmarkId)
            && type == that.type
            && equal(position, that.position)
            && facing == that.facing
            && equal(team, that.team)
            && equal(displayName, that.displayName)
            && equal(provenance, that.provenance)
            && validationStatus == that.validationStatus
            && equal(notes, that.notes);
    }

    @Override
    public int hashCode() {
        long confidenceBits = Double.doubleToLongBits(confidence);
        int result = hash(landmarkId);
        result = 31 * result + hash(type);
        result = 31 * result + hash(position);
        result = 31 * result + hash(facing);
        result = 31 * result + hash(team);
        result = 31 * result + hash(displayName);
        result = 31 * result + (int) (confidenceBits ^ confidenceBits >>> 32);
        result = 31 * result + hash(provenance);
        result = 31 * result + hash(validationStatus);
        result = 31 * result + hash(notes);
        return result;
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static int hash(Object value) {
        return value == null ? 0 : value.hashCode();
    }
}
