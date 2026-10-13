package com.avatar.nputest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class VoiceStore {
    static final String DEFAULT_VOICE = "longxiaochun_v2";

    static final class Voice {
        final String id;
        final String name;
        final String status;

        Voice(String id, String name, String status) {
            requireText(id, "id");
            requireText(name, "name");
            if (DEFAULT_VOICE.equals(id)) throw new IllegalArgumentException("Reserved voice id");
            if (!"DEPLOYING".equals(status) && !"OK".equals(status) && !"UNDEPLOYED".equals(status)) {
                throw new IllegalArgumentException("Unknown voice status");
            }
            this.id = id;
            this.name = name;
            this.status = status;
        }
    }

    private final Path file;
    private Map<String, Voice> voices = new LinkedHashMap<>();
    private String selected = DEFAULT_VOICE;

    VoiceStore(File directory, String apiKey) throws IOException {
        requireText(apiKey, "apiKey");
        Files.createDirectories(directory.toPath());
        file = directory.toPath().resolve("voices-" + keyHash(apiKey) + ".properties");
        if (!Files.exists(file)) return;
        Properties data = new Properties();
        try (FileInputStream input = new FileInputStream(file.toFile())) {
            data.load(input);
            if (!"1".equals(data.getProperty("version"))) throw new IllegalArgumentException("Unknown store format");
            int count = Integer.parseInt(data.getProperty("count"));
            if (count < 0 || (long) count * 3 + 3 != data.size()) {
                throw new IllegalArgumentException("Incomplete voice store");
            }
            for (int i = 0; i < count; i++) {
                String prefix = "voice." + i + ".";
                Voice voice = new Voice(data.getProperty(prefix + "id"),
                        data.getProperty(prefix + "name"), data.getProperty(prefix + "status"));
                if (voices.put(voice.id, voice) != null) throw new IllegalArgumentException("Duplicate voice id");
            }
            selected = data.getProperty("selected");
            validateSelection(voices, selected);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Voice store is damaged", exception);
        }
    }

    synchronized List<Voice> list() {
        List<Voice> result = new ArrayList<>(voices.values());
        result.sort(Comparator.comparing((Voice voice) -> voice.name).thenComparing(voice -> voice.id));
        return result;
    }

    synchronized String selected() {
        return selected;
    }

    synchronized void put(String id, String name, String status) throws IOException {
        Voice voice = new Voice(id, name, status);
        Map<String, Voice> next = new LinkedHashMap<>(voices);
        next.put(id, voice);
        persist(next, id.equals(selected) && !"OK".equals(status) ? DEFAULT_VOICE : selected);
    }

    synchronized void select(String id) throws IOException {
        validateSelection(voices, id);
        persist(voices, id);
    }

    synchronized void remove(String id) throws IOException {
        requireText(id, "id");
        Map<String, Voice> next = new LinkedHashMap<>(voices);
        next.remove(id);
        persist(next, id.equals(selected) ? DEFAULT_VOICE : selected);
    }

    private void persist(Map<String, Voice> next, String nextSelected) throws IOException {
        Properties data = new Properties();
        data.setProperty("version", "1");
        data.setProperty("count", Integer.toString(next.size()));
        data.setProperty("selected", nextSelected);
        int i = 0;
        for (Voice voice : next.values()) {
            String prefix = "voice." + i++ + ".";
            data.setProperty(prefix + "id", voice.id);
            data.setProperty(prefix + "name", voice.name);
            data.setProperty(prefix + "status", voice.status);
        }
        Path temporary = Files.createTempFile(file.getParent(), "voice-", ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                data.store(output, null);
                output.getFD().sync();
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            voices = next;
            selected = nextSelected;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validateSelection(Map<String, Voice> voices, String id) {
        if (DEFAULT_VOICE.equals(id)) return;
        Voice voice = voices.get(id);
        if (voice == null || !"OK".equals(voice.status)) throw new IllegalArgumentException("Voice is not ready");
    }

    private static void requireText(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + label);
    }

    private static String keyHash(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(Character.forDigit((value & 255) >>> 4, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
