
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

    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_SESSION_ID = "sessionId";
    public static final String EXTRA_CONTENT_HASH = "contentHash";
    public static final String EXTRA_CALLER_PACKAGE = "callerPackage";
    public static final String EXTRA_RESULT_RECEIVER = "resultReceiver";
    public static final String EXTRA_PREFERRED_FORMAT = "preferredFormat";

    private static final int NOTIFICATION_ID = 94011;
    private static final String CHANNEL_ID = "jtts_export_bridge";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground("J.TTS 正在准备整章导出");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_EXPORT.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        Bundle req = intent.getExtras() == null ? new Bundle() : new Bundle(intent.getExtras());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    exportChapter(req);
                } catch (Throwable t) {
                    Log.e(TAG, "export failed", t);
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
        try { executor.shutdownNow(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void exportChapter(Bundle req) throws Exception {
        String requestId = safeFilePart(req.getString(EXTRA_REQUEST_ID, "req_" + System.currentTimeMillis()));
        String wantedSessionId = req.getString(EXTRA_SESSION_ID, "");
        String wantedHash = req.getString(EXTRA_CONTENT_HASH, "");
        String callerPackage = req.getString(EXTRA_CALLER_PACKAGE, "");

        sendResult(req, "running", 0, null, null);

        File chapterFile = findJttsDataFile("jread_current_chapter.json");
        if (chapterFile == null || !chapterFile.exists()) {
            throw new RuntimeException("未找到 jread_current_chapter.json。请先让 J阅读发送整章 marker，并确认 J.TTS 规则已写入缓存。");
        }

        File jttsDataDir = chapterFile.getParentFile();
        JSONObject chapter = new JSONObject(readText(chapterFile));
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
