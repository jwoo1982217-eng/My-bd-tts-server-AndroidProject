package com.github.jing332.tts_server_android.bridge;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
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
        String ackStatus = req.getString("status", "");

        if (!"success".equalsIgnoreCase(ackStatus)) {
            throw new RuntimeException("ACK status 不是 success，拒绝删除缓存: " + ackStatus);
        }

        String contentHash = req.getString("contentHash", "");
        int expectedItemCount = req.getInt("itemCount", -1);
        int consumedCount = req.getInt("consumedCount", -1);
        if (expectedItemCount < 0 && consumedCount >= 0) {
            expectedItemCount = consumedCount;
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

        JSONArray items = manifest.optJSONArray("items");
        if (items == null || items.length() == 0) {
            throw new RuntimeException("ACK manifest 没有 items，拒绝删除缓存");
        }

        if (expectedItemCount >= 0 && expectedItemCount != items.length()) {
            throw new RuntimeException("ACK itemCount 不匹配: request=" + expectedItemCount + " manifest=" + items.length());
        }

        boolean[] seenSeq = new boolean[items.length()];
        int minSeq = Integer.MAX_VALUE;
        int maxSeq = Integer.MIN_VALUE;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                throw new RuntimeException("ACK items[" + i + "] 不是对象");
            }

            int seq = item.optInt("seq", -1);
            if (seq < 0 || seq >= items.length()) {
                throw new RuntimeException("ACK items seq 非 0..N-1 连续范围: seq=" + seq + ", count=" + items.length());
            }
            if (seenSeq[seq]) {
                throw new RuntimeException("ACK items seq 重复: " + seq);
            }
            seenSeq[seq] = true;

            if (seq < minSeq) minSeq = seq;
            if (seq > maxSeq) maxSeq = seq;
        }

        for (int i = 0; i < seenSeq.length; i++) {
            if (!seenSeq[i]) {
                throw new RuntimeException("ACK items seq 不连续，缺失: " + i);
            }
        }

        if (consumedSeqStart >= 0 && minSeq != consumedSeqStart) {
            throw new RuntimeException("ACK consumedSeqStart 不匹配: request=" + consumedSeqStart + " manifestMin=" + minSeq);
        }
        if (consumedSeqEnd >= 0 && maxSeq != consumedSeqEnd) {
            throw new RuntimeException("ACK consumedSeqEnd 不匹配: request=" + consumedSeqEnd + " manifestMax=" + maxSeq);
        }
        if (consumedCount >= 0 && consumedCount != items.length()) {
            throw new RuntimeException("ACK consumedCount 不匹配: request=" + consumedCount + " manifest=" + items.length());
        }

        JSONArray deletedFiles = new JSONArray();
        JSONArray skippedFiles = new JSONArray();
        int deletedCount = 0;
        long deletedBytes = 0L;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;

            String rel = item.optString("file", "");
            if (rel == null || rel.length() == 0) {
                throw new RuntimeException("ACK item 缺少 file 字段: index=" + i);
            }

            long expectedLength = item.optLong("length", -1L);
            if (expectedLength <= 0L) {
                throw new RuntimeException("ACK item 缺少有效 length 字段: " + rel);
            }

            File f = resolveReaderCacheFile(root, rel);
            if (!f.exists() || !f.isFile()) {
                skippedFiles.put(rel);
                continue;
            }

            if (f.length() != expectedLength) {
                throw new RuntimeException("ACK 删除前 length 校验失败: " + rel + ", expected=" + expectedLength + ", actual=" + f.length());
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
        out.put("itemCount", items.length());
        out.put("consumedSeqStart", minSeq == Integer.MAX_VALUE ? -1 : minSeq);
        out.put("consumedSeqEnd", maxSeq == Integer.MIN_VALUE ? -1 : maxSeq);
        out.put("consumedCount", items.length());
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

        String requestId = req.getString(EXTRA_REQUEST_ID, "");
        if (requestId == null || requestId.trim().length() == 0) {
            requestId = req.getString("taskId", "");
        }
        if (requestId == null || requestId.trim().length() == 0) {
            requestId = "cache_" + System.currentTimeMillis() + "_" + UUID.randomUUID();
        }

        String taskId = req.getString("taskId", requestId);
        String sessionId = req.getString("sessionId", "");
        String contentHash = req.getString("contentHash", "");
        String bookName = req.getString("bookName", "");
        String chapterTitle = req.getString("chapterTitle", "");
        int chapterIndex = req.getInt("chapterIndex", -1);

        int sampleRate = req.getInt("sampleRate", 24000);
        int channels = req.getInt("channels", 1);
        String pcmFormat = req.getString("pcmFormat", "PCM_16BIT");

        File root = cacheRoot().getCanonicalFile();
        File targetDir = pickCacheDirForPcmExport(root, req);

        if (targetDir == null || !targetDir.exists() || !targetDir.isDirectory()) {
            throw new RuntimeException("未找到当前章节 reader_audio_cache PCM 缓存目录: " + root.getAbsolutePath());
        }

        JSONArray items = buildPcmItems(root, targetDir);
        if (items.length() == 0) {
            throw new RuntimeException("当前章节 reader_audio_cache 没有可导出的 PCM 分片: " + targetDir.getAbsolutePath());
        }

        JSONObject out = new JSONObject();
        out.put("method", "exportReaderAudioCache");
        out.put("status", "done");
        out.put("requestId", requestId);
        out.put("taskId", taskId);
        out.put("sessionId", sessionId);
        out.put("contentHash", contentHash);
        out.put("bookName", bookName);
        out.put("chapterTitle", chapterTitle);
        out.put("chapterIndex", chapterIndex);
        out.put("sampleRate", sampleRate);
        out.put("channels", channels);
        out.put("pcmFormat", pcmFormat);
        out.put("cacheDir", relativePath(root, targetDir));
        out.put("itemCount", items.length());
        out.put("createdAt", System.currentTimeMillis());
        out.put("items", items);

        // 不再生成 reader_audio_cache/exports。
        // J.TTS 只在当前章节缓存目录写 manifest.json，里面只列 PCM items。
        File exportManifest = new File(targetDir, "manifest.json");
        Uri manifestUri = uriForFile(root, exportManifest);
        out.put("manifestUri", manifestUri.toString());
        out.put("manifestFile", relativePath(root, exportManifest));

        writeText(exportManifest, out.toString(2));

        grantReadUri(req, manifestUri);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String uri = item.optString("uri", "");
            if (uri != null && uri.length() > 0) {
                grantReadUri(req, Uri.parse(uri));
            }
        }

        Log.i(TAG, "reader audio cache PCM manifest export done dir=" + targetDir.getAbsolutePath()
                + " items=" + items.length()
                + " requestId=" + requestId);

        sendResult(req, "done", 100, out, null);
    }

    private File pickCacheDirForPcmExport(File root, Bundle req) throws Exception {
        String explicit = req.getString("cacheDirName", "");
        if (explicit != null && explicit.trim().length() > 0) {
            File f = resolveReaderCacheFile(root, explicit.trim());
            if (f.exists() && f.isDirectory()) return f;
            throw new RuntimeException("指定的 cacheDirName 不存在或不是目录: " + explicit);
        }

        String contentHash = req.getString("contentHash", "");
        String bookName = req.getString("bookName", "");
        String chapterTitle = req.getString("chapterTitle", "");
        int chapterIndex = req.getInt("chapterIndex", -1);

        boolean hasStrongKey =
                (contentHash != null && contentHash.trim().length() > 0)
                        || (bookName != null && bookName.trim().length() > 0)
                        || (chapterTitle != null && chapterTitle.trim().length() > 0)
                        || chapterIndex >= 0;

        if (!hasStrongKey) {
            throw new RuntimeException("EXPORT_READER_AUDIO_CACHE 缺少 contentHash/bookName/chapterTitle/chapterIndex/cacheDirName，拒绝盲选旧缓存，避免混入其他书或旧章节");
        }

        ArrayList<File> candidates = new ArrayList<>();
        collectCandidateDirs(root, candidates, 0, 5);

        File best = null;
        int bestScore = -1;
        long bestModified = -1L;

        for (File c : candidates) {
            if (isUnderExports(root, c)) continue;

            int score = scoreCacheDirForRequest(c, req);
            if (score < 0) continue;

            long modified = lastModifiedDeep(c);
            if (score > bestScore || (score == bestScore && modified > bestModified)) {
                best = c;
                bestScore = score;
                bestModified = modified;
            }
        }

        if (best == null) {
            throw new RuntimeException("没有找到匹配当前书/章节的 PCM 缓存目录，拒绝导出旧缓存。contentHash="
                    + contentHash + ", bookName=" + bookName + ", chapterTitle=" + chapterTitle + ", chapterIndex=" + chapterIndex);
        }

        return best;
    }

    private int scoreCacheDirForRequest(File dir, Bundle req) {
        try {
            String contentHash = req.getString("contentHash", "");
            String bookName = req.getString("bookName", "");
            String chapterTitle = req.getString("chapterTitle", "");
            int chapterIndex = req.getInt("chapterIndex", -1);

            int score = 0;
            String dirName = dir.getName();

            JSONObject m = readOptionalManifest(dir);

            if (contentHash != null && contentHash.trim().length() > 0) {
                String h = contentHash.trim();
                if (dirName.contains(h)) score += 50;

                if (m != null) {
                    String mh = m.optString("contentHash", "");
                    if (mh.length() > 0) {
                        if (!h.equals(mh)) return -1;
                        score += 100;
                    }
                }
            }

            if (chapterIndex >= 0 && m != null && m.has("chapterIndex")) {
                int got = m.optInt("chapterIndex", Integer.MIN_VALUE);
                if (got != chapterIndex) return -1;
                score += 30;
            }

            if (bookName != null && bookName.trim().length() > 0 && m != null) {
                String got = m.optString("bookName", "");
                if (got.length() > 0) {
                    if (!bookName.trim().equals(got)) return -1;
                    score += 20;
                }
            }

            if (chapterTitle != null && chapterTitle.trim().length() > 0 && m != null) {
                String got = m.optString("chapterTitle", "");
                if (got.length() > 0) {
                    if (!chapterTitle.trim().equals(got)) return -1;
                    score += 20;
                }
            }

            if (score == 0) {
                return -1;
            }

            return score;
        } catch (Throwable e) {
            return -1;
        }
    }

    private JSONObject readOptionalManifest(File dir) {
        try {
            File m = new File(dir, "manifest.json");
            if (m.exists() && m.isFile()) {
                return new JSONObject(readText(m, 1024 * 1024));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean hasPcmLikeFiles(File dir) {
        ArrayList<File> files = new ArrayList<>();
        collectPcmCandidateFiles(dir, files);
        return !files.isEmpty();
    }

    private JSONArray buildPcmItems(File root, File dir) throws Exception {
        ArrayList<File> files = new ArrayList<>();
        collectPcmCandidateFiles(dir, files);

        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long ma = a.lastModified();
                long mb = b.lastModified();
                if (ma != mb) return Long.compare(ma, mb);

                int ia = leadingIndex(a.getName());
                int ib = leadingIndex(b.getName());
                if (ia != ib) return Integer.compare(ia, ib);

                return a.getName().compareTo(b.getName());
            }
        });

        JSONArray arr = new JSONArray();

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            Uri uri = uriForFile(root, f);
            String rel = relativePath(root, f);

            JSONObject item = new JSONObject();
            item.put("seq", i);
            item.put("file", rel);
            item.put("fileName", f.getName());
            item.put("uri", uri.toString());
            item.put("length", f.length());
            item.put("lastModified", f.lastModified());

            arr.put(item);
        }

        return arr;
    }

    private void collectPcmCandidateFiles(File dir, ArrayList<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                if (!"exports".equals(f.getName())) {
                    collectPcmCandidateFiles(f, out);
                }
                continue;
            }

            String n = f.getName().toLowerCase();

            if (n.equals("manifest.json")) continue;
            if (n.startsWith("reader_audio_cache_export_")) continue;
            if (n.endsWith(".json") || n.endsWith(".tmp") || n.endsWith(".lock")) continue;

            // J.TTS 端只导出 PCM 分片，不导出 J.TTS 自己拼出来的整章 WAV/MP3/AAC。
            if (n.endsWith(".wav") || n.endsWith(".mp3") || n.endsWith(".m4a")
                    || n.endsWith(".aac") || n.endsWith(".ogg")) {
                continue;
            }

            if (f.length() <= 128) continue;

            // 当前缓存分片可能没有扩展名；也兼容 .pcm/.raw。
            if (n.endsWith(".pcm") || n.endsWith(".raw") || n.indexOf('.') < 0) {
                out.add(f);
            }
        }
    }

    private boolean isUnderExports(File root, File f) {
        try {
            File exportDir = new File(root, "exports").getCanonicalFile();
            File target = f.getCanonicalFile();
            String ep = exportDir.getAbsolutePath();
            String tp = target.getAbsolutePath();
            return tp.equals(ep) || tp.startsWith(ep + File.separator);
        } catch (Throwable e) {
            return false;
        }
    }

    private String relativePath(File root, File file) throws Exception {
        File r = root.getCanonicalFile();
        File f = file.getCanonicalFile();
        ensureUnderReaderCacheRoot(r, f);

        String rootPath = r.getAbsolutePath();
        String filePath = f.getAbsolutePath();

        if (filePath.equals(rootPath)) return "";
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private void grantReadUri(Bundle req, Uri uri) {
        try {
            String callerPackage = req.getString(EXTRA_CALLER_PACKAGE, "");
            if (callerPackage == null || callerPackage.trim().length() == 0) {
                callerPackage = req.getString("readerPackage", "");
            }
            if (callerPackage == null || callerPackage.trim().length() == 0) {
                callerPackage = req.getString("packageName", "");
            }
            if (callerPackage != null && callerPackage.trim().length() > 0) {
                grantUriPermission(callerPackage.trim(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Throwable t) {
            Log.w(TAG, "grant read uri failed: " + uri, t);
        }
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
