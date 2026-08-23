package org.onetwoone.gateway.audio;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable record of the mixer values an {@link AudioProfile} found before it
 * overwrote them, so {@code teardownMixer} can put them back.
 *
 * Immutability is the point (AUDIT B2). A profile's setup runs on the
 * {@code GsmAudioOpen} thread while its teardown runs on main, a pjsua worker or
 * {@code ConfigReload}; when the originals lived in a plain {@code HashMap} field,
 * a back-to-back call had teardown iterating the map while the next setup called
 * {@code clear()} on it — {@code ConcurrentModificationException}, or a teardown
 * that read an emptied map and restored nothing, leaving the local microphone
 * muted until reboot.
 *
 * A snapshot is built complete and then published with a single write to a
 * {@code volatile} field, so a reader either sees the whole thing or sees
 * {@code null} — never a collection being emptied underneath its iterator.
 * Both maps are defensive copies, so the builder may keep mutating its own.
 * Insertion order is preserved: MediaTek restores in the order it saved.
 */
final class MixerSnapshot {

    private final Map<String, Integer> values;
    private final Map<String, String> enumValues;

    MixerSnapshot(Map<String, Integer> values, Map<String, String> enumValues) {
        this.values = copyOrEmpty(values);
        this.enumValues = copyOrEmpty(enumValues);
    }

    private static <V> Map<String, V> copyOrEmpty(Map<String, V> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /** @return the saved INT value for {@code control}, or null if it was not saved. */
    Integer value(String control) {
        return values.get(control);
    }

    /** @return the saved ENUM item for {@code control}, or null if it was not saved. */
    String enumValue(String control) {
        return enumValues.get(control);
    }

    /** Saved INT controls, in the order they were saved. Unmodifiable. */
    Map<String, Integer> values() {
        return values;
    }

    /** How many controls this snapshot can restore. */
    int size() {
        return values.size() + enumValues.size();
    }

    @Override
    public String toString() {
        return "MixerSnapshot{" + values + ", " + enumValues + "}";
    }
}
