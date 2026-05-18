package com.github.jing332.tts_server_android.bridge;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;

import com.github.jing332.tts_server_android.service.systts.help.AudioCacheFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class JttsReaderAudioCacheExportService extends Service {
    private static final String TAG = "JttsReaderAudioCacheExport";
    public static final String ACTION_EXPORT_READER_AUDIO_CACHE = "com.jtts.action.EXPORT_READER_AUDIO_CACHE";
    public static final String ACTION_ACK_READER_AUDIO_CACHE = "com.jtts.action.ACK_READER_AUDIO_CACHE";
    public static final String ACTION_EXPORT_READER_AUDIO_CACHE_RESULT = "com.jtts.action.EXPORT_READER_AUDIO_CACHE_RESULT";
    private static final String ACTION_EXPORT = "com.jtts.action.EXPORT_CHAPTER_AUDIO";
    private static final String ACTION_IMPORT_CONTEXT = "com.jtts.action.IMPORT_CHAPTER_CONTEXT";
    private static final String ACTION_IMPORT_READING_POINTER = "com.jtts.action.IMPORT_READING_POINTER";

    private static final String EXTRA_RESULT_RECEIVER = "resultReceiver";
    private static final String EXTRA_REQUEST_ID = "requestId";
    private static final String EXTRA_CALLER_PACKAGE = "callerPackage";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    private static final String BRIDGE_FGS_CHANNEL_ID = "jtts_bridge_tasks";
    private static final int BRIDGE_FGS_NOTIFICATION_ID = 712093;

    private void startBridgeForeground(String action) {
        try {
            String text = "J.TTS Bridge 正在处理请求";
            if (ACTION_IMPORT_CONTEXT.equals(action)) {
                text = "正在导入章节上下文";
            } else if (ACTION_IMPORT_READING_POINTER.equals(action)) {
                text = "正在同步朗读位置";
            } else if (ACTION_EXPORT.equals(action)) {
                text = "正在处理章节音频导出";
            } else if ("com.jtts.action.EXPORT_READER_AUDIO_CACHE".equals(action)) {
                text = "正在导出朗读音频缓存";
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        BRIDGE_FGS_CHANNEL_ID,
                        "J.TTS Bridge",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("J.TTS 与 J阅读桥接任务");
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(channel);
            }

            int icon = getApplicationInfo().icon;
            if (icon == 0) icon = android.R.drawable.stat_notify_sync;

            Notification.Builder builder;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                builder = new Notification.Builder(this, BRIDGE_FGS_CHANNEL_ID);
            } else {
                builder = new Notification.Builder(this);
            }

            Notification notification = builder
                    .setSmallIcon(icon)
                    .setContentTitle("J.TTS Bridge")
                    .setContentText(text)
                    .setOngoing(false)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build();

            startForeground(BRIDGE_FGS_NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            Log.e(TAG, "startBridgeForeground failed", t);
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || (!ACTION_EXPORT_READER_AUDIO_CACHE.equals(intent.getAction()) && !ACTION_ACK_READER_AUDIO_CACHE.equals(intent.getAction()))) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startBridgeForeground(intent.getAction());

        final String action = intent.getAction();
        Bundle req = intent.getExtras() == null ? new Bundle() : new Bundle(intent.getExtras());

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ACTION_ACK_READER_AUDIO_CACHE.equals(action)) {
                          ackReaderAudioCache(req);
                      } else {
                          exportReaderAudioCache(req);
                      }
                } catch (Throwable t) {
                    Log.e(TAG, "export reader audio cache failed", t);
                    sendResult(req, "failed", -1, null, String.valueOf(t));
                } finally {
                    stopSelf(startId);
                }
            }
        });

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }


    private void ackReaderAudioCache(Bundle req) throws Exception {
        sendResult(req, "running", 0, null, null);

        String requestId = req.getString(EXTRA_REQUEST_ID, "");
        if (requestId == null || requestId.trim().length() == 0) {
            requestId = req.getString("taskId", "");
        }
        String taskId = req.getString("taskId", requestId);
        String sessionId = req.getString("sessionId", "");
        String callerPackage = req.getString(EXTRA_CALLER_PACKAGE, "");
        String ackStatus = req.getString("status", "success");
        if (!"success".equalsIgnoreCase(ackStatus) && !"done".equalsIgnoreCase(ackStatus)) {
            throw new RuntimeException("ACK status 不是 success/done，拒绝删除缓存: " + ackStatus);
        }

        String contentHash = req.getString("contentHash", "");
        int expectedSegmentCount = req.getInt("segmentCount", -1);
        int consumedCount = req.getInt("consumedCount", -1);
        if (expectedSegmentCount < 0 && consumedCount >= 0) {
            expectedSegmentCount = consumedCount;
        }
        int consumedSeqStart = req.getInt("consumedSeqStart", -1);
        int consumedSeqEnd = req.getInt("consumedSeqEnd", -1);
        String manifestJson = req.getString("manifestJson", "");

        File root = cacheRoot().getCanonicalFile();
        JSONObject manifest = null;
        File ackManifestFile = null;

        if (manifestJson != null && manifestJson.trim().length() > 0) {
            manifest = new JSONObject(manifestJson);
        }

        if (manifest == null) {
            String manifestPath = req.getString("manifestPath", "");
            if (manifestPath != null && manifestPath.trim().length() > 0) {
                File f = new File(manifestPath.trim()).getCanonicalFile();
                ensureUnderReaderCacheRoot(root, f);
                ackManifestFile = f;
                manifest = new JSONObject(readText(f, 10 * 1024 * 1024));
            }
        }

        if (manifest == null && requestId != null && requestId.trim().length() > 0) {
            File exportDir = new File(new File(root, "exports"), safeFileName(requestId.trim()));
            File f = new File(exportDir, "manifest.json");
            if (!f.exists()) {
                f = new File(new File(new File(root, "exports"), requestId.trim()), "manifest.json");
            }
            if (f.exists() && f.isFile()) {
                ackManifestFile = f.getCanonicalFile();
                ensureUnderReaderCacheRoot(root, ackManifestFile);
                manifest = new JSONObject(readText(ackManifestFile, 10 * 1024 * 1024));
            }
        }

        if (manifest == null) {
            throw new RuntimeException("ACK 缺少 manifestJson / manifestPath，且无法通过 requestId 找到导出 manifest");
        }

        if (contentHash != null && contentHash.length() > 0) {
            String gotHash = manifest.optString("contentHash", "");
            if (gotHash.length() > 0 && !contentHash.equals(gotHash)) {
                throw new RuntimeException("ACK contentHash 不匹配: request=" + contentHash + " manifest=" + gotHash);
            }
        }

        if (sessionId != null && sessionId.length() > 0) {
            String gotSessionId = manifest.optString("sessionId", "");
            if (gotSessionId.length() > 0 && !sessionId.equals(gotSessionId)) {
                throw new RuntimeException("ACK sessionId 不匹配: request=" + sessionId + " manifest=" + gotSessionId);
            }
        }

        JSONArray segments = manifest.optJSONArray("segments");
        if (segments == null) segments = manifest.optJSONArray("items");
        if (segments == null || segments.length() == 0) {
            throw new RuntimeException("ACK manifest 没有 segments/items，拒绝删除缓存");
        }

        if (expectedSegmentCount >= 0 && expectedSegmentCount != segments.length()) {
            throw new RuntimeException("ACK segmentCount 不匹配: request=" + expectedSegmentCount + " manifest=" + segments.length());
        }

        int minSeq = Integer.MAX_VALUE;
        int maxSeq = Integer.MIN_VALUE;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.optJSONObject(i);
            if (seg == null) continue;
            int seq = seg.optInt("seq", seg.optInt("index", i));
            if (seq < minSeq) minSeq = seq;
            if (seq > maxSeq) maxSeq = seq;
        }
        if (consumedSeqStart >= 0 && minSeq != consumedSeqStart) {
            throw new RuntimeException("ACK consumedSeqStart 不匹配: request=" + consumedSeqStart + " manifestMin=" + minSeq);
        }
        if (consumedSeqEnd >= 0 && maxSeq != consumedSeqEnd) {
            throw new RuntimeException("ACK consumedSeqEnd 不匹配: request=" + consumedSeqEnd + " manifestMax=" + maxSeq);
        }
        if (consumedCount >= 0 && consumedCount != segments.length()) {
            throw new RuntimeException("ACK consumedCount 不匹配: request=" + consumedCount + " manifest=" + segments.length());
        }

        JSONArray deletedFiles = new JSONArray();
        JSONArray skippedFiles = new JSONArray();
        int deletedCount = 0;
        long deletedBytes = 0L;

        for (int i = 0; i < segments.length(); i++) {
            JSONObject seg = segments.optJSONObject(i);
            if (seg == null) continue;

            String rel = seg.optString("file", "");
            if (rel == null || rel.length() == 0) {
                rel = seg.optString("fileName", "");
            }
            if (rel == null || rel.length() == 0) {
                skippedFiles.put("missing-file-field@" + i);
                continue;
            }

            File f = resolveReaderCacheFile(root, rel);
            if (!f.exists() || !f.isFile()) {
                skippedFiles.put(rel);
                continue;
            }

            long expectedLength = seg.optLong("length", seg.optLong("sizeBytes", -1L));
            if (expectedLength > 0L && f.length() != expectedLength) {
                throw new RuntimeException("ACK 删除前长度校验失败: " + rel + ", expected=" + expectedLength + ", actual=" + f.length());
            }

            long len = f.length();
            if (f.delete()) {
                deletedCount++;
                deletedBytes += len;
                deletedFiles.put(rel);
                deleteEmptyParents(root, f);
            } else {
                skippedFiles.put(rel);
            }
        }

        if (ackManifestFile == null && requestId != null && requestId.trim().length() > 0) {
            File exportDir = new File(new File(root, "exports"), safeFileName(requestId.trim()));
            File f = new File(exportDir, "manifest.json");
            if (f.exists() && f.isFile()) ackManifestFile = f.getCanonicalFile();
        }

        if (ackManifestFile != null && ackManifestFile.exists() && ackManifestFile.isFile()) {
            try {
                ensureUnderReaderCacheRoot(root, ackManifestFile);
                ackManifestFile.delete();
                deleteEmptyParents(root, ackManifestFile);
            } catch (Throwable ignored) {
            }
        }

        JSONObject out = new JSONObject();
        out.put("method", "ackReaderAudioCache");
        out.put("status", "done");
        out.put("requestId", requestId);
        out.put("taskId", taskId);
        out.put("sessionId", sessionId);
        out.put("callerPackage", callerPackage);
        out.put("contentHash", manifest.optString("contentHash", contentHash));
        out.put("segmentCount", segments.length());
        out.put("consumedSeqStart", minSeq == Integer.MAX_VALUE ? -1 : minSeq);
        out.put("consumedSeqEnd", maxSeq == Integer.MIN_VALUE ? -1 : maxSeq);
        out.put("consumedCount", segments.length());
        out.put("deletedCount", deletedCount);
        out.put("deletedBytes", deletedBytes);
        out.put("deletedFiles", deletedFiles);
        out.put("skippedFiles", skippedFiles);
        out.put("updatedAt", System.currentTimeMillis());

        Log.i(TAG, "reader audio cache ack done requestId=" + requestId + " deleted=" + deletedCount + " bytes=" + deletedBytes);
        sendResult(req, "done", 100, out, null);
    }

    private File resolveReaderCacheFile(File root, String relativePath) throws Exception {
        if (relativePath == null || relativePath.length() == 0 || relativePath.contains("://")) {
            throw new RuntimeException("非法缓存相对路径: " + relativePath);
        }
        File f = new File(root, relativePath).getCanonicalFile();
        ensureUnderReaderCacheRoot(root, f);
        return f;
    }

    private void ensureUnderReaderCacheRoot(File root, File file) throws Exception {
        File r = root.getCanonicalFile();
        File f = file.getCanonicalFile();
        String rootPath = r.getAbsolutePath();
        String filePath = f.getAbsolutePath();
        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new RuntimeException("拒绝访问 reader_audio_cache 外部路径: " + filePath);
        }
    }

    private void deleteEmptyParents(File root, File file) {
        try {
            File r = root.getCanonicalFile();
            File dir = file.getParentFile();
            while (dir != null) {
                File d = dir.getCanonicalFile();
                if (d.equals(r)) break;
                String[] children = d.list();
                if (children != null && children.length == 0) {
                    d.delete();
                    dir = d.getParentFile();
                } else {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private File cacheRoot() {
        File ext = getExternalFilesDir(null);
        File root = new File(ext, "reader_audio_cache");
        if (!root.exists()) root.mkdirs();
        return root;
    }

    private void exportReaderAudioCache(Bundle req) throws Exception {
        sendResult(req, "running", 0, null, null);

        if (req.getBoolean("useAudioCacheFactory", true)) {
            String requestId = req.getString(EXTRA_REQUEST_ID, "cache_" + System.currentTimeMillis() + "_" + UUID.randomUUID());
            String sessionId = req.getString("sessionId", "");
            String contentHash = req.getString("contentHash", "");
            String bookName = req.getString("bookName", "");
            String chapterTitle = req.getString("chapterTitle", "");
            int chapterIndex = req.getInt("chapterIndex", -1);
            String authority = getPackageName() + ".jtts.reader.cache.provider";

            JSONObject out = AudioCacheFactory.INSTANCE.exportReaderAudioCacheForBridge(
                    this,
                    requestId,
                    sessionId,
                    contentHash,
                    bookName,
                    chapterTitle,
                    chapterIndex,
                    authority
            );

            sendResult(req, "done", 100, out, null);
            return;
        }


        File root = cacheRoot();
        File targetDir = pickBestCacheDir(root, req);

        if (targetDir == null || !targetDir.exists()) {
            throw new RuntimeException("未找到 reader_audio_cache 可导出的缓存目录: " + root.getAbsolutePath());
        }

        String requestId = req.getString(EXTRA_REQUEST_ID, "cache_" + System.currentTimeMillis() + "_" + UUID.randomUUID());
        String sessionId = req.getString("sessionId", "");
        String contentHash = req.getString("contentHash", "");
        String bookName = req.getString("bookName", "");
        String chapterTitle = req.getString("chapterTitle", "");
        int chapterIndex = req.getInt("chapterIndex", -1);

        JSONObject out = new JSONObject();
        out.put("method", "exportReaderAudioCache");
        out.put("status", "done");
        out.put("requestId", requestId);
        out.put("sessionId", sessionId);
        out.put("contentHash", contentHash);
        out.put("bookName", bookName);
        out.put("chapterTitle", chapterTitle);
        out.put("chapterIndex", chapterIndex);
        out.put("cacheDir", targetDir.getName());
        out.put("createdAt", System.currentTimeMillis());

        File existingManifest = new File(targetDir, "manifest.json");
        if (existingManifest.exists() && existingManifest.isFile()) {
            try {
                out.put("sourceManifestPreview", readText(existingManifest, 20000));
                out.put("sourceManifestUri", uriForFile(root, existingManifest).toString());
            } catch (Throwable ignored) {}
        }

        JSONArray segments = buildSegments(root, targetDir);
        out.put("segmentCount", segments.length());
        out.put("segments", segments);

        File exportDir = new File(root, "exports");
        if (!exportDir.exists()) exportDir.mkdirs();

        File exportManifest = new File(exportDir, safeFileName("reader_audio_cache_export_" + requestId + ".json"));
        writeText(exportManifest, out.toString(2));

        out.put("manifestUri", uriForFile(root, exportManifest).toString());

        Log.i(TAG, "reader audio cache export done dir=" + targetDir.getAbsolutePath() +
                " segments=" + segments.length());

        sendResult(req, "done", 100, out, null);
    }

    private File pickBestCacheDir(File root, Bundle req) {
        String explicit = req.getString("cacheDirName", "");
        if (explicit != null && explicit.trim().length() > 0) {
            File f = new File(root, explicit.trim());
            if (f.exists() && f.isDirectory()) return f;
        }

        ArrayList<File> candidates = new ArrayList<>();
        collectCandidateDirs(root, candidates, 0, 4);

        if (candidates.isEmpty()) return root;

        Collections.sort(candidates, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long ma = lastModifiedDeep(a);
                long mb = lastModifiedDeep(b);
                return Long.compare(mb, ma);
            }
        });

        return candidates.get(0);
    }

    private void collectCandidateDirs(File dir, ArrayList<File> out, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || depth > maxDepth) return;

        if (hasAudioLikeFiles(dir) || new File(dir, "manifest.json").exists()) {
            out.add(dir);
        }

        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory() && !"exports".equals(f.getName())) {
                collectCandidateDirs(f, out, depth + 1, maxDepth);
            }
        }
    }

    private boolean hasAudioLikeFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (n.equals("manifest.json") || n.endsWith(".json") || n.endsWith(".tmp")) continue;
            if (f.length() > 128) return true;
        }
        return false;
    }

    private JSONArray buildSegments(File root, File dir) throws Exception {
        ArrayList<File> files = new ArrayList<>();
        collectAudioFiles(dir, files);

        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                int ia = leadingIndex(a.getName());
                int ib = leadingIndex(b.getName());
                if (ia != ib) return Integer.compare(ia, ib);
                return a.getName().compareTo(b.getName());
            }
        });

        JSONArray arr = new JSONArray();
        int i = 0;
        for (File f : files) {
            JSONObject item = new JSONObject();
            int index = leadingIndex(f.getName());
            if (index < 0) index = i;

            item.put("index", index);
            item.put("order", i);
            item.put("fileName", f.getName());
            item.put("sizeBytes", f.length());
            item.put("audioUri", uriForFile(root, f).toString());
            item.put("audioMimeType", guessMime(f));
            item.put("durationMs", readDurationMs(f));
            item.put("lastModified", f.lastModified());

            arr.put(item);
            i++;
        }
        return arr;
    }

    private void collectAudioFiles(File dir, ArrayList<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                if (!"exports".equals(f.getName())) collectAudioFiles(f, out);
                continue;
            }

            String n = f.getName().toLowerCase();
            if (n.equals("manifest.json")) continue;
            if (n.startsWith("reader_audio_cache_export_")) continue;
            if (n.endsWith(".json") || n.endsWith(".tmp") || n.endsWith(".lock")) continue;
            if (f.length() <= 128) continue;
            out.add(f);
        }
    }

    private int leadingIndex(String name) {
        try {
            int i = 0;
            while (i < name.length() && Character.isDigit(name.charAt(i))) i++;
            if (i <= 0) return -1;
            return Integer.parseInt(name.substring(0, i));
        } catch (Throwable e) {
            return -1;
        }
    }

    private long readDurationMs(File f) {
        MediaMetadataRetriever r = null;
        try {
            r = new MediaMetadataRetriever();
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d == null || d.length() == 0) return -1;
            return Long.parseLong(d);
        } catch (Throwable e) {
            return -1;
        } finally {
            try {
                if (r != null) r.release();
            } catch (Throwable ignored) {}
        }
    }

    private String guessMime(File f) {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a") || n.endsWith(".aac")) return "audio/mp4";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".wav")) return "audio/wav";
        // 当前 J.TTS reader_audio_cache 无扩展名时，通常仍按音频流处理；先给 wav 兜底。
        return "audio/wav";
    }

    private Uri uriForFile(File root, File file) throws Exception {
        File r = root.getCanonicalFile();
        File f = file.getCanonicalFile();

        String rootPath = r.getAbsolutePath();
        String filePath = f.getAbsolutePath();

        if (!filePath.equals(rootPath) && !filePath.startsWith(rootPath + File.separator)) {
            throw new RuntimeException("file outside reader_audio_cache: " + filePath);
        }

        String rel = filePath.equals(rootPath) ? "" : filePath.substring(rootPath.length() + 1);
        rel = rel.replace(File.separatorChar, '/');

        Uri.Builder b = new Uri.Builder()
                .scheme("content")
                .authority(getPackageName() + ".jtts.reader.cache.provider")
                .appendPath("reader_audio_cache");

        if (rel.length() > 0) {
            String[] parts = rel.split("/");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].length() > 0) b.appendPath(parts[i]);
            }
        }

        return b.build();
    }

    private long lastModifiedDeep(File f) {
        if (f == null || !f.exists()) return 0;
        long m = f.lastModified();
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File c : files) {
                    long cm = lastModifiedDeep(c);
                    if (cm > m) m = cm;
                }
            }
        }
        return m;
    }

    private String readText(File f, int max) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                int can = Math.min(n, max - total);
                if (can > 0) {
                    bos.write(buf, 0, can);
                    total += can;
                }
                if (total >= max) break;
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private void writeText(File f, String text) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        File tmp = new File(parent, f.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }

        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) {
            throw new RuntimeException("rename failed: " + tmp + " -> " + f);
        }
    }

    private String safeFileName(String s) {
        return String.valueOf(s).replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void sendResult(Bundle req, String status, int progress, JSONObject manifest, String error) {
        try {
            Bundle b = new Bundle();
            b.putString("status", status);
            b.putInt("progress", progress);
            if (manifest != null) b.putString("manifestJson", manifest.toString());
            if (error != null) b.putString("error", error);

            ResultReceiver rr = null;
            try { rr = req.getParcelable(EXTRA_RESULT_RECEIVER); } catch (Throwable ignored) {}
            if (rr != null) {
                int code = "done".equals(status) ? 1 : ("failed".equals(status) ? -1 : 0);
                rr.send(code, b);
            }

            Intent out = new Intent(ACTION_EXPORT_READER_AUDIO_CACHE_RESULT);
            out.putExtras(b);
            out.putExtra(EXTRA_REQUEST_ID, req.getString(EXTRA_REQUEST_ID, ""));
            out.putExtra("method", "exportReaderAudioCache");

            String callerPackage = req.getString(EXTRA_CALLER_PACKAGE, "");
            if (callerPackage != null && callerPackage.length() > 0) out.setPackage(callerPackage);

            sendBroadcast(out);
        } catch (Throwable t) {
            Log.w(TAG, "send result failed", t);
        }
    }
}
