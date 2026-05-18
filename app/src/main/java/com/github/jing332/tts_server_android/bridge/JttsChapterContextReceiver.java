package com.github.jing332.tts_server_android.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class JttsChapterContextReceiver extends BroadcastReceiver {
    private static final String TAG = "JttsChapterContextReceiver";
    public static final String ACTION_IMPORT_CHAPTER_CONTEXT = "com.jtts.action.IMPORT_CHAPTER_CONTEXT";
    public static final String ACTION_IMPORT_CHAPTER_CONTEXT_RESULT = "com.jtts.action.IMPORT_CHAPTER_CONTEXT_RESULT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_IMPORT_CHAPTER_CONTEXT.equals(intent.getAction())) return;

        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bundle req = intent.getExtras() == null ? new Bundle() : intent.getExtras();
                try {
                    writeDebug(context, req, intent, "received", null, null, "");

                    Uri uri = intent.getData();
                    String uriText = req.getString("chapterContextUri", "");
                    if ((uri == null) && uriText != null && uriText.trim().length() > 0) {
                        uri = Uri.parse(uriText.trim());
                    }

                    if (uri == null) {
                        throw new RuntimeException("chapterContextUri 为空，无法导入整章上下文");
                    }

                    String raw = readTextFromUri(context, uri);
                    JSONObject chapter = new JSONObject(raw);

                    File ext = context.getExternalFilesDir(null);
                    if (ext == null) throw new RuntimeException("getExternalFilesDir(null) 返回 null");
                    if (!ext.exists()) ext.mkdirs();

                    File pluginDir = new File(ext, "plugins/mingwuyan_2_94_noweb_marker");
                    if (!pluginDir.exists()) pluginDir.mkdirs();

                    writeChapterFiles(ext, chapter, "chapterContextBroadcast");

                    mirrorChapterContextToAllPluginDirs(ext, chapter, "chapterContextBroadcast");
                    writeChapterFiles(pluginDir, chapter, "chapterContextBroadcast");

                    writeDebug(context, req, intent, "done", chapter, "chapterContextBroadcast",
                            "written extRoot=" + ext.getAbsolutePath() + " pluginDir=" + pluginDir.getAbsolutePath());

                    sendResult(context, intent, "done", 100, null);
                    Log.i(TAG, "chapter context broadcast imported book=" + chapter.optString("bookName", "")
                            + " chapter=" + chapter.optString("chapterTitle", ""));
                } catch (Throwable t) {
                    Log.e(TAG, "chapter context broadcast import failed", t);
                    try { writeDebug(context, req, intent, "failed", null, "", String.valueOf(t)); } catch (Throwable ignored) {}
                    sendResult(context, intent, "failed", -1, String.valueOf(t));
                } finally {
                    pending.finish();
                }
            }
        }, "jtts-context-broadcast").start();
    }

    private String readTextFromUri(Context context, Uri uri) throws Exception {
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) throw new RuntimeException("openInputStream 返回 null: " + uri);
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
                    writeChapterFiles(dir, chapter, source);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "mirrorChapterContextToAllPluginDirs failed", t);
        }
    }


    private void writeChapterFiles(File dir, JSONObject chapter, String source) throws Exception {
        if (!dir.exists()) dir.mkdirs();

        String bookName = chapter.optString("bookName",
                chapter.optString("book",
                        chapter.optString("bookTitle",
                                chapter.optString("title", ""))));

        String chapterTitle = chapter.optString("chapterTitle", "");
        String chapterContent = chapter.optString("chapterContent", "");

        writeText(new File(dir, "jread_current_chapter.json"), chapter.toString(2));

        if (bookName != null && bookName.trim().length() > 0) {
            writeText(new File(dir, "cunfang.txt"), bookName.trim());
        }

        JSONObject meta = new JSONObject();
        meta.put("source", source);
        meta.put("sessionId", chapter.optString("sessionId", ""));
        meta.put("bookName", bookName);
        meta.put("book", bookName);
        meta.put("bookTitle", bookName);
        meta.put("title", bookName);
        meta.put("chapterTitle", chapterTitle);
        meta.put("chapterIndex", chapter.opt("chapterIndex"));
        meta.put("contentHash", chapter.optString("contentHash", ""));
        meta.put("chapterContentLength", chapterContent.length());
        meta.put("updatedAt", System.currentTimeMillis());

        writeText(new File(dir, "cache_book_context_meta.json"), meta.toString(2));
    }

    private void writeDebug(Context context, Bundle req, Intent intent, String status, JSONObject chapter, String source, String message) {
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext == null) return;
            if (!ext.exists()) ext.mkdirs();

            JSONObject debug = new JSONObject();
            debug.put("receiver", "JttsChapterContextReceiver");
            debug.put("method", "importChapterContextBroadcast");
            debug.put("status", status);
            debug.put("action", intent == null ? "" : intent.getAction());
            debug.put("requestId", req == null ? "" : req.getString("requestId", ""));
            debug.put("sessionId", req == null ? "" : req.getString("sessionId", ""));
            debug.put("contentHash", req == null ? "" : req.getString("contentHash", ""));
            debug.put("chapterContextUriExtra", req == null ? "" : req.getString("chapterContextUri", ""));
            debug.put("dataUri", intent != null && intent.getData() != null ? intent.getData().toString() : "");
            debug.put("source", source == null ? "" : source);
            debug.put("message", message == null ? "" : message);
            debug.put("externalDir", ext.getAbsolutePath());
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

            writeText(new File(ext, "jtts_chapter_context_broadcast_debug.txt"), debug.toString(2));
        } catch (Throwable t) {
            Log.w(TAG, "write debug failed", t);
        }
    }

    private void sendResult(Context context, Intent src, String status, int progress, String error) {
        try {
            Intent out = new Intent(ACTION_IMPORT_CHAPTER_CONTEXT_RESULT);
            out.putExtra("status", status);
            out.putExtra("progress", progress);
            out.putExtra("requestId", src.getStringExtra("requestId"));
            out.putExtra("method", "importChapterContext");
            if (error != null) out.putExtra("error", error);

            String caller = src.getStringExtra("callerPackage");
            if (caller != null && caller.length() > 0) out.setPackage(caller);

            context.sendBroadcast(out);
        } catch (Throwable t) {
            Log.w(TAG, "send result failed", t);
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
}
