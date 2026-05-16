package com.github.jing332.tts_server_android.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class JttsReaderAudioCacheProvider extends ContentProvider {
    public static final String PATH_ROOT = "reader_audio_cache";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File cacheRoot() {
        File ext = getContext().getExternalFilesDir(null);
        File root = new File(ext, PATH_ROOT);
        if (!root.exists()) root.mkdirs();
        return root;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            List<String> parts = uri.getPathSegments();
            if (parts == null || parts.size() < 2 || !PATH_ROOT.equals(parts.get(0))) {
                throw new FileNotFoundException("bad uri: " + uri);
            }

            File root = cacheRoot().getCanonicalFile();
            File f = root;
            for (int i = 1; i < parts.size(); i++) {
                f = new File(f, parts.get(i));
            }
            f = f.getCanonicalFile();

            String rootPath = root.getAbsolutePath();
            String filePath = f.getAbsolutePath();
            if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
                throw new FileNotFoundException("path escape denied: " + uri);
            }
            if (!f.exists() || !f.isFile()) {
                throw new FileNotFoundException("not found: " + uri);
            }
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(String.valueOf(e));
        }
    }

    @Override
    public String getType(Uri uri) {
        try {
            File f = resolve(uri);
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase();
                String mt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mt != null) return mt;
                if ("wav".equals(ext)) return "audio/wav";
                if ("mp3".equals(ext)) return "audio/mpeg";
                if ("m4a".equals(ext) || "aac".equals(ext)) return "audio/mp4";
                if ("json".equals(ext)) return "application/json";
            }
        } catch (Throwable ignored) {}
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("read only");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            String[] cols = projection != null ? projection : new String[]{
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE
            };
            MatrixCursor cursor = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
                else row[i] = null;
            }
            cursor.addRow(row);
            return cursor;
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
