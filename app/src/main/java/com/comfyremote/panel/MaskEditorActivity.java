package com.comfyremote.panel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/**
 * V2.4 mobile mask painter for ComfyUI image-loader nodes with two-finger zoom/pan.
 *
 * ComfyUI core LoadImage defines MASK as (1 - alpha). Therefore painted mask
 * pixels are saved with alpha=0 while unpainted pixels are alpha=255. RGB is
 * kept intact, so the same uploaded PNG can feed both IMAGE and MASK outputs.
 */
public class MaskEditorActivity extends Activity {
    public static final String EXTRA_SOURCE_URI = "source_uri";
    public static final String EXTRA_EXISTING_MASK_PATH = "existing_mask_path";
    public static final String EXTRA_OUTPUT_PATH = "output_path";

    private MaskCanvasView canvasView;
    private TextView stateText;
    private Button drawButton, eraseButton, saveButton;
    private Uri sourceUri;
    private String existingMaskPath = "";
    private String outputPath = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.prepare(this);
        super.onCreate(savedInstanceState);
        ThemeManager.applyWindow(this);

        String source = getIntent().getStringExtra(EXTRA_SOURCE_URI);
        existingMaskPath = safe(getIntent().getStringExtra(EXTRA_EXISTING_MASK_PATH));
        outputPath = safe(getIntent().getStringExtra(EXTRA_OUTPUT_PATH));
        if (source == null || source.isEmpty() || outputPath.isEmpty()) {
            Toast.makeText(this, "蒙版编辑器缺少输入图片", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        sourceUri = Uri.parse(source);
        buildUi();
        loadEditorImage();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(ThemeManager.background(this));

        TextView title = text("蒙版遮罩编辑器", 21, true);
        root.addView(title);
        TextView tip = text("红色区域 = ComfyUI MASK 白色区域。单指涂抹/擦除；双指可缩放并拖动画布（最高约 10×），方便精确处理很小的区域。", 12, false);
        tip.setTextColor(ThemeManager.secondary(this));
        tip.setPadding(0, dp(4), 0, dp(8));
        root.addView(tip);

        canvasView = new MaskCanvasView(this);
        LinearLayout.LayoutParams canvasLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        canvasLp.setMargins(0, 0, 0, dp(8));
        root.addView(canvasView, canvasLp);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        drawButton = button("✏ 涂抹");
        eraseButton = button("⌫ 橡皮擦");
        modeRow.addView(drawButton, weighted());
        LinearLayout.LayoutParams eraseLp = weighted(); eraseLp.setMargins(dp(8), 0, 0, 0);
        modeRow.addView(eraseButton, eraseLp);
        root.addView(modeRow);
        drawButton.setOnClickListener(v -> setEraseMode(false));
        eraseButton.setOnClickListener(v -> setEraseMode(true));
        setEraseMode(false);

        TextView brushLabel = text("画笔大小", 12, false);
        brushLabel.setPadding(0, dp(8), 0, 0);
        root.addView(brushLabel);
        SeekBar brush = new SeekBar(this);
        brush.setMax(190);
        brush.setProgress(55);
        root.addView(brush);
        brush.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                canvasView.setBrushDp(10 + progress);
                brushLabel.setText("画笔大小：" + (10 + progress));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        canvasView.setBrushDp(65);
        brushLabel.setText("画笔大小：65");

        LinearLayout editRow = new LinearLayout(this);
        editRow.setOrientation(LinearLayout.HORIZONTAL);
        Button undo = button("撤销");
        Button redo = button("重做");
        Button invert = button("反转");
        Button clear = button("清空");
        editRow.addView(undo, weighted());
        LinearLayout.LayoutParams lp2 = weighted(); lp2.setMargins(dp(5), 0, 0, 0); editRow.addView(redo, lp2);
        LinearLayout.LayoutParams lp3 = weighted(); lp3.setMargins(dp(5), 0, 0, 0); editRow.addView(invert, lp3);
        LinearLayout.LayoutParams lp4 = weighted(); lp4.setMargins(dp(5), 0, 0, 0); editRow.addView(clear, lp4);
        root.addView(editRow);
        undo.setOnClickListener(v -> canvasView.undo());
        redo.setOnClickListener(v -> canvasView.redo());
        invert.setOnClickListener(v -> canvasView.invertMask());
        clear.setOnClickListener(v -> canvasView.clearMask());

        stateText = text("正在载入图片…", 12, false);
        stateText.setTextColor(ThemeManager.muted(this));
        stateText.setPadding(0, dp(8), 0, dp(6));
        root.addView(stateText);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = button("取消");
        saveButton = button("✓ 保存蒙版");
        saveButton.setEnabled(false);
        bottom.addView(cancel, weighted());
        LinearLayout.LayoutParams saveLp = weighted(); saveLp.setMargins(dp(8), 0, 0, 0);
        bottom.addView(saveButton, saveLp);
        root.addView(bottom);
        cancel.setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> saveMask());

        setContentView(root);
    }

    private void setEraseMode(boolean erase) {
        if (canvasView != null) canvasView.setErase(erase);
        if (drawButton != null) drawButton.setText(erase ? "涂抹" : "✓ 涂抹");
        if (eraseButton != null) eraseButton.setText(erase ? "✓ 橡皮擦" : "橡皮擦");
    }

    private void loadEditorImage() {
        new Thread(() -> {
            try {
                Bitmap sourcePreview = decodeOriented(sourceUri, 1800);
                if (sourcePreview == null) throw new Exception("无法解码输入图片");
                Bitmap existing = null;
                File existingFile = existingMaskPath.isEmpty() ? null : new File(existingMaskPath);
                if (existingFile != null && existingFile.isFile()) {
                    existing = decodeFileScaled(existingFile, 1800);
                }
                Bitmap finalExisting = existing;
                runOnUiThread(() -> {
                    canvasView.setSource(sourcePreview, finalExisting);
                    stateText.setText(existingMaskPath.isEmpty() ? "蒙版为空，可以开始涂抹" : "已载入上次保存的蒙版");
                    saveButton.setEnabled(true);
                });
            } catch (OutOfMemoryError oom) {
                runOnUiThread(() -> {
                    stateText.setText("载入失败：图片过大，内存不足");
                    Toast.makeText(this, "图片过大，无法打开蒙版编辑器", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    stateText.setText("载入失败：" + message(e));
                    Toast.makeText(this, "载入图片失败：" + message(e), Toast.LENGTH_LONG).show();
                });
            }
        }, "mask-preview-loader").start();
    }

    private void saveMask() {
        Bitmap mask = canvasView == null ? null : canvasView.copyMask();
        if (mask == null) return;
        saveButton.setEnabled(false);
        stateText.setText("正在生成带 Alpha 蒙版的 PNG…");
        new Thread(() -> {
            try {
                saveMaskedPng(sourceUri, mask, new File(outputPath));
                Intent result = new Intent();
                result.putExtra(EXTRA_OUTPUT_PATH, outputPath);
                runOnUiThread(() -> {
                    setResult(RESULT_OK, result);
                    Toast.makeText(this, "蒙版已保存", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (OutOfMemoryError oom) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    stateText.setText("保存失败：原图过大，内存不足");
                    Toast.makeText(this, "原图过大，保存蒙版时内存不足", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    stateText.setText("保存失败：" + message(e));
                    Toast.makeText(this, "保存蒙版失败：" + message(e), Toast.LENGTH_LONG).show();
                });
            }
        }, "mask-png-writer").start();
    }

    private void saveMaskedPng(Uri uri, Bitmap maskPreview, File target) throws Exception {
        Bitmap original = decodeOriented(uri, 0);
        if (original == null) throw new Exception("无法读取原尺寸图片");

        int mw = maskPreview.getWidth(), mh = maskPreview.getHeight();
        int[] maskPixels = new int[mw * mh];
        maskPreview.getPixels(maskPixels, 0, mw, 0, 0, mw, mh);
        int w = original.getWidth(), h = original.getHeight();
        int[] row = new int[w];
        byte[] rgbaRow = new byte[1 + w * 4];
        rgbaRow[0] = 0; // PNG filter type: None

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new Exception("无法创建蒙版目录");
        File compressed = new File(target.getAbsolutePath() + ".idat.tmp");
        try {
            // Encode RGBA ourselves instead of Bitmap.compress(). Android bitmaps are often
            // premultiplied; custom PNG writing preserves original RGB even where alpha=0.
            try (DeflaterOutputStream def = new DeflaterOutputStream(new FileOutputStream(compressed))) {
                for (int y = 0; y < h; y++) {
                    original.getPixels(row, 0, w, 0, y, w, 1);
                    int my = Math.min(mh - 1, (int) ((long) y * mh / Math.max(1, h)));
                    int maskBase = my * mw;
                    int o = 1;
                    for (int x = 0; x < w; x++) {
                        int mx = Math.min(mw - 1, (int) ((long) x * mw / Math.max(1, w)));
                        int selected = Color.alpha(maskPixels[maskBase + mx]);
                        int alpha = 255 - selected; // ComfyUI LoadImage MASK = 1 - alpha
                        int px = row[x];
                        rgbaRow[o++] = (byte) Color.red(px);
                        rgbaRow[o++] = (byte) Color.green(px);
                        rgbaRow[o++] = (byte) Color.blue(px);
                        rgbaRow[o++] = (byte) alpha;
                    }
                    def.write(rgbaRow);
                }
                def.finish();
            }

            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(target))) {
                out.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
                byte[] ihdr = new byte[13];
                writeIntBigEndian(ihdr, 0, w);
                writeIntBigEndian(ihdr, 4, h);
                ihdr[8] = 8;  // bit depth
                ihdr[9] = 6;  // RGBA
                ihdr[10] = 0; // compression
                ihdr[11] = 0; // filter
                ihdr[12] = 0; // no interlace
                writePngChunk(out, "IHDR", ihdr);
                writePngFileChunk(out, "IDAT", compressed);
                writePngChunk(out, "IEND", new byte[0]);
            }
        } finally {
            compressed.delete();
            original.recycle();
            maskPreview.recycle();
        }
    }

    private static void writeIntBigEndian(byte[] out, int offset, int value) {
        out[offset] = (byte) ((value >>> 24) & 0xff);
        out[offset + 1] = (byte) ((value >>> 16) & 0xff);
        out[offset + 2] = (byte) ((value >>> 8) & 0xff);
        out[offset + 3] = (byte) (value & 0xff);
    }

    private static void writePngChunk(DataOutputStream out, String type, byte[] data) throws Exception {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }

    private static void writePngFileChunk(DataOutputStream out, String type, File dataFile) throws Exception {
        long len = dataFile.length();
        if (len > Integer.MAX_VALUE) throw new Exception("蒙版 PNG 数据过大");
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt((int) len);
        out.write(typeBytes);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(dataFile)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                crc.update(buffer, 0, n);
            }
        }
        out.writeInt((int) crc.getValue());
    }

    private Bitmap decodeOriented(Uri uri, int maxDim) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int sample = 1;
        if (maxDim > 0) {
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDim * 2) sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, opts); }
        if (bitmap == null) return null;
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {}
        return rotateExif(bitmap, orientation);
    }

    private Bitmap decodeFileScaled(File file, int maxDim) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        if (maxDim > 0) {
            while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDim * 2) sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (b == null) throw new Exception("无法读取已保存蒙版");
        return b;
    }

    private static Bitmap rotateExif(Bitmap src, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.setRotate(180); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.setRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: m.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.setRotate(-90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.setRotate(-90); break;
            default: return src;
        }
        Bitmap rotated = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (rotated != src) src.recycle();
        return rotated;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        return b;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(ThemeManager.text(this));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, dp(46), 1f); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String message(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }

    /** Draws an RGB preview with a translucent red mask overlay. */
    private static final class MaskCanvasView extends View {
        private Bitmap source;
        private Bitmap mask;
        private Canvas maskCanvas;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint maskPreviewPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Deque<Bitmap> undo = new ArrayDeque<>();
        private final Deque<Bitmap> redo = new ArrayDeque<>();
        private boolean erase = false;
        private float brushDp = 65f;

        // V2.4 view transform: fitScale is the 1x fit-to-screen scale; zoom is user pinch zoom.
        private float fitScale = 1f, zoom = 1f, scale = 1f, offsetX = 0f, offsetY = 0f;
        private float lastX, lastY;
        private boolean drawing = false;
        private boolean gestureActive = false;
        private float lastGestureFocusX, lastGestureFocusY, lastPinchDistance;

        MaskCanvasView(Activity context) {
            super(context);
            setBackgroundColor(Color.rgb(28, 28, 32));
            brushPaint.setStyle(Paint.Style.STROKE);
            brushPaint.setStrokeCap(Paint.Cap.ROUND);
            brushPaint.setStrokeJoin(Paint.Join.ROUND);
            brushPaint.setColor(Color.WHITE);
            maskPreviewPaint.setColorFilter(new PorterDuffColorFilter(Color.argb(135, 255, 35, 70), PorterDuff.Mode.SRC_IN));
        }

        void setSource(Bitmap rawSource, Bitmap existingMasked) {
            if (rawSource == null) return;
            int w = rawSource.getWidth(), h = rawSource.getHeight();
            int[] pixels = new int[w * h];
            rawSource.getPixels(pixels, 0, w, 0, 0, w, h);
            Bitmap display = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Bitmap initialMask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int[] displayPixels = new int[pixels.length];
            int[] maskPixels = new int[pixels.length];

            if (existingMasked != null) {
                Bitmap aligned = Bitmap.createScaledBitmap(existingMasked, w, h, true);
                int[] ep = new int[w * h];
                aligned.getPixels(ep, 0, w, 0, 0, w, h);
                for (int i = 0; i < pixels.length; i++) {
                    int p = pixels[i];
                    displayPixels[i] = Color.argb(255, Color.red(p), Color.green(p), Color.blue(p));
                    int selected = 255 - Color.alpha(ep[i]);
                    maskPixels[i] = Color.argb(selected, 255, 255, 255);
                }
                if (aligned != existingMasked) aligned.recycle();
                existingMasked.recycle();
            } else {
                for (int i = 0; i < pixels.length; i++) {
                    int p = pixels[i];
                    displayPixels[i] = Color.argb(255, Color.red(p), Color.green(p), Color.blue(p));
                    int selected = 255 - Color.alpha(p);
                    maskPixels[i] = Color.argb(selected, 255, 255, 255);
                }
            }
            display.setPixels(displayPixels, 0, w, 0, 0, w, h);
            initialMask.setPixels(maskPixels, 0, w, 0, 0, w, h);
            rawSource.recycle();
            source = display;
            mask = initialMask;
            maskCanvas = new Canvas(mask);
            undo.clear(); redo.clear();
            resetViewTransform();
            // If the image arrived before the first layout pass, recompute once the View has size.
            post(() -> { resetViewTransform(); invalidate(); });
            invalidate();
        }

        void setErase(boolean value) { erase = value; }
        void setBrushDp(float value) { brushDp = Math.max(8f, value); }

        Bitmap copyMask() { return mask == null ? null : mask.copy(Bitmap.Config.ARGB_8888, false); }

        void clearMask() {
            if (mask == null) return;
            pushUndo();
            maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            redo.clear(); invalidate();
        }

        void invertMask() {
            if (mask == null) return;
            pushUndo();
            int w = mask.getWidth(), h = mask.getHeight();
            int[] px = new int[w * h];
            mask.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int a = 255 - Color.alpha(px[i]);
                px[i] = Color.argb(a, 255, 255, 255);
            }
            mask.setPixels(px, 0, w, 0, 0, w, h);
            redo.clear(); invalidate();
        }

        void undo() {
            if (undo.isEmpty() || mask == null) return;
            redo.push(mask.copy(Bitmap.Config.ARGB_8888, false));
            replaceMask(undo.pop());
        }

        void redo() {
            if (redo.isEmpty() || mask == null) return;
            undo.push(mask.copy(Bitmap.Config.ARGB_8888, false));
            replaceMask(redo.pop());
        }

        private void replaceMask(Bitmap b) {
            if (mask != null && mask != b) mask.recycle();
            mask = b.copy(Bitmap.Config.ARGB_8888, true);
            b.recycle();
            maskCanvas = new Canvas(mask);
            invalidate();
        }

        private void pushUndo() {
            if (mask == null) return;
            undo.push(mask.copy(Bitmap.Config.ARGB_8888, false));
            while (undo.size() > 15) {
                Bitmap oldest = undo.removeLast();
                oldest.recycle();
            }
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { resetViewTransform(); }

        private void resetViewTransform() {
            if (source == null || getWidth() <= 0 || getHeight() <= 0) return;
            fitScale = Math.min((float) getWidth() / source.getWidth(), (float) getHeight() / source.getHeight());
            if (!Float.isFinite(fitScale) || fitScale <= 0f) fitScale = 1f;
            zoom = 1f;
            scale = fitScale;
            offsetX = (getWidth() - source.getWidth() * scale) * 0.5f;
            offsetY = (getHeight() - source.getHeight() * scale) * 0.5f;
            gestureActive = false;
            drawing = false;
        }

        private void clampOffsets() {
            if (source == null) return;
            float contentW = source.getWidth() * scale;
            float contentH = source.getHeight() * scale;
            if (contentW <= getWidth()) offsetX = (getWidth() - contentW) * 0.5f;
            else offsetX = Math.max(getWidth() - contentW, Math.min(0f, offsetX));
            if (contentH <= getHeight()) offsetY = (getHeight() - contentH) * 0.5f;
            else offsetY = Math.max(getHeight() - contentH, Math.min(0f, offsetY));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (source == null || mask == null) return;
            RectF dst = new RectF(offsetX, offsetY, offsetX + source.getWidth() * scale, offsetY + source.getHeight() * scale);
            canvas.drawBitmap(source, null, dst, imagePaint);
            canvas.drawBitmap(mask, null, dst, maskPreviewPaint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (source == null || mask == null || maskCanvas == null) return true;
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
                cancelCurrentStrokeForGesture();
                beginTwoFingerGesture(event);
                return true;
            }

            if (gestureActive) {
                if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() >= 2) {
                    updateTwoFingerGesture(event);
                    return true;
                }
                if (action == MotionEvent.ACTION_POINTER_UP) {
                    if (event.getPointerCount() <= 2) {
                        gestureActive = false;
                        drawing = false;
                    }
                    return true;
                }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    gestureActive = false;
                    drawing = false;
                    return true;
                }
                return true;
            }

            float x = (event.getX() - offsetX) / Math.max(0.0001f, scale);
            float y = (event.getY() - offsetY) / Math.max(0.0001f, scale);
            boolean inside = x >= 0 && y >= 0 && x <= source.getWidth() && y <= source.getHeight();
            if (!inside) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) drawing = false;
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    pushUndo(); redo.clear(); drawing = true; lastX = x; lastY = y; drawLine(x, y, x, y); return true;
                case MotionEvent.ACTION_MOVE:
                    if (drawing) { drawLine(lastX, lastY, x, y); lastX = x; lastY = y; }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (drawing) drawLine(lastX, lastY, x, y);
                    drawing = false; return true;
                default: return true;
            }
        }

        private void cancelCurrentStrokeForGesture() {
            if (!drawing) return;
            drawing = false;
            // A second finger usually lands a moment after the first. Roll back that in-progress
            // stroke so a pinch gesture never leaves an accidental mask dot/line.
            if (!undo.isEmpty()) replaceMask(undo.pop());
            redo.clear();
        }

        private void beginTwoFingerGesture(MotionEvent event) {
            gestureActive = true;
            lastGestureFocusX = focusX(event);
            lastGestureFocusY = focusY(event);
            lastPinchDistance = pinchDistance(event);
        }

        private void updateTwoFingerGesture(MotionEvent event) {
            float focusX = focusX(event);
            float focusY = focusY(event);
            float distance = pinchDistance(event);

            // Two-finger translation pans the enlarged canvas.
            offsetX += focusX - lastGestureFocusX;
            offsetY += focusY - lastGestureFocusY;
            clampOffsets();

            if (lastPinchDistance > 1f && distance > 1f) {
                float oldScale = scale;
                float anchorImageX = (focusX - offsetX) / Math.max(0.0001f, oldScale);
                float anchorImageY = (focusY - offsetY) / Math.max(0.0001f, oldScale);
                float factor = distance / lastPinchDistance;
                float newZoom = Math.max(1f, Math.min(10f, zoom * factor));
                if (Math.abs(newZoom - zoom) > 0.0001f) {
                    zoom = newZoom;
                    scale = fitScale * zoom;
                    offsetX = focusX - anchorImageX * scale;
                    offsetY = focusY - anchorImageY * scale;
                    clampOffsets();
                }
            }

            lastGestureFocusX = focusX;
            lastGestureFocusY = focusY;
            lastPinchDistance = distance;
            invalidate();
        }

        private float focusX(MotionEvent e) {
            if (e.getPointerCount() < 2) return e.getX();
            return (e.getX(0) + e.getX(1)) * 0.5f;
        }

        private float focusY(MotionEvent e) {
            if (e.getPointerCount() < 2) return e.getY();
            return (e.getY(0) + e.getY(1)) * 0.5f;
        }

        private float pinchDistance(MotionEvent e) {
            if (e.getPointerCount() < 2) return 0f;
            float dx = e.getX(0) - e.getX(1);
            float dy = e.getY(0) - e.getY(1);
            return (float) Math.sqrt(dx * dx + dy * dy);
        }

        private void drawLine(float x1, float y1, float x2, float y2) {
            // Keep the brush visually the same size on screen. When zoomed in, its footprint on
            // original-image pixels becomes smaller, which gives the user more precision.
            brushPaint.setStrokeWidth(Math.max(1f, brushDp / Math.max(0.05f, scale)));
            brushPaint.setXfermode(erase ? new android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR) : null);
            maskCanvas.drawLine(x1, y1, x2, y2, brushPaint);
            brushPaint.setXfermode(null);
            invalidate();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (source != null && !source.isRecycled()) source.recycle();
            if (mask != null && !mask.isRecycled()) mask.recycle();
            while (!undo.isEmpty()) { Bitmap b = undo.pop(); if (!b.isRecycled()) b.recycle(); }
            while (!redo.isEmpty()) { Bitmap b = redo.pop(); if (!b.isRecycled()) b.recycle(); }
        }
    }

}
