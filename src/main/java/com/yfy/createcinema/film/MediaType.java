package com.yfy.createcinema.film;

public enum MediaType {
    VIDEO("video"),
    IMAGE("image"),
    ALBUM("album"),
    SLIDES("slides");

    private final String id;

    MediaType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isStatic() {
        return this != VIDEO;
    }

    public boolean supportsPageNavigation() {
        return this == ALBUM || this == SLIDES;
    }

    public static MediaType fromId(String id) {
        for (MediaType type : values()) {
            if (type.id.equalsIgnoreCase(id)) return type;
        }
        return VIDEO;
    }
}
