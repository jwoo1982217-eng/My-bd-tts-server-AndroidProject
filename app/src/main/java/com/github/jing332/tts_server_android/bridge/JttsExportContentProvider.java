
package com.github.jing332.tts_server_android.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class JttsExportContentProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String name = getFileName(uri);
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("read only");
        }

        File f = resolveFile(uri);
        if (f == null || !f.exists() || !f.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }

        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = resolveFile(uri);
        if (f == null || !f.exists()) return null;

        MatrixCursor c = new MatrixCursor(new String[]{
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE
        });
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private File resolveFile(Uri uri) {
        try {
            if (getContext() == null) return null;

            java.util.List<String> seg = uri.getPathSegments();
            if (seg.size() != 3) return null;
            if (!"export".equals(seg.get(0))) return null;

            String requestId = safeFilePart(seg.get(1));
            String fileName = safeFileName(seg.get(2));

            File base = new File(getContext().getFilesDir(), "jtts_exports");
            File target = new File(new File(base, requestId), fileName);

            String basePath = base.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            if (!targetPath.startsWith(basePath)) return null;

            return target;
        } catch (Throwable t) {
            return null;
        }
    }

    private String getFileName(Uri uri) {
        try {
            java.util.List<String> seg = uri.getPathSegments();
            if (seg.size() >= 3) return seg.get(2);
        } catch (Throwable ignored) {}
        return "";
    }

    private static String safeFilePart(String s) {
        if (s == null) s = "";
        s = s.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (s.length() > 80) s = s.substring(0, 80);
        if (s.length() == 0) s = "empty";
        return s;
    }

    private static String safeFileName(String s) {
        if (s == null) s = "";
        s = s.replaceAll("[^A-Za-z0-9_\\-.]", "_");
        if (s.length() == 0) s = "file.bin";
        return s;
    }
}
