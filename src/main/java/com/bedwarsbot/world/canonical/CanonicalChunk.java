package com.bedwarsbot.world.canonical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class CanonicalChunk implements Comparable<CanonicalChunk> {
    private final int chunkX;
    private final int chunkZ;
    private final List<CanonicalSection> sections;

    public CanonicalChunk(int chunkX, int chunkZ, List<CanonicalSection> sections) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        List<CanonicalSection> copy = sections == null
            ? null
            : new ArrayList<CanonicalSection>(sections);
        if (copy != null) {
            Collections.sort(copy, new Comparator<CanonicalSection>() {
                @Override
                public int compare(CanonicalSection left, CanonicalSection right) {
                    if (left == null) return right == null ? 0 : -1;
                    if (right == null) return 1;
                    return Integer.compare(left.getSectionIndex(), right.getSectionIndex());
                }
            });
            copy = Collections.unmodifiableList(copy);
        }
        this.sections = copy;
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }
    public List<CanonicalSection> getSections() { return sections; }

    public Optional<CanonicalSection> findSection(int sectionIndex) {
        if (sections == null) return Optional.empty();
        for (CanonicalSection section : sections) {
            if (section == null) continue;
            if (section.getSectionIndex() == sectionIndex) return Optional.of(section);
        }
        return Optional.empty();
    }

    @Override
    public int compareTo(CanonicalChunk other) {
        int xComparison = Integer.compare(chunkX, other.chunkX);
        return xComparison != 0 ? xComparison : Integer.compare(chunkZ, other.chunkZ);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CanonicalChunk)) return false;
        CanonicalChunk that = (CanonicalChunk) other;
        return chunkX == that.chunkX && chunkZ == that.chunkZ
            && (sections == null ? that.sections == null : sections.equals(that.sections));
    }

    @Override
    public int hashCode() {
        int result = chunkX;
        result = 31 * result + chunkZ;
        result = 31 * result + (sections == null ? 0 : sections.hashCode());
        return result;
    }
}
