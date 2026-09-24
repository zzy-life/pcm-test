package com.hr.digitalhuman.ui.display;

import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.hr.digitalhuman.debug.DebugLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * 下载简历 PDF，并用集成的 android-pdf-viewer 全屏打开；失败时回退系统阅读器。
 */
public final class ResumePdfViewer {

    private static final String TAG = "ResumePdfViewer";

    private ResumePdfViewer() {
    }

    public static void open(Context context, String pdfUrl) {
        if (context == null) {
            return;
        }
        if (pdfUrl == null || pdfUrl.trim().isEmpty()) {
            Toast.makeText(context, "简历文件地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final ProgressDialog progress = new ProgressDialog(context);
        progress.setMessage("正在加载简历 PDF…");
        progress.setCancelable(true);
        progress.show();

        Executors.newSingleThreadExecutor().execute(() -> {
            File out = null;
            Exception error = null;
            try {
                out = downloadPdf(appCtx, pdfUrl.trim());
            } catch (Exception e) {
                error = e;
                DebugLog.e(TAG, "download pdf failed: " + e.getMessage());
            }
            final File pdf = out;
            final Exception err = error;
            main.post(() -> {
                try {
                    if (progress.isShowing()) {
                        progress.dismiss();
                    }
                } catch (Exception ignored) {
                }
                if (pdf == null || !pdf.exists()) {
                    Toast.makeText(context, err != null && err.getMessage() != null
                                    ? ("加载失败：" + err.getMessage())
                                    : "加载简历失败，请稍后重试",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    ResumePdfActivity.start(context, pdf, "简历预览");
                } catch (Throwable t) {
                    DebugLog.w(TAG, "open pdf activity failed: " + t.getMessage());
                    openExternal(context, pdf);
                }
            });
        });
    }

    private static File downloadPdf(Context ctx, String pdfUrl) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            URL url = new URL(pdfUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            File dir = new File(ctx.getCacheDir(), "resume_pdf");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建缓存目录");
            }
            File out = new File(dir, "resume_" + Math.abs(pdfUrl.hashCode()) + ".pdf");
            in = conn.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) >= 0) {
                fos.write(buf, 0, n);
                total += n;
                if (total > 40L * 1024 * 1024) {
                    throw new IllegalStateException("文件过大");
                }
            }
            fos.flush();
            if (total < 64) {
                throw new IllegalStateException("文件内容为空");
            }
            return out;
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void openExternal(Context context, File pdf) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", pdf);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(intent, "打开简历 PDF"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "本机未安装 PDF 阅读器", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            DebugLog.e(TAG, "open external pdf failed: " + e.getMessage());
            Toast.makeText(context, "无法打开 PDF：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
