package com.batchops.pipeline;

import com.batchops.persistence.Upload;

/** Object-store key layout. Source is immutable; every pipeline version writes its own artifacts. */
public final class ArtifactKeys {

    private ArtifactKeys() {
    }

    public static String source(Upload u) {
        return "uploads/" + u.uploadId + "/source/" + safeName(u.filename);
    }

    public static String fixed(Upload u, int version) {
        return "uploads/" + u.uploadId + "/v" + version + "/fixed.csv";
    }

    public static String standardized(Upload u) {
        return dir(u) + "standardized.csv";
    }

    public static String validated(Upload u) {
        return dir(u) + "validated.csv";
    }

    public static String transformed(Upload u) {
        return dir(u) + "transformed.csv";
    }

    public static String reportCsv(Upload u) {
        return dir(u) + "report.csv";
    }

    public static String reportPdf(Upload u) {
        return dir(u) + "report.pdf";
    }

    private static String dir(Upload u) {
        return "uploads/" + u.uploadId + "/v" + u.version + "/";
    }

    public static String safeName(String name) {
        String n = name == null ? "file" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
        return n.isBlank() ? "file" : n;
    }
}
