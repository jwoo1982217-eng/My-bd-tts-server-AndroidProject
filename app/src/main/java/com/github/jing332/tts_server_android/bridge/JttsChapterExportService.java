
package com.github.jing332.tts_server_android.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class JttsChapterExportService extends Service {
    public static final String TAG = "JttsExportBridge";

    public static final String ACTION_EXPORT = "com.jtts.action.EXPORT_CHAPTER_AUDIO";
    public static final String ACTION_RESULT = "com.jtts.action.EXPORT_CHAPTER_AUDIO_RESULT";
    public static final String ACTION_IMPORT_CONTEXT = "com.jtts.action.IMPORT_CHAPTER_CONTEXT";
    public static final String ACTION_IMPORT_CONTEXT_RESULT = "com.jtts.action.IMPORT_CHAPTER_CONTEXT_RESULT";
    public static final String ACTION_IMPORT_READING_POINTER = "com.jtts.action.IMPORT_READING_POINTER";
    public static final String ACTION_IMPORT_READING_POINTER_RESULT = "com.jtts.action.IMPORT_READING_POINTER_RESULT";

    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_CONTENT_HASH = "contentHash";
    public static final String EXTRA_CALLER_PACKAGE = "callerPackage";
    public static final String EXTRA_RESULT_RECEIVER = "resultReceiver";
    public static final String EXTRA_PREFERRED_FORMAT = "preferredFormat";
    public static final String EXTRA_CHAPTER_CONTEXT_URI = "chapterContextUri";
    public static final String EXTRA_METHOD = "method";

    private static final int NOTIFICATION_ID = 94011;
    private static final String CHANNEL_ID = "jtts_export_bridge";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground("J.TTS 正在准备整章导出");
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
        if (intent == null || (!ACTION_EXPORT.equals(intent.getAction()) && !ACTION_IMPORT_CONTEXT.equals(intent.getAction()) && !ACTION_IMPORT_READING_POINTER.equals(intent.getAction()))) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startBridgeForeground(intent.getAction());

        Bundle req = intent.getExtras() == null ? new Bundle() : new Bundle(intent.getExtras());
        try {
            if (intent.getData() != null && !req.containsKey(EXTRA_CHAPTER_CONTEXT_URI)) {
                req.putString(EXTRA_CHAPTER_CONTEXT_URI, intent.getData().toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "copy intent data to chapterContextUri failed: " + t);
        }

        final boolean importContextOnly = ACTION_IMPORT_CONTEXT.equals(intent.getAction());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (ACTION_IMPORT_READING_POINTER.equals(intent.getAction())) {
                        importReadingPointerOnly(req);
                    } else if (importContextOnly) {
                        importChapterContextOnly(req);
                    } else {
                        exportChapter(req);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "export failed", t);
                    if (ACTION_IMPORT_READING_POINTER.equals(intent.getAction())) {
                        sendImportPointerResult(req, "failed", -1, null, String.valueOf(t));
                    } else if (importContextOnly) {
                        try { writeImportContextDebug(req, "failed", null, null, String.valueOf(t)); } catch (Throwable ignored) {}
                        sendImportContextResult(req, "failed", -1, null, String.valueOf(t));
                    } else {
                        sendResult(req, "failed", -1, null, String.valueOf(t));
                    }
                } finally {
                    stopSelf(startId);
                }
            }
        });

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { executor.shutdownNow(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }



    private void importReadingPointerOnly(Bundle req) throws Exception {
        sendImportPointerResult(req, "running", 0, null, null);

        JSONObject ptr;
        String pointerJson = req.getString("pointerJson", "");
        if (pointerJson != null && pointerJson.trim().length() > 0) {
            ptr = new JSONObject(pointerJson);
        } else {
            ptr = new JSONObject();
            ptr.put("type", "current_pointer");
            ptr.put("sessionId", req.getString("sessionId", ""));
            ptr.put("contentHash", req.getString("contentHash", ""));
            ptr.put("currentText", req.getString("currentText", ""));

            if (req.containsKey("startOffset")) {
                ptr.put("startOffset", req.getInt("startOffset", -1));
            } else {
                ptr.put("startOffset", -1);
            }

            if (req.containsKey("endOffset")) {
                ptr.put("endOffset", req.getInt("endOffset", -1));
            } else {
                ptr.put("endOffset", -1);
            }

            if (req.containsKey("chapterIndex")) {
                ptr.put("chapterIndex", req.getInt("chapterIndex", -1));
            } else {
                ptr.put("chapterIndex", -1);
            }

            ptr.put("updatedAt", System.currentTimeMillis());
        }

        if (!ptr.has("type")) ptr.put("type", "current_pointer");
        if (!ptr.has("updatedAt")) ptr.put("updatedAt", System.currentTimeMillis());

        File dataDir = findJttsDataDirForBridge();
        if (dataDir == null) dataDir = getFilesDir();
        if (!dataDir.exists()) dataDir.mkdirs();

        writeText(new File(dataDir, "jread_current_pointer.json"), ptr.toString(2));
        mirrorPointerToAllPluginDirs(ptr);

        JSONObject manifest = new JSONObject();
        manifest.put("method", "importReadingPointer");
        manifest.put("status", "done");
        manifest.put("requestId", req.getString(EXTRA_REQUEST_ID, "ptr_" + System.currentTimeMillis()));
        manifest.put("sessionId", ptr.optString("sessionId", ""));
        manifest.put("contentHash", ptr.optString("contentHash", ""));
        manifest.put("chapterIndex", ptr.optInt("chapterIndex", -1));
        manifest.put("startOffset", ptr.optInt("startOffset", -1));
        manifest.put("endOffset", ptr.optInt("endOffset", -1));
        manifest.put("currentTextLength", ptr.optString("currentText", "").length());
        manifest.put("updatedAt", System.currentTimeMillis());

        Log.i(TAG, "当前位置导入完成 sessionId=" + ptr.optString("sessionId", "") +
                " start=" + ptr.optInt("startOffset", -1) +
                " end=" + ptr.optInt("endOffset", -1) +
                " textLen=" + ptr.optString("currentText", "").length());

        sendImportPointerResult(req, "done", 100, manifest, null);
    }

    private void sendImportPointerResult(Bundle req, String status, int progress, JSONObject manifest, String error) {
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

            Intent out = new Intent(ACTION_IMPORT_READING_POINTER_RESULT);
            out.putExtras(b);
            out.putExtra(EXTRA_REQUEST_ID, req.getString(EXTRA_REQUEST_ID, ""));
            out.putExtra("method", "importReadingPointer");

            String callerPackage = req.getString(EXTRA_CALLER_PACKAGE, "");
            if (callerPackage != null && callerPackage.length() > 0) out.setPackage(callerPackage);

            sendBroadcast(out);
        } catch (Throwable t) {
            Log.w(TAG, "sendImportPointerResult failed", t);
        }
    }



    private void mirrorImportedChapterContextToExternal(Bundle req, JSONObject chapter, String source) {
        try {
            File extRoot = getExternalFilesDir(null);
            if (extRoot == null) {
                writeImportContextDebug(req, "failed", chapter, source, "getExternalFilesDir(null) 返回 null");
                return;
            }

            if (!extRoot.exists()) extRoot.mkdirs();

            File pluginDir = new File(extRoot, "plugins/mingwuyan_2_94_noweb_marker");
            if (!pluginDir.exists()) pluginDir.mkdirs();

            writeChapterContextFilesToDir(extRoot, chapter, source);

            mirrorChapterContextToAllPluginDirs(extRoot, chapter, source);
            writeChapterContextFilesToDir(pluginDir, chapter, source);

            writeImportContextDebug(req, "done", chapter, source,
                    "written extRoot=" + extRoot.getAbsolutePath() +
                            " pluginDir=" + pluginDir.getAbsolutePath());
        } catch (Throwable t) {
            try { writeImportContextDebug(req, "failed", chapter, source, String.valueOf(t)); } catch (Throwable ignored) {}
            Log.w(TAG, "mirrorImportedChapterContextToExternal failed", t);
        }
    }


    private void mirrorChapterContextToAllPluginDirs(File extRoot, JSONObject chapter, String source) {
        try {
            if (extRoot == null) return;

            File pluginRoot = new File(extRoot, "plugins");
            if (!pluginRoot.exists()) return;

            File[] list = pluginRoot.listFiles();
            if (list == null) return;

            for (int i = 0; i < list.length; i++) {
                File dir = list[i];
                if (dir != null && dir.isDirectory()) {
                    writeChapterContextFilesToDir(dir, chapter, source);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "mirrorChapterContextToAllPluginDirs failed", t);
        }
    }



    private void mirrorPointerToAllPluginDirs(JSONObject ptr) {
        try {
            File extRoot = getExternalFilesDir(null);
            if (extRoot == null) return;

            if (!extRoot.exists()) extRoot.mkdirs();

            writeText(new File(extRoot, "jread_current_pointer.json"), ptr.toString(2));

            File pluginRoot = new File(extRoot, "plugins");
            if (!pluginRoot.exists()) return;

            File[] list = pluginRoot.listFiles();
            if (list == null) return;

            for (int i = 0; i < list.length; i++) {
                File dir = list[i];
                if (dir != null && dir.isDirectory()) {
                    writeText(new File(dir, "jread_current_pointer.json"), ptr.toString(2));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "mirrorPointerToAllPluginDirs failed", t);
        }
    }


    private void writeChapterContextFilesToDir(File dir, JSONObject chapter, String source) throws Exception {
        if (dir == null) return;
        if (!dir.exists()) dir.mkdirs();

        String bookName = chapter.optString("bookName",
                chapter.optString("book",
                        chapter.optString("bookTitle",
                                chapter.optString("title", ""))));

        String chapterTitle = chapter.optString("chapterTitle", "");
        String sessionId = chapter.optString("sessionId", "");
        String contentHash = chapter.optString("contentHash", "");
        String chapterContent = chapter.optString("chapterContent", "");

        writeText(new File(dir, "jread_current_chapter.json"), chapter.toString(2));

        if (bookName != null && bookName.trim().length() > 0) {
            writeText(new File(dir, "cunfang.txt"), bookName.trim());
        }

        JSONObject meta = new JSONObject();
        meta.put("source", source == null ? "chapterContextUri" : source);
        meta.put("sessionId", sessionId);
        meta.put("bookName", bookName);
        meta.put("book", bookName);
        meta.put("bookTitle", bookName);
        meta.put("title", bookName);
        meta.put("chapterTitle", chapterTitle);
        meta.put("chapterIndex", chapter.opt("chapterIndex"));
        meta.put("contentHash", contentHash);
        meta.put("chapterContentLength", chapterContent.length());
        meta.put("updatedAt", System.currentTimeMillis());

        writeText(new File(dir, "cache_book_context_meta.json"), meta.toString(2));
    }

    private void writeImportContextDebug(Bundle req, String status, JSONObject chapter, String source, String message) {
        try {
            File extRoot = getExternalFilesDir(null);
            if (extRoot == null) return;
            if (!extRoot.exists()) extRoot.mkdirs();

            JSONObject debug = new JSONObject();
            debug.put("receiver", "JttsChapterExportService");
            debug.put("method", "importChapterContext");
            debug.put("status", status);
            debug.put("requestId", req == null ? "" : req.getString(EXTRA_REQUEST_ID, ""));
            debug.put("sessionId", req == null ? "" : req.getString(EXTRA_SESSION_ID, ""));
            debug.put("contentHash", req == null ? "" : req.getString(EXTRA_CONTENT_HASH, ""));
            debug.put("chapterContextUri", req == null ? "" : req.getString(EXTRA_CHAPTER_CONTEXT_URI, ""));
            debug.put("source", source == null ? "" : source);
            debug.put("message", message == null ? "" : message);
            debug.put("externalDir", extRoot.getAbsolutePath());
            debug.put("updatedAt", System.currentTimeMillis());

            if (chapter != null) {
                debug.put("bookName", chapter.optString("bookName",
                        chapter.optString("book",
                                chapter.optString("bookTitle",
                                        chapter.optString("title", "")))));
                debug.put("chapterTitle", chapter.optString("chapterTitle", ""));
                debug.put("chapterIndex", chapter.opt("chapterIndex"));
                debug.put("chapterContentLength", chapter.optString("chapterContent", "").length());
                debug.put("chapterSessionId", chapter.optString("sessionId", ""));
                debug.put("chapterContentHash", chapter.optString("contentHash", ""));
            }

            writeText(new File(extRoot, "jtts_chapter_context_import_debug.txt"), debug.toString(2));
        } catch (Throwable t) {
            Log.w(TAG, "writeImportContextDebug failed", t);
        }
    }


    private void importChapterContextOnly(Bundle req) throws Exception {
        sendImportContextResult(req, "running", 0, null, null);

        writeImportContextDebug(req, "received", null, null, null);

        ChapterLoadResult loadedChapter = loadChapterFromRequestOrCache(req);
        JSONObject chapter = loadedChapter.chapter;

        mirrorImportedChapterContextToExternal(req, chapter, loadedChapter.source);

        String requestId = req.getString(EXTRA_REQUEST_ID, "import_" + System.currentTimeMillis());
        String sessionId = chapter.optString("sessionId", req.getString(EXTRA_SESSION_ID, ""));
        String bookName = chapter.optString("bookName",
                chapter.optString("book",
                        chapter.optString("bookTitle",
                                chapter.optString("title", ""))));
        String chapterTitle = chapter.optString("chapterTitle", "");
        String contentHash = chapter.optString("contentHash", req.getString(EXTRA_CONTENT_HASH, ""));
        String chapterContent = chapter.optString("chapterContent", "");

        JSONObject manifest = new JSONObject();
        manifest.put("method", "importChapterContext");
        manifest.put("status", "done");
        manifest.put("requestId", requestId);
        manifest.put("sessionId", sessionId);
        manifest.put("bookName", bookName);
        manifest.put("chapterTitle", chapterTitle);
        manifest.put("chapterIndex", chapter.opt("chapterIndex"));
        manifest.put("contentHash", contentHash);
        manifest.put("chapterContentLength", chapterContent.length());
        manifest.put("source", loadedChapter.source);
        manifest.put("updatedAt", System.currentTimeMillis());

        Log.i(TAG, "章节上下文导入完成 source=" + loadedChapter.source +
                " book=" + bookName +
                " chapter=" + chapterTitle +
                " len=" + chapterContent.length());

        sendImportContextResult(req, "done", 100, manifest, null);
    }

    private void sendImportContextResult(Bundle req, String status, int progress, JSONObject manifest, String error) {
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

            Intent out = new Intent(ACTION_IMPORT_CONTEXT_RESULT);
            out.putExtras(b);
            out.putExtra(EXTRA_REQUEST_ID, req.getString(EXTRA_REQUEST_ID, ""));
            out.putExtra("method", "importChapterContext");

            String callerPackage = req.getString(EXTRA_CALLER_PACKAGE, "");
            if (callerPackage != null && callerPackage.length() > 0) out.setPackage(callerPackage);

            sendBroadcast(out);
        } catch (Throwable t) {
            Log.w(TAG, "sendImportContextResult failed", t);
        }
    }


    private void exportChapter(Bundle req) throws Exception {
        String requestId = safeFilePart(req.getString(EXTRA_REQUEST_ID, "req_" + System.currentTimeMillis()));
        String wantedSessionId = req.getString(EXTRA_SESSION_ID, "");
        String wantedHash = req.getString(EXTRA_CONTENT_HASH, "");
        String callerPackage = req.getString(EXTRA_CALLER_PACKAGE, "");

        sendResult(req, "running", 0, null, null);

        ChapterLoadResult loadedChapter = loadChapterFromRequestOrCache(req);
        File jttsDataDir = loadedChapter.dataDir;
        JSONObject chapter = loadedChapter.chapter;
        Log.i(TAG, "章节上下文来源=" + loadedChapter.source);
        String sessionId = chapter.optString("sessionId", "");
        String contentHash = chapter.optString("contentHash", "");
        String bookName = chapter.optString("bookName", chapter.optString("book", ""));
        String chapterTitle = chapter.optString("chapterTitle", "");
        int chapterIndex = chapter.optInt("chapterIndex", -1);
        String chapterContent = chapter.optString("chapterContent", "");

        if (chapterContent.trim().length() == 0) {
            throw new RuntimeException("jread_current_chapter.json 中 chapterContent 为空");
        }

        if (wantedSessionId.length() > 0 && sessionId.length() > 0 && !wantedSessionId.equals(sessionId)) {
            throw new RuntimeException("sessionId 不匹配：request=" + wantedSessionId + " cache=" + sessionId);
        }

        if (wantedHash.length() > 0 && contentHash.length() > 0 && !wantedHash.equals(contentHash)) {
            throw new RuntimeException("contentHash 不匹配：request=" + wantedHash + " cache=" + contentHash);
        }

        File baseDir = new File(getFilesDir(), "jtts_exports/" + requestId);
        File segDir = new File(baseDir, "segments");
        if (!segDir.exists()) segDir.mkdirs();

        ArrayList<Segment> segments = buildSegments(chapterContent, 180);
        if (segments.size() == 0) {
            throw new RuntimeException("章节切分后没有可合成片段");
        }

        Log.i(TAG, "开始整章导出 book=" + bookName + " chapter=" + chapterTitle + " len=" + chapterContent.length() + " segments=" + segments.size());

        TextToSpeech tts = initSelfTts();
        try {
            JSONArray timeline = new JSONArray();
            ArrayList<File> wavFiles = new ArrayList<File>();

            long audioCursorMs = 0L;

            for (int i = 0; i < segments.size(); i++) {
                Segment seg = segments.get(i);

                writePointerForRule(jttsDataDir, sessionId, chapterIndex, seg);

                File outFile = new File(segDir, String.format(Locale.US, "%06d.wav", i));
                synthesizeOne(tts, seg.text, outFile, "jtts_export_" + requestId + "_" + i);

                WavInfo wi = WavInfo.read(outFile);
                long durationMs = wi == null ? 0L : wi.durationMs();

                JSONObject item = new JSONObject();
                item.put("index", i);
                item.put("text", seg.text);
                item.put("startOffset", seg.startOffset);
                item.put("endOffset", seg.endOffset);
                item.put("audioStartMs", audioCursorMs);
                item.put("audioEndMs", audioCursorMs + durationMs);
                item.put("file", "segments/" + outFile.getName());
                timeline.put(item);

                audioCursorMs += durationMs;
                wavFiles.add(outFile);

                int progress = (int) Math.floor(((i + 1) * 100.0) / segments.size());
                sendResult(req, "running", progress, null, null);
            }

            File mergedWav = new File(baseDir, "chapter_audio.wav");
            mergeWavFiles(wavFiles, mergedWav);

            File timelineFile = new File(baseDir, "timeline.json");
            writeText(timelineFile, timeline.toString(2));

            String authority = getPackageName() + ".jtts.export.provider";
            Uri audioUri = Uri.parse("content://" + authority + "/export/" + requestId + "/chapter_audio.wav");
            Uri timelineUri = Uri.parse("content://" + authority + "/export/" + requestId + "/timeline.json");
            Uri manifestUri = Uri.parse("content://" + authority + "/export/" + requestId + "/manifest.json");

            if (callerPackage != null && callerPackage.length() > 0) {
                try {
                    grantUriPermission(callerPackage, audioUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    grantUriPermission(callerPackage, timelineUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    grantUriPermission(callerPackage, manifestUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable t) {
                    Log.w(TAG, "grantUriPermission failed: " + t);
                }
            }

            JSONObject manifest = new JSONObject();
            manifest.put("method", "exportAudiobookChapter");
            manifest.put("requestId", requestId);
            manifest.put("sessionId", sessionId);
            manifest.put("bookName", bookName);
            manifest.put("chapterTitle", chapterTitle);
            manifest.put("chapterIndex", chapterIndex);
            manifest.put("contentHash", contentHash);
            manifest.put("status", "done");
            manifest.put("audioUri", audioUri.toString());
            manifest.put("timelineUri", timelineUri.toString());
            manifest.put("manifestUri", manifestUri.toString());
            manifest.put("format", "wav");
            manifest.put("audioMimeType", "audio/wav");
            manifest.put("durationMs", audioCursorMs);
            manifest.put("segmentCount", segments.size());
            manifest.put("createdAt", System.currentTimeMillis());
            manifest.put("note", "当前版本输出 WAV。若需要 MP3，请在后续版本接入 MP3 编码器。");

            File manifestFile = new File(baseDir, "manifest.json");
            writeText(manifestFile, manifest.toString(2));

            Log.i(TAG, "整章导出完成 audio=" + mergedWav.getAbsolutePath());
            sendResult(req, "done", 100, manifest, null);
        } finally {
            try { tts.shutdown(); } catch (Throwable ignored) {}
        }
    }


    private static class ChapterLoadResult {
        JSONObject chapter;
        File dataDir;
        String source;
    }

    private ChapterLoadResult loadChapterFromRequestOrCache(Bundle req) throws Exception {
        String chapterContextUri = "";
        try {
            chapterContextUri = req.getString(EXTRA_CHAPTER_CONTEXT_URI, "");
        } catch (Throwable ignored) {}

        if (chapterContextUri == null || chapterContextUri.trim().length() == 0) {
            try {
                chapterContextUri = req.getString("chapter_context_uri", "");
            } catch (Throwable ignored) {}
        }

        if (chapterContextUri != null && chapterContextUri.trim().length() > 0) {
            Uri uri = Uri.parse(chapterContextUri.trim());
            String raw = readTextFromUri(uri);
            JSONObject chapter = new JSONObject(raw);

            File dataDir = findJttsDataDirForBridge();
            writeChapterCacheFiles(dataDir, chapter);

            ChapterLoadResult result = new ChapterLoadResult();
            result.chapter = chapter;
            result.dataDir = dataDir;
            result.source = "chapterContextUri";
            return result;
        }

        File chapterFile = findJttsDataFile("jread_current_chapter.json");
        if (chapterFile == null || !chapterFile.exists()) {
            throw new RuntimeException("未找到 jread_current_chapter.json，也没有收到 chapterContextUri。J阅读需要在 EXPORT_CHAPTER_AUDIO 中传入 chapterContextUri。");
        }

        ChapterLoadResult result = new ChapterLoadResult();
        result.chapter = new JSONObject(readText(chapterFile));
        result.dataDir = chapterFile.getParentFile();
        result.source = "jread_current_chapter.json";
        return result;
    }

    private File findJttsDataDirForBridge() {
        try {
            File f = findJttsDataFile("jread_current_chapter.json");
            if (f != null && f.getParentFile() != null) return f.getParentFile();
        } catch (Throwable ignored) {}

        String[] knownFiles = new String[]{
                "jread_current_chapter.json",
                "cache_book_context_meta.json",
                "cunfang.txt",
                "characterRecords.json",
                "fayinren.json",
                "miyue.txt",

                // 2.94 朗读规则实际会生成/使用这些文件；用于定位当前插件数据目录
                "dialog_cache.json",
                "nameKeyIndex.txt",
                "aliasKeyIndex.txt",
                "fayinren_emotion_summary.json"
        };

        for (int i = 0; i < knownFiles.length; i++) {
            try {
                File f = findJttsDataFile(knownFiles[i]);
                if (f != null && f.getParentFile() != null) return f.getParentFile();
            } catch (Throwable ignored) {}
        }

        File dir = getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void writeChapterCacheFiles(File dataDir, JSONObject chapter) throws Exception {
        if (dataDir == null) dataDir = getFilesDir();
        if (!dataDir.exists()) dataDir.mkdirs();

        if (!chapter.has("type")) chapter.put("type", "chapter_context");
        if (!chapter.has("updatedAt")) chapter.put("updatedAt", System.currentTimeMillis());

        String sessionId = chapter.optString("sessionId", "");
        String bookName = chapter.optString("bookName",
                chapter.optString("book",
                        chapter.optString("bookTitle",
                                chapter.optString("title", "")))).trim();

        String chapterTitle = chapter.optString("chapterTitle", "");
        String contentHash = chapter.optString("contentHash", "");
        String chapterContent = chapter.optString("chapterContent", "");

        if (chapterContent.trim().length() == 0) {
            throw new RuntimeException("chapterContextUri 中 chapterContent 为空");
        }

        writeText(new File(dataDir, "jread_current_chapter.json"), chapter.toString(2));

        if (bookName.length() > 0) {
            writeText(new File(dataDir, "cunfang.txt"), bookName);
        }

        JSONObject meta = new JSONObject();
        meta.put("source", "chapterContextUri");
        meta.put("sessionId", sessionId);
        meta.put("bookName", bookName);
        meta.put("book", bookName);
        meta.put("bookTitle", bookName);
        meta.put("title", bookName);
        meta.put("chapterTitle", chapterTitle);
        meta.put("chapterIndex", chapter.opt("chapterIndex"));
        meta.put("contentHash", contentHash);
        meta.put("updatedAt", System.currentTimeMillis());

        writeText(new File(dataDir, "cache_book_context_meta.json"), meta.toString(2));

        Log.i(TAG, "chapterContextUri 写入缓存成功 book=" + bookName +
                " chapter=" + chapterTitle +
                " len=" + chapterContent.length() +
                " hash=" + contentHash);
    }

    private String readTextFromUri(Uri uri) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new RuntimeException("openInputStream 返回 null: " + uri);
        }

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }


    private void writePointerForRule(File jttsDataDir, String sessionId, int chapterIndex, Segment seg) {
        try {
            JSONObject ptr = new JSONObject();
            ptr.put("type", "current_pointer");
            ptr.put("sessionId", sessionId);
            ptr.put("currentText", seg.text);
            ptr.put("startOffset", seg.startOffset);
            ptr.put("endOffset", seg.endOffset);
            ptr.put("chapterIndex", chapterIndex);
            ptr.put("updatedAt", System.currentTimeMillis());
            writeText(new File(jttsDataDir, "jread_current_pointer.json"), ptr.toString(2));
        } catch (Throwable t) {
            Log.w(TAG, "write pointer failed: " + t);
        }
    }

    private TextToSpeech initSelfTts() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final int[] statusHolder = new int[]{TextToSpeech.ERROR};

        TextToSpeech tts = new TextToSpeech(getApplicationContext(), status -> {
            statusHolder[0] = status;
            latch.countDown();
        }, getPackageName());

        boolean ok = latch.await(20, TimeUnit.SECONDS);
        if (!ok || statusHolder[0] != TextToSpeech.SUCCESS) {
            try { tts.shutdown(); } catch (Throwable ignored) {}
            throw new RuntimeException("初始化 J.TTS 自身 TextToSpeech 失败 status=" + statusHolder[0]);
        }

        try { tts.setLanguage(Locale.CHINESE); } catch (Throwable ignored) {}
        return tts;
    }

    private void synthesizeOne(TextToSpeech tts, String text, File outFile, String utteranceId) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final String[] err = new String[]{null};

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {}

            @Override
            public void onDone(String id) {
                if (utteranceId.equals(id)) latch.countDown();
            }

            @Override
            public void onError(String id) {
                if (utteranceId.equals(id)) {
                    err[0] = "onError";
                    latch.countDown();
                }
            }

            @Override
            public void onError(String id, int errorCode) {
                if (utteranceId.equals(id)) {
                    err[0] = "onError code=" + errorCode;
                    latch.countDown();
                }
            }
        });

        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

        int r;
        if (Build.VERSION.SDK_INT >= 21) {
            r = tts.synthesizeToFile(text, params, outFile, utteranceId);
        } else {
            throw new RuntimeException("当前 Android 版本不支持新版 synthesizeToFile");
        }

        if (r != TextToSpeech.SUCCESS) {
            throw new RuntimeException("synthesizeToFile 提交失败 ret=" + r + " text=" + shortText(text));
        }

        boolean ok = latch.await(90, TimeUnit.SECONDS);
        if (!ok) {
            throw new RuntimeException("synthesizeToFile 超时 text=" + shortText(text));
        }
        if (err[0] != null) {
            throw new RuntimeException("synthesizeToFile 失败 " + err[0] + " text=" + shortText(text));
        }
        if (!outFile.exists() || outFile.length() <= 44) {
            throw new RuntimeException("合成文件无效：" + outFile.getAbsolutePath());
        }
    }

    private ArrayList<Segment> buildSegments(String content, int maxLen) {
        ArrayList<Segment> list = new ArrayList<Segment>();
        int start = 0;
        int lastBreak = -1;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            boolean punctuation = "。！？!?；;\n\r".indexOf(c) >= 0;
            if (punctuation) lastBreak = i + 1;

            if ((i - start + 1) >= maxLen || punctuation) {
                int end = punctuation ? i + 1 : (lastBreak > start ? lastBreak : i + 1);
                addSegment(list, content, start, end);
                start = end;
                lastBreak = -1;
            }
        }

        if (start < content.length()) {
            addSegment(list, content, start, content.length());
        }

        return list;
    }

    private void addSegment(ArrayList<Segment> list, String content, int start, int end) {
        if (start < 0) start = 0;
        if (end > content.length()) end = content.length();
        if (end <= start) return;

        String text = content.substring(start, end);
        int leftTrim = 0;
        while (leftTrim < text.length() && Character.isWhitespace(text.charAt(leftTrim))) leftTrim++;
        int rightTrim = text.length();
        while (rightTrim > leftTrim && Character.isWhitespace(text.charAt(rightTrim - 1))) rightTrim--;

        if (rightTrim <= leftTrim) return;

        Segment seg = new Segment();
        seg.startOffset = start + leftTrim;
        seg.endOffset = start + rightTrim;
        seg.text = text.substring(leftTrim, rightTrim);
        list.add(seg);
    }

    private static class Segment {
        int startOffset;
        int endOffset;
        String text;
    }

    private File findJttsDataFile(String name) {
        ArrayList<File> roots = new ArrayList<File>();
        roots.add(getFilesDir());

        try {
            File dataRoot = new File(getApplicationInfo().dataDir);
            roots.add(dataRoot);
        } catch (Throwable ignored) {}

        try {
            File ext = getExternalFilesDir(null);
            if (ext != null) roots.add(ext);
        } catch (Throwable ignored) {}

        for (File root : roots) {
            File direct = new File(root, name);
            if (direct.exists()) return direct;
        }

        for (File root : roots) {
            File found = findFileRecursive(root, name, 6);
            if (found != null) return found;
        }

        return null;
    }

    private File findFileRecursive(File dir, String name, int depth) {
        if (dir == null || depth < 0 || !dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (f.isFile() && name.equals(f.getName())) return f;
        }

        for (File f : files) {
            if (f.isDirectory()) {
                File found = findFileRecursive(f, name, depth - 1);
                if (found != null) return found;
            }
        }

        return null;
    }

    private void mergeWavFiles(ArrayList<File> wavFiles, File outFile) throws Exception {
        if (wavFiles == null || wavFiles.size() == 0) {
            throw new RuntimeException("没有可合并的 WAV 片段");
        }

        WavInfo first = WavInfo.read(wavFiles.get(0));
        if (first == null) throw new RuntimeException("第一个片段不是标准 WAV：" + wavFiles.get(0));

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();

        for (File f : wavFiles) {
            WavInfo info = WavInfo.read(f);
            if (info == null) throw new RuntimeException("片段不是标准 WAV：" + f.getName());
            if (!info.compatibleWith(first)) {
                throw new RuntimeException("WAV 格式不一致，无法直接合并：" + f.getName());
            }

            FileInputStream in = new FileInputStream(f);
            try {
                long skipped = in.skip(info.dataOffset);
                if (skipped < info.dataOffset) throw new RuntimeException("跳过 WAV 头失败：" + f.getName());
                byte[] buf = new byte[8192];
                int n;
                long remain = info.dataSize;
                while (remain > 0 && (n = in.read(buf, 0, (int)Math.min(buf.length, remain))) > 0) {
                    pcm.write(buf, 0, n);
                    remain -= n;
                }
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }

        byte[] pcmBytes = pcm.toByteArray();
        FileOutputStream out = new FileOutputStream(outFile);
        try {
            writeWavHeader(out, first.sampleRate, first.channels, first.bitsPerSample, pcmBytes.length);
            out.write(pcmBytes);
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private static class WavInfo {
        int channels;
        int sampleRate;
        int bitsPerSample;
        int audioFormat;
        int dataOffset;
        int dataSize;

        long durationMs() {
            int bytesPerSecond = sampleRate * channels * bitsPerSample / 8;
            if (bytesPerSecond <= 0) return 0;
            return (long) dataSize * 1000L / bytesPerSecond;
        }

        boolean compatibleWith(WavInfo other) {
            return other != null &&
                    channels == other.channels &&
                    sampleRate == other.sampleRate &&
                    bitsPerSample == other.bitsPerSample &&
                    audioFormat == other.audioFormat;
        }

        static WavInfo read(File f) {
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] all;
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    all = bos.toByteArray();
                } finally {
                    try { in.close(); } catch (Throwable ignored) {}
                }

                if (all.length < 44) return null;
                if (!ascii(all, 0, 4).equals("RIFF")) return null;
                if (!ascii(all, 8, 4).equals("WAVE")) return null;

                int pos = 12;
                WavInfo wi = new WavInfo();

                while (pos + 8 <= all.length) {
                    String id = ascii(all, pos, 4);
                    int size = leInt(all, pos + 4);
                    int dataStart = pos + 8;

                    if ("fmt ".equals(id)) {
                        wi.audioFormat = leShort(all, dataStart);
                        wi.channels = leShort(all, dataStart + 2);
                        wi.sampleRate = leInt(all, dataStart + 4);
                        wi.bitsPerSample = leShort(all, dataStart + 14);
                    } else if ("data".equals(id)) {
                        wi.dataOffset = dataStart;
                        wi.dataSize = size;
                        break;
                    }

                    pos = dataStart + size;
                    if ((pos & 1) == 1) pos++;
                }

                if (wi.dataOffset <= 0 || wi.dataSize <= 0) return null;
                if (wi.channels <= 0 || wi.sampleRate <= 0 || wi.bitsPerSample <= 0) return null;
                return wi;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.US_ASCII);
    }

    private static int leShort(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xff) |
                ((b[off + 1] & 0xff) << 8) |
                ((b[off + 2] & 0xff) << 16) |
                ((b[off + 3] & 0xff) << 24);
    }

    private void writeWavHeader(FileOutputStream out, int sampleRate, int channels, int bitsPerSample, int dataSize) throws Exception {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int chunkSize = 36 + dataSize;

        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLeInt(out, chunkSize);
        out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeLeInt(out, 16);
        writeLeShort(out, 1);
        writeLeShort(out, channels);
        writeLeInt(out, sampleRate);
        writeLeInt(out, byteRate);
        writeLeShort(out, blockAlign);
        writeLeShort(out, bitsPerSample);
        out.write("data".getBytes(StandardCharsets.US_ASCII));
        writeLeInt(out, dataSize);
    }

    private void writeLeShort(FileOutputStream out, int v) throws Exception {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    private void writeLeInt(FileOutputStream out, int v) throws Exception {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
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

            Intent out = new Intent(ACTION_RESULT);
            out.putExtras(b);
            out.putExtra(EXTRA_REQUEST_ID, req.getString(EXTRA_REQUEST_ID, ""));
            String callerPackage = req.getString(EXTRA_CALLER_PACKAGE, "");
            if (callerPackage != null && callerPackage.length() > 0) out.setPackage(callerPackage);
            sendBroadcast(out);
        } catch (Throwable t) {
            Log.w(TAG, "sendResult failed", t);
        }
    }

    private void startAsForeground(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "J.TTS 整章导出", NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }

            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            PendingIntent pi = null;
            if (launch != null) {
                int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
                pi = PendingIntent.getActivity(this, 0, launch, flags);
            }

            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ?
                    new Notification.Builder(this, CHANNEL_ID) :
                    new Notification.Builder(this);

            b.setContentTitle("J.TTS 整章导出")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(true);

            if (pi != null) b.setContentIntent(pi);

            startForeground(NOTIFICATION_ID, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed", t);
        }
    }

    private static String safeFilePart(String s) {
        if (s == null) s = "";
        s = s.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (s.length() > 80) s = s.substring(0, 80);
        if (s.length() == 0) s = "empty";
        return s;
    }

    private static String shortText(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void writeText(File f, String s) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(String.valueOf(s).getBytes(StandardCharsets.UTF_8));
        } finally {
            try { out.close(); } catch (Throwable ignored) {}
        }
    }
}
