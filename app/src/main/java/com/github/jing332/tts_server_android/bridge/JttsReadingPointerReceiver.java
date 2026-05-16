package com.github.jing332.tts_server_android.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class JttsReadingPointerReceiver extends BroadcastReceiver {
    private static final String TAG = "JttsReadingPointerReceiver";
    public static final String ACTION_IMPORT_READING_POINTER = "com.jtts.action.IMPORT_READING_POINTER";
    public static final String ACTION_IMPORT_READING_POINTER_RESULT = "com.jtts.action.IMPORT_READING_POINTER_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_IMPORT_READING_POINTER.equals(intent.getAction())) return;

        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject ptr = buildPointer(intent);
                    File dir = findBestDataDir(context);
                    if (!dir.exists()) dir.mkdirs();

                    File out = new File(dir, "jread_current_pointer.json");
                    writeText(out, ptr.toString(2));

                    // 同时写一份到 external files 根目录，方便 Termux / 文件管理器确认广播是否真的生效。
                    try {
                        File ext = context.getExternalFilesDir(null);
                        if (ext != null) {
                            if (!ext.exists()) ext.mkdirs();

                            File mirror = new File(ext, "jread_current_pointer.json");
                            writeText(mirror, ptr.toString(2));

                            JSONObject debug = new JSONObject();
                            debug.put("receiver", "JttsReadingPointerReceiver");
                            debug.put("status", "done");
                            debug.put("action", intent.getAction());
                            debug.put("requestId", intent.getStringExtra("requestId"));
                            debug.put("sessionId", ptr.optString("sessionId", ""));
                            debug.put("contentHash", ptr.optString("contentHash", ""));
                            debug.put("currentText", ptr.optString("currentText", ""));
                            debug.put("startOffset", ptr.optInt("startOffset", -1));
                            debug.put("endOffset", ptr.optInt("endOffset", -1));
                            debug.put("chapterIndex", ptr.optInt("chapterIndex", -1));
                            debug.put("ruleDir", dir.getAbsolutePath());
                            debug.put("externalDir", ext.getAbsolutePath());
                            debug.put("updatedAt", System.currentTimeMillis());

                            writeText(new File(ext, "jtts_pointer_receiver_debug.txt"), debug.toString(2));
                        }
                    } catch (Throwable mirrorError) {
                        Log.w(TAG, "write external pointer mirror failed", mirrorError);
                    }

                    sendResult(context, intent, "done", 100, null);
                    Log.i(TAG, "pointer broadcast imported dir=" + dir.getAbsolutePath()
                            + " start=" + ptr.optInt("startOffset", -1)
                            + " end=" + ptr.optInt("endOffset", -1)
                            + " len=" + ptr.optString("currentText", "").length());
                } catch (Throwable t) {
                    Log.e(TAG, "pointer broadcast import failed", t);
                    sendResult(context, intent, "failed", -1, String.valueOf(t));
                } finally {
                    pending.finish();
                }
            }
        }, "jtts-pointer-broadcast").start();
    }

    private JSONObject buildPointer(Intent intent) throws Exception {
        Bundle req = intent.getExtras() == null ? new Bundle() : intent.getExtras();

        String pointerJson = req.getString("pointerJson", "");
        JSONObject ptr;
        if (pointerJson != null && pointerJson.trim().length() > 0) {
            ptr = new JSONObject(pointerJson);
        } else {
            ptr = new JSONObject();
            ptr.put("type", "current_pointer");
            ptr.put("requestId", req.getString("requestId", ""));
            ptr.put("sessionId", req.getString("sessionId", ""));
            ptr.put("contentHash", req.getString("contentHash", ""));
            ptr.put("segmentId", req.getString("segmentId", req.getString("utteranceId", "")));
            ptr.put("utteranceId", req.getString("utteranceId", req.getString("segmentId", "")));
            ptr.put("currentText", req.getString("currentText", ""));
            ptr.put("startOffset", req.containsKey("startOffset") ? req.getInt("startOffset", -1) : -1);
            ptr.put("endOffset", req.containsKey("endOffset") ? req.getInt("endOffset", -1) : -1);
            ptr.put("chapterIndex", req.containsKey("chapterIndex") ? req.getInt("chapterIndex", -1) : -1);
            ptr.put("updatedAt", System.currentTimeMillis());
        }

        if (!ptr.has("type")) ptr.put("type", "current_pointer");
        if (!ptr.has("updatedAt")) ptr.put("updatedAt", System.currentTimeMillis());
        return ptr;
    }

    private static final String[] KNOWN_FILES = new String[]{
            "jread_current_chapter.json",
            "cache_book_context_meta.json",
            "cunfang.txt",
            "jread_current_pointer.json",
            "characterRecords.json",
            "dialog_cache.json",
            "nameKeyIndex.txt",
            "aliasKeyIndex.txt",
            "fayinren_emotion_summary.json"
    };

    private File findBestDataDir(Context context) {
        File best = null;
        int bestScore = -1;
        long bestTime = 0L;

        File ext = context.getExternalFilesDir(null);
        File files = context.getFilesDir();

        File[] roots = new File[]{ext, files};
        for (int i = 0; i < roots.length; i++) {
            File root = roots[i];
            if (root == null || !root.exists()) continue;
            SearchResult r = searchDir(root, 0, 7);
            if (r != null && (r.score > bestScore || (r.score == bestScore && r.lastModified > bestTime))) {
                best = r.dir;
                bestScore = r.score;
                bestTime = r.lastModified;
            }
        }

        if (best != null) return best;
        if (ext != null) return ext;
        return files;
    }

    private SearchResult searchDir(File dir, int depth, int maxDepth) {
        if (dir == null || !dir.exists() || !dir.isDirectory() || depth > maxDepth) return null;

        int score = scoreDir(dir);
        long lm = dir.lastModified();
        SearchResult best = score > 0 ? new SearchResult(dir, score, lm) : null;

        File[] list = dir.listFiles();
        if (list == null) return best;

        for (File f : list) {
            if (!f.isDirectory()) continue;
            SearchResult child = searchDir(f, depth + 1, maxDepth);
            if (child == null) continue;
            if (best == null || child.score > best.score ||
                    (child.score == best.score && child.lastModified > best.lastModified)) {
                best = child;
            }
        }
        return best;
    }

    private int scoreDir(File dir) {
        int s = 0;
        long lm = dir.lastModified();
        for (int i = 0; i < KNOWN_FILES.length; i++) {
            File f = new File(dir, KNOWN_FILES[i]);
            if (f.exists() && f.isFile()) {
                s += (KNOWN_FILES[i].equals("jread_current_chapter.json") ? 100 : 10);
                if (f.lastModified() > lm) lm = f.lastModified();
            }
        }
        return s;
    }

    private static class SearchResult {
        final File dir;
        final int score;
        final long lastModified;
        SearchResult(File dir, int score, long lastModified) {
            this.dir = dir;
            this.score = score;
            this.lastModified = lastModified;
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
        if (!tmp.renameTo(f)) throw new RuntimeException("rename failed: " + tmp + " -> " + f);
    }

    private void sendResult(Context context, Intent src, String status, int progress, String error) {
        try {
            Intent out = new Intent(ACTION_IMPORT_READING_POINTER_RESULT);
            out.putExtra("status", status);
            out.putExtra("progress", progress);
            out.putExtra("requestId", src.getStringExtra("requestId"));
            out.putExtra("method", "importReadingPointer");
            if (error != null) out.putExtra("error", error);

            String caller = src.getStringExtra("callerPackage");
            if (caller != null && caller.length() > 0) out.setPackage(caller);

            context.sendBroadcast(out);
        } catch (Throwable t) {
            Log.w(TAG, "send result failed", t);
        }
    }
}
