package com.comfyremote.panel;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.util.Base64;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Runs the platform decoder and actual Activity against local ranged and deliberately slow HTTP. */
@RunWith(AndroidJUnit4.class)
public class VideoNativeIntegrationTest {
    private Instrumentation instrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private Context context() { return instrumentation().getTargetContext(); }
    private byte[] fixture(boolean pad) throws Exception {
        byte[] encoded;
        try (InputStream in = instrumentation().getContext().getAssets().open("cover-fixture.base64")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            encoded = out.toByteArray();
        }
        byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
        if (!pad) return raw;
        // A valid 16 MiB free atom before moov proves decoding can seek past video bytes.
        int moov = 0;
        while (moov + 8 <= raw.length) {
            int size = ByteBuffer.wrap(raw, moov, 4).getInt();
            if (new String(raw, moov + 4, 4, StandardCharsets.US_ASCII).equals("moov")) break;
            if (size < 8) throw new IOException("Invalid fixture");
            moov += size;
        }
        assertTrue(moov + 8 < raw.length);
        int padding = 16 * 1024 * 1024;
        byte[] padded = new byte[raw.length + padding];
        System.arraycopy(raw, 0, padded, 0, moov);
        ByteBuffer.wrap(padded, moov, 8).putInt(padding).put(new byte[]{'f','r','e','e'});
        System.arraycopy(raw, moov, padded, moov + padding, raw.length - moov);
        return padded;
    }
    @Test public void nativeDecoderBuildsRealCoverWithRangesAndReopensFromDiskWithoutNetwork() throws Exception {
        try (MediaServer server = new MediaServer(fixture(true), 0)) {
            ImageRef ref = new ImageRef("native-cover.mp4", "", "output", System.currentTimeMillis(), UUID.randomUUID().toString());
            Bitmap cover = VideoThumbnailLoader.loadBlocking(context(), server.base(), ref, () -> true);
            assertNotNull("The Android decoder must produce an actual frame", cover);
            assertTrue(cover.getWidth() > 100 && cover.getHeight() > 50);
            assertTrue(cover.getWidth() <= 512 && cover.getHeight() <= 512);
            assertNotEquals(cover.getPixel(20, 20), cover.getPixel(cover.getWidth() - 20, cover.getHeight() - 20));
            assertTrue("Cover must not download the padded 16 MiB video: " + server.sent.get(), server.sent.get() < 2 * 1024 * 1024);
            assertEquals(0, server.withoutRange.get());
            int before = server.requests.get();
            VideoThumbnailLoader.clearMemoryForTest();
            assertNotNull(VideoThumbnailLoader.loadBlocking(context(), server.base(), ref, () -> true));
            assertEquals("Persistent cover cache must avoid a second video request", before, server.requests.get());
            instrumentation().runOnMainSync(() -> {
                ImageView home = new ImageView(context()), queue = new ImageView(context()), gallery = new ImageView(context());
                for (ImageView target : Arrays.asList(home, queue, gallery)) {
                    GalleryThumbnailLoader.load(context(), server.base(), ref, target, 320, () -> true);
                    assertTrue(target.getDrawable() instanceof BitmapDrawable);
                }
            });
            File output = new File(context().getExternalFilesDir("validation"), "native-video-cover.jpg");
            try (FileOutputStream file = new FileOutputStream(output)) { cover.compress(Bitmap.CompressFormat.JPEG, 90, file); }
            report("cover_file_bytes=" + server.bytes.length + " ranged_bytes_sent=" + server.sent.get() + " requests=" + before);
        }
    }
    @Test public void returnButtonWorksWhileVideoServerHasNotResponded() throws Exception {
        try (MediaServer server = new MediaServer(fixture(false), 6000)) {
            VideoPlayerActivity activity = launch(server.base(), "slow.mp4");
            try {
                assertTrue("Player must have begun its network preparation", server.requested.await(15, TimeUnit.SECONDS));
                AtomicLong elapsed = new AtomicLong();
                instrumentation().runOnMainSync(() -> {
                    long before = SystemClock.elapsedRealtime();
                    activity.findViewById(R.id.video_back).performClick();
                    elapsed.set(SystemClock.elapsedRealtime() - before);
                });
                assertTrue("Back must not wait for a 6-second server response: " + elapsed.get(), elapsed.get() < 500);
                assertTrue(activity.isFinishing());
                report("return_during_loading_ms=" + elapsed.get());
            } finally { instrumentation().runOnMainSync(activity::finish); }
        }
    }
    @Test public void nativeVideoRendersAndSystemBackCanExit() throws Exception {
        try (MediaServer server = new MediaServer(fixture(false), 0)) {
            VideoPlayerActivity activity = launch(server.base(), "play.mp4");
            try {
                AtomicBoolean rendered = new AtomicBoolean();
                long deadline = SystemClock.elapsedRealtime() + 15000;
                while (!rendered.get() && SystemClock.elapsedRealtime() < deadline) {
                    instrumentation().runOnMainSync(() -> {
                        TextView status = activity.findViewById(R.id.video_status);
                        rendered.set(status.getText().toString().contains("原声播放") || status.getText().toString().contains("播放完成"));
                    });
                    Thread.sleep(100);
                }
                assertTrue("Native video should render before the deadline", rendered.get());
                AtomicLong elapsed = new AtomicLong();
                instrumentation().runOnMainSync(() -> {
                    long before = SystemClock.elapsedRealtime(); activity.onBackPressed();
                    elapsed.set(SystemClock.elapsedRealtime() - before);
                });
                assertTrue(activity.isFinishing()); assertTrue(elapsed.get() < 500);
                report("rendered_video=true system_back_ms=" + elapsed.get());
            } finally { instrumentation().runOnMainSync(activity::finish); }
        }
    }
    private VideoPlayerActivity launch(String base, String filename) {
        Intent intent = new Intent(context(), VideoPlayerActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(VideoPlayerActivity.EXTRA_SERVER, base);
        intent.putExtra(VideoPlayerActivity.EXTRA_MEDIA_JSON, new ImageRef(filename, "", "output").toJson().toString());
        return (VideoPlayerActivity) instrumentation().startActivitySync(intent);
    }
    private void report(String line) throws Exception {
        File file = new File(context().getExternalFilesDir("validation"), "native-results.txt");
        try (FileOutputStream out = new FileOutputStream(file, true)) { out.write((line + "\n").getBytes(StandardCharsets.UTF_8)); }
        android.util.Log.i("VideoNativeValidation", line);
    }
    private static final class MediaServer implements AutoCloseable {
        final byte[] bytes;
        final long delay;
        final ServerSocket server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        final ExecutorService clients = Executors.newCachedThreadPool();
        final AtomicLong sent = new AtomicLong();
        final AtomicInteger requests = new AtomicInteger(), withoutRange = new AtomicInteger();
        final CountDownLatch requested = new CountDownLatch(1);
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        volatile boolean closed;
        MediaServer(byte[] bytes, long delay) throws Exception {
            this.bytes = bytes; this.delay = delay;
            clients.execute(() -> {
                while (!closed) try {
                    Socket socket = server.accept(); sockets.add(socket); clients.execute(() -> serve(socket));
                } catch (IOException ignored) {}
            });
        }
        String base() { return "http://127.0.0.1:" + server.getLocalPort(); }
        void serve(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(10000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                String request = reader.readLine(); if (request == null) return;
                boolean head = request.startsWith("HEAD ");
                String range = null, line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("range:")) range = line.substring(6).trim();
                }
                requests.incrementAndGet(); requested.countDown();
                if (delay > 0) Thread.sleep(delay);
                int start = 0, end = bytes.length - 1;
                boolean partial = range != null;
                if (partial) {
                    String[] positions = range.substring(6).split("-", -1);
                    start = Integer.parseInt(positions[0]);
                    if (!positions[1].isEmpty()) end = Math.min(end, Integer.parseInt(positions[1]));
                } else withoutRange.incrementAndGet();
                OutputStream out = connection.getOutputStream();
                String header = "HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\nContent-Type: video/mp4\r\n"
                        + "Content-Length: " + (end - start + 1) + "\r\nAccept-Ranges: bytes\r\nConnection: close\r\n"
                        + (partial ? "Content-Range: bytes " + start + "-" + end + "/" + bytes.length + "\r\n" : "") + "\r\n";
                out.write(header.getBytes(StandardCharsets.US_ASCII)); out.flush();
                if (!head) for (int offset = start; offset <= end; ) {
                    int n = Math.min(8192, end - offset + 1); out.write(bytes, offset, n); sent.addAndGet(n); offset += n;
                }
                out.flush();
            } catch (Exception ignored) {}
            finally { sockets.remove(socket); }
        }
        public void close() throws Exception {
            closed = true; server.close();
            for (Socket socket : sockets) try { socket.close(); } catch (Exception ignored) {}
            clients.shutdownNow(); clients.awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
