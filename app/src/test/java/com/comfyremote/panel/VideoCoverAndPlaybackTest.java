package com.comfyremote.panel;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.Surface;
import android.widget.ImageView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VideoCoverAndPlaybackTest {
    private final VideoThumbnailLoader.FrameDecoder original = VideoThumbnailLoader.decoder;
    @After public void reset() { VideoThumbnailLoader.decoder = original; VideoThumbnailLoader.clearMemoryForTest(); }
    private Application app() { return RuntimeEnvironment.getApplication(); }
    private boolean hasCover(ImageView view) {
        if (!(view.getDrawable() instanceof BitmapDrawable)) return false;
        Bitmap bitmap = ((BitmapDrawable) view.getDrawable()).getBitmap();
        return bitmap.getWidth() == 64 && bitmap.getHeight() == 32;
    }

    private MockWebServer rangeServer(long length) throws Exception {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String range = request.getHeader("Range");
                if (range == null) return new MockResponse().setResponseCode(400);
                String[] parts = range.substring(6).split("-");
                long start = Long.parseLong(parts[0]), end = Math.min(length - 1, Long.parseLong(parts[1]));
                byte[] bytes = new byte[(int) (end - start + 1)];
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (start + i);
                return new MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length)
                        .setBody(new okio.Buffer().write(bytes));
            }
        });
        server.start(); return server;
    }
    @Test public void rangeReadsHeaderAndTailAndCachesBlocksWithoutDownloadingWholeVideo() throws Exception {
        long length = 16L * 1024 * 1024 + 123;
        try (MockWebServer server = rangeServer(length);
             VideoRangeDataSource source = new VideoRangeDataSource(server.url("/view").toString(), () -> true)) {
            assertEquals(length, source.getSize());
            byte[] bytes = new byte[32];
            assertEquals(32, source.readAt(7, bytes, 0, 32));
            assertEquals(7, bytes[0]);
            assertEquals(17, source.readAt(length - 17, bytes, 0, 17));
            assertEquals((byte) (length - 17), bytes[0]);
            assertEquals(32, source.readAt(7, bytes, 0, 32));
            assertEquals(-1, source.readAt(length, bytes, 0, 1));
            assertEquals(0, source.readAt(0, bytes, 0, 0));
            assertEquals(2, server.getRequestCount());
            assertTrue(source.transferredBytes() < 70_000);
        }
    }
    @Test public void ignoresNoRangeResponseInsteadOfReadingHugeFullFile() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("not a range").setHeader("Content-Length", 20_000_000));
            server.start();
            try (VideoRangeDataSource source = new VideoRangeDataSource(server.url("/view").toString(), () -> true)) {
                try { source.getSize(); fail("A huge HTTP 200 must not be consumed"); }
                catch (IOException expected) {}
                assertEquals(0, source.transferredBytes());
                assertEquals(1, server.getRequestCount());
            }
        }
    }
    @Test public void budgetStopsAdditionalRequestsAndRejectsMismatchedRanges() throws Exception {
        try (MockWebServer server = rangeServer(1_000_000);
             VideoRangeDataSource source = new VideoRangeDataSource(server.url("/view").toString(), () -> true,
                     VideoRangeDataSource.BLOCK_BYTES, 15000)) {
            source.getSize();
            try { source.readAt(65536, new byte[1], 0, 1); fail("Budget must stop another block"); }
            catch (IOException expected) {}
            assertEquals(1, server.getRequestCount());
        }
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 8-9/100").setBody("xx"));
            server.start();
            try (VideoRangeDataSource source = new VideoRangeDataSource(server.url("/view").toString(), () -> true)) {
                try { source.getSize(); fail("Never decode incorrectly positioned bytes"); } catch (IOException expected) {}
            }
        }
    }
    @Test public void cancellingRangeReadDoesNotWaitForBlockedServer() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            VideoRangeDataSource source = new VideoRangeDataSource(server.url("/view").toString(), () -> true);
            Future<Long> pending = pool.submit(source::getSize);
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
            long start = System.nanoTime(); source.close();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100);
            try { pending.get(2, TimeUnit.SECONDS); fail("Read must be cancelled"); }
            catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof IOException); }
        } finally { pool.shutdownNow(); }
    }
    @Test public void realBitmapCoversCoalesceAndSurviveMemoryEviction() throws Exception {
        AtomicInteger decodes = new AtomicInteger();
        CountDownLatch began = new CountDownLatch(1), proceed = new CountDownLatch(1);
        VideoThumbnailLoader.decoder = (url, wanted) -> {
            decodes.incrementAndGet(); began.countDown();
            assertTrue(proceed.await(3, TimeUnit.SECONDS));
            Bitmap bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xffff4433); return bitmap;
        };
        ImageRef ref = new ImageRef("cover.mp4", "", "output", 1, "cover-single-flight");
        List<ImageView> targets = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ImageView view = new ImageView(app()); targets.add(view);
            GalleryThumbnailLoader.load(app(), "http://127.0.0.1:9123", ref, view, 320, () -> true);
        }
        assertTrue(began.await(3, TimeUnit.SECONDS)); proceed.countDown();
        ImageView last = targets.get(targets.size() - 1);
        for (int i = 0; !hasCover(last) && i < 300; i++) {
            Thread.sleep(10); Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue(hasCover(last));
        assertEquals(1, decodes.get());
        VideoThumbnailLoader.clearMemoryForTest();
        Bitmap disk = VideoThumbnailLoader.loadBlocking(app(), "http://127.0.0.1:9123", ref, () -> true);
        assertNotNull(disk); assertEquals(64, disk.getWidth()); assertEquals(1, decodes.get());
        assertNotEquals(VideoThumbnailLoader.cacheKey("8188", ref), VideoThumbnailLoader.cacheKey("8189", ref));
        assertNotEquals(VideoThumbnailLoader.cacheKey("8188", ref),
                VideoThumbnailLoader.cacheKey("8188", new ImageRef("cover.mp4", "", "output", 1, "different-task")));
        assertEquals("Server history and local observation metadata share one video cover",
                VideoThumbnailLoader.cacheKey("8188", ref),
                VideoThumbnailLoader.cacheKey("8188", new ImageRef("cover.mp4", "", "output", 99,
                        ref.promptId, "189", 123456, 1)));
    }
    @Test public void coverFailureBackoffSurvivesMemoryEvictionAndStaleTilesNeverDecode() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        VideoThumbnailLoader.decoder = (url, wanted) -> { attempts.incrementAndGet(); throw new IOException("unsupported"); };
        ImageRef ref = new ImageRef("failed.mp4", "", "output", 1, "failed-test");
        try { VideoThumbnailLoader.loadBlocking(app(), "8188", ref, () -> true); fail(); } catch (IOException expected) {}
        VideoThumbnailLoader.clearMemoryForTest();
        for (int i = 0; i < 30; i++) assertNull(VideoThumbnailLoader.loadBlocking(app(), "8188", ref, () -> true));
        GalleryThumbnailLoader.load(app(), "8188", new ImageRef("stale.mp4", "", "output"), new ImageView(app()), 320, () -> false);
        assertEquals(1, attempts.get());
    }
    @Test public void playbackCloseReturnsWhilePrepareAndNativeReleaseAreBlocked() throws Exception {
        CountDownLatch preparing = new CountDownLatch(1), allowPrepare = new CountDownLatch(1);
        CountDownLatch releasing = new CountDownLatch(1), allowRelease = new CountDownLatch(1), released = new CountDownLatch(1);
        AtomicBoolean nativeOnMain = new AtomicBoolean(), started = new AtomicBoolean(), callback = new AtomicBoolean();
        VideoPlaybackSession session = new VideoPlaybackSession(() -> new VideoPlaybackSession.Backend() {
            public void prepare(String url, Surface surface, VideoPlaybackSession.Events events, AtomicBoolean cancelled) throws Exception {
                nativeOnMain.set(Looper.myLooper() == Looper.getMainLooper());
                preparing.countDown(); allowPrepare.await(5, TimeUnit.SECONDS); events.prepared(2000, 320, 180);
            }
            public void start() { started.set(true); }
            public void pause() {}
            public void seek(int position) {}
            public int position() { return 0; }
            public void release() {
                releasing.countDown();
                try { allowRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                released.countDown();
            }
        }, new VideoPlaybackSession.Events() {
            public void prepared(int d, int w, int h) { callback.set(true); }
            public void rendering() { callback.set(true); }
            public void completed() { callback.set(true); }
            public void failed(String message) { callback.set(true); }
        });
        try {
            session.open("http://slow-video", null, 0, null);
            assertTrue(preparing.await(3, TimeUnit.SECONDS));
            long start = System.nanoTime(); session.close();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100);
            assertTrue(session.isClosed()); assertFalse(nativeOnMain.get());
            allowPrepare.countDown(); assertTrue(releasing.await(3, TimeUnit.SECONDS));
            session.close(); Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(started.get()); assertFalse(callback.get());
        } finally {
            allowPrepare.countDown(); allowRelease.countDown(); session.close();
            assertTrue(released.await(3, TimeUnit.SECONDS));
        }
    }
}
