package com.hr.digitalhuman.ui.display;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.github.barteksc.pdfviewer.PDFView;
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle;
import com.hr.digitalhuman.R;
import com.hr.digitalhuman.debug.DebugLog;

import java.io.File;

/**
 * 简历 PDF 全屏预览（android-pdf-viewer / Pdfium）。
 */
public class ResumePdfActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "pdf_file_path";
    public static final String EXTRA_TITLE = "pdf_title";

    private static final String TAG = "ResumePdfActivity";

    public static void start(Context context, File pdfFile, String title) {
        if (context == null || pdfFile == null) {
            return;
        }
        Intent intent = new Intent(context, ResumePdfActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, pdfFile.getAbsolutePath());
        intent.putExtra(EXTRA_TITLE, title == null ? "" : title);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resume_pdf);

        TextView tvTitle = findViewById(R.id.tv_pdf_title);
        TextView btnClose = findViewById(R.id.btn_pdf_close);
        TextView tvError = findViewById(R.id.tv_pdf_error);
        ProgressBar loading = findViewById(R.id.pdf_loading);
        PDFView pdfView = findViewById(R.id.pdf_view);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && !title.trim().isEmpty()) {
            tvTitle.setText(title.trim());
        }
        btnClose.setOnClickListener(v -> finish());

        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (path == null || path.trim().isEmpty()) {
            showError(tvError, loading, getString(R.string.resume_pdf_invalid));
            return;
        }
        File file = new File(path.trim());
        if (!file.exists() || file.length() < 64) {
            showError(tvError, loading, getString(R.string.resume_pdf_invalid));
            return;
        }

        loading.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);
        try {
            pdfView.fromFile(file)
                    .enableSwipe(true)
                    .swipeHorizontal(false)
                    .enableDoubletap(true)
                    .defaultPage(0)
                    .scrollHandle(new DefaultScrollHandle(this))
                    .spacing(8)
                    .onLoad(nbPages -> {
                        if (loading != null) {
                            loading.setVisibility(View.GONE);
                        }
                    })
                    .onError(t -> {
                        DebugLog.e(TAG, "pdf render error: "
                                + (t == null ? "null" : t.getMessage()));
                        String msg = t != null && t.getMessage() != null
                                ? t.getMessage() : getString(R.string.resume_pdf_open_fail);
                        showError(tvError, loading, msg);
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    })
                    .load();
        } catch (Throwable t) {
            DebugLog.e(TAG, "pdf load failed: " + t.getMessage());
            showError(tvError, loading, getString(R.string.resume_pdf_open_fail));
        }
    }

    private static void showError(TextView tvError, ProgressBar loading, String message) {
        if (loading != null) {
            loading.setVisibility(View.GONE);
        }
        if (tvError != null) {
            tvError.setVisibility(View.VISIBLE);
            tvError.setText(message == null ? "" : message);
        }
    }
}
