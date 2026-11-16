package com.comfyremote.panel;

import android.media.MediaDataSource;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Random access for cover extraction. Never silently download an unbounded video. */
final class VideoRangeDataSource extends MediaDataSource {
    static final int BLOCK_BYTES = 64 * 1024;
    static final long COVER_BUDGET = 6L * 1024 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build();
    private final String url;
    private final BooleanSupplier wanted;
    private final long budget;
    private final long deadline;
    private long length = -1;
    private long transferred;
    private volatile boolean closed;
    private volatile Call active;
    private final Map<Long, byte[]> blocks = new LinkedHashMap<Long, byte[]>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> entry) { return size() > 16; }
    };

    VideoRangeDataSource(String url, BooleanSupplier wanted) {
        this(url, wanted, COVER_BUDGET, 15000);
    }
    VideoRangeDataSource(String url, BooleanSupplier wanted, long budget, long timeoutMs) {
        this.url = url; this.wanted = wanted; this.budget = budget;
        this.deadline = SystemClock.elapsedRealtime() + timeoutMs;
    }
    private void check() throws InterruptedIOException {
        if (closed || !wanted.getAsBoolean() || Thread.currentThread().isInterrupted()
                || SystemClock.elapsedRealtime() > deadline) throw new InterruptedIOException("封面读取已取消或超时");
    }
    @Override public synchronized long getSize() throws IOException {
        check();
        if (length < 0) block(0);
        return length;
    }
    @Override public synchronized int readAt(long position, byte[] buffer, int offset, int count) throws IOException {
        check();
        if (position < 0 || offset < 0 || count < 0 || offset > buffer.length - count)
            throw new IOException("无效视频读取范围");
        if (count == 0) return 0;
        if (length >= 0 && position >= length) return -1;
        long start = position - position % BLOCK_BYTES;
        byte[] bytes = block(start);
        int within = (int) (position - start);
        if (within >= bytes.length) return -1;
        int n = Math.min(count, bytes.length - within);
        System.arraycopy(bytes, within, buffer, offset, n);
        return n;
    }
    private byte[] block(long start) throws IOException {
        check();
        byte[] cached = blocks.get(start);
        if (cached != null) return cached;
        long end = start + BLOCK_BYTES - 1;
        if (length >= 0) end = Math.min(end, length - 1);
        int expected = (int) (end - start + 1);
        if (expected <= 0) return new byte[0];
        if (transferred + expected > budget) throw new IOException("视频封面读取超过流量上限");
        Call call = HTTP.newCall(new Request.Builder().url(url)
                .header("Range", "bytes=" + start + "-" + end).header("Accept-Encoding", "identity").build());
        active = call;
        try (Response response = call.execute()) {
            check();
            if (response.body() == null) throw new IOException("视频响应为空");
            int allowed;
            if (response.code() == 206) {
                Matcher range = RANGE.matcher(response.header("Content-Range", ""));
                if (!range.matches()) throw new IOException("服务器未返回有效的视频范围");
                long actualStart = Long.parseLong(range.group(1));
                long actualEnd = Long.parseLong(range.group(2));
                long total = Long.parseLong(range.group(3));
                if (actualStart != start || actualEnd < start || actualEnd > end || actualEnd >= total)
                    throw new IOException("服务器返回的视频范围不匹配");
                if (length >= 0 && length != total) throw new IOException("视频文件已变化");
                length = total;
                allowed = (int) (actualEnd - start + 1);
            } else if (response.code() == 200 && start == 0 && response.body().contentLength() >= 0
                    && response.body().contentLength() <= BLOCK_BYTES) {
                length = response.body().contentLength();
                allowed = (int) length; // Tiny complete clips are safe even without Range support.
            } else {
                throw new IOException("视频服务器不支持有限范围读取 (HTTP " + response.code() + ")");
            }
            if (response.body().contentLength() > allowed) throw new IOException("服务器忽略了视频范围上限");
            byte[] bytes = new byte[allowed];
            int offset = 0;
            try (InputStream stream = response.body().byteStream()) {
                while (offset < allowed) {
                    check();
                    int n = stream.read(bytes, offset, allowed - offset);
                    if (n < 0) throw new IOException("视频片段不完整");
                    transferred += n; offset += n;
                }
            }
            blocks.put(start, bytes);
            return bytes;
        } catch (NumberFormatException error) { throw new IOException("视频长度无效", error); }
        finally { active = null; }
    }
    synchronized long transferredBytes() { return transferred; }
    @Override public void close() {
        closed = true;
        Call call = active;
        if (call != null) call.cancel();
    }
}
