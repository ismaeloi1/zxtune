/**
 * @file
 * @brief Implementation of Iterator based on Vfs objects
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.playback;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import app.zxtune.Log;
import app.zxtune.R;
import app.zxtune.core.Identifier;
import app.zxtune.core.Module;
import app.zxtune.core.Scanner;
import app.zxtune.fs.DefaultComparator;
import app.zxtune.fs.VfsArchive;
import app.zxtune.fs.VfsDir;
import app.zxtune.fs.VfsExtensions;
import app.zxtune.fs.VfsFile;
import app.zxtune.fs.VfsObject;
import app.zxtune.playback.stubs.PlayableItemStub;
import app.zxtune.utils.StubProgressCallback;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

// TODO: support cleanup of iterator itself
public class FileIterator implements Iterator {

    private static final String TAG = FileIterator.class.getName();

    private static final long MAX_USED_MEMORY = 128 << 20;

    private static class OnDemandUpdateItem {

        private final Identifier location;
        @Nullable private PlayableItem cached;
        private final long dataSize;

        OnDemandUpdateItem(PlayableItem delegate) {
            this.location = delegate.getDataId();
            this.cached = delegate;
            this.dataSize = delegate.getModule().getProperty("Size", 0);
        }

        final long getUsedMemory() {
            return cached != null ? dataSize : 0;
        }

        final PlayableItem capture() {
            final PlayableItem result = cached;
            cached = null;
            return result;
        }

        final void preload(AsyncScanner.Analyzer analyzer) {
            if (cached == null) {
                analyzer.analyzeIdentifier(
                        location,
                        new Scanner.Callback() {
                            @Override
                            public void onModule(Identifier id, Module module) {
                                cached = new AsyncScanner.FileItem(id, module);
                            }

                            @Override
                            public void onError(Identifier id, Exception e) {
                                Log.w(TAG, e, "Failed to reopen" + id);
                            }
                        });
            }
        }
    }

    private final LinkedBlockingQueue<PlayableItem> itemsQueue;
    // Ordered from the furthest folder position reached to the earliest one
    private final ArrayList<OnDemandUpdateItem> history;
    private final Exception[] lastError;
    private long totalUsedMemory;

    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final Object scanHandle;

    private int historyDepth;
    private final AsyncScanner.Analyzer analyzer;
    // Folder files before the started one. Playback may start in the middle of the folder, so
    // 'previous' has to reach them even though they were never played.
    private final List<VfsFile> preceding;
    private int precedingLeft;

    // Folder content split around the started file
    @VisibleForTesting
    static final class Files {
        final java.util.Iterator<VfsFile> following;
        final List<VfsFile> preceding;

        Files(java.util.Iterator<VfsFile> following, List<VfsFile> preceding) {
            this.following = following;
            this.preceding = preceding;
        }

        Files(java.util.Iterator<VfsFile> following) {
            this(following, Collections.<VfsFile>emptyList());
        }
    }

    public static FileIterator create(Context ctx, Uri uri) throws Exception {
        final Files files = createDirFiles(uri);
        if (!files.following.hasNext()) {
            throw new Exception(ctx.getString(R.string.failed_resolve, uri.toString()));
        }
        return new FileIterator(ctx, files, AsyncScanner.Analyzer.DEFAULT);
    }

    @VisibleForTesting
    FileIterator(Context context, Files files, AsyncScanner.Analyzer analyzer) throws Exception {
        this.itemsQueue = new LinkedBlockingQueue<>(1);
        this.history = new ArrayList<>(100);
        this.lastError = new Exception[1];
        this.totalUsedMemory = 0;
        this.analyzer = analyzer;
        this.preceding = files.preceding;
        this.precedingLeft = files.preceding.size();
        this.scanHandle =
                AsyncScanner.scan(
                        new ScannerCallback(files.following, itemsQueue, lastError), analyzer);
        this.historyDepth = 0;
        if (!takeNextItem()) {
            if (lastError[0] != null) {
                throw lastError[0];
            }
            throw new Exception(context.getString(R.string.no_tracks_found));
        }
    }

    @Override
    public PlayableItem getItem() {
        final OnDemandUpdateItem wrapper = history.get(historyDepth);
        totalUsedMemory -= wrapper.getUsedMemory();
        wrapper.preload(analyzer);
        return wrapper.capture();
    }

    @Override
    public boolean next() {
        if (0 != historyDepth) {
            --historyDepth;
            return true;
        }
        return takeNextItem();
    }

    @Override
    public boolean prev() {
        if (historyDepth + 1 < history.size()) {
            ++historyDepth;
            return true;
        }
        return takePrevItem();
    }

    // Scans backwards one file at a time on the caller's (service worker) thread: only what is
    // actually navigated to gets analyzed, so huge folders cost nothing until 'previous' is used.
    // Folder iteration ignores sequence modes in both directions, so the first file is a hard stop
    // (current track keeps playing), same as the last one is for 'next'.
    private boolean takePrevItem() {
        while (precedingLeft > 0) {
            final VfsFile file = preceding.get(--precedingLeft);
            final ArrayList<PlayableItem> items = analyzeFile(file);
            if (items.isEmpty()) {
                continue;
            }
            // Multitrack file: the nearest previous position is its last track
            for (int idx = items.size() - 1; idx >= 0; --idx) {
                final OnDemandUpdateItem wrapper = new OnDemandUpdateItem(items.get(idx));
                totalUsedMemory += wrapper.getUsedMemory();
                history.add(wrapper);
            }
            ++historyDepth;
            releaseExcessMemory();
            return true;
        }
        return false;
    }

    private ArrayList<PlayableItem> analyzeFile(VfsFile file) {
        final ArrayList<PlayableItem> result = new ArrayList<>();
        analyzer.analyzeFile(
                file,
                new Scanner.Callback() {
                    @Override
                    public void onModule(Identifier id, Module module) {
                        result.add(new AsyncScanner.FileItem(id, module));
                    }

                    @Override
                    public void onError(Identifier id, Exception e) {
                        Log.w(TAG, e, "Skip " + id);
                    }
                });
        return result;
    }

    private static class ScannerCallback implements AsyncScanner.Callback {

        private final int[] counter = {0, 0};
        private final java.util.Iterator<VfsFile> files;
        private final LinkedBlockingQueue<PlayableItem> itemsQueue;
        private final Exception[] lastError;

        ScannerCallback(
                java.util.Iterator<VfsFile> files,
                LinkedBlockingQueue<PlayableItem> itemsQueue,
                Exception[] lastError) {
            this.files = files;
            this.itemsQueue = itemsQueue;
            this.lastError = lastError;
        }

        @Nullable
        @Override
        public VfsFile getNextFile() {
            final int doneFiles = counter[0];
            counter[0]++;
            final int itemsCount = counter[1];
            if ((itemsCount == 0 && doneFiles > 0) || !files.hasNext()) {
                finish();
                return null;
            } else {
                return files.next();
            }
        }

        @Override
        public Reply onItem(PlayableItem item) {
            if (itemsQueue.offer(item)) {
                ++counter[1];
                return Reply.CONTINUE;
            } else {
                return Reply.RETRY;
            }
        }

        private void finish() {
            try {
                itemsQueue.put(PlayableItemStub.instance());
            } catch (InterruptedException e) {
                Log.w(TAG, e, "Interrupted Callback.onFinish");
            }
        }

        @Override
        public void onError(Identifier id, Exception e) {
            lastError[0] = e;
        }
    }

    private boolean takeNextItem() {
        try {
            final PlayableItem newItem = itemsQueue.take();
            if (newItem != PlayableItemStub.instance()) {
                addItem(newItem);
                return true;
            }
            // put limiter back
            itemsQueue.put(newItem);
        } catch (InterruptedException e) {
            Log.w(TAG, e, "Interrupted takeNextItem");
        }
        return false;
    }

    private void addItem(PlayableItem item) {
        final OnDemandUpdateItem wrapper = new OnDemandUpdateItem(item);
        totalUsedMemory += wrapper.getUsedMemory();
        history.add(0, wrapper);
        releaseExcessMemory();
    }

    // History grows at both ends, so drop modules furthest from the current position first; they
    // are reloaded on demand when navigated to again
    private void releaseExcessMemory() {
        for (int lo = 0, hi = history.size() - 1; totalUsedMemory > MAX_USED_MEMORY && lo <= hi; ) {
            final int index = historyDepth - lo > hi - historyDepth ? lo++ : hi--;
            if (index == historyDepth) {
                continue;
            }
            final OnDemandUpdateItem toCleanup = history.get(index);
            final long used = toCleanup.getUsedMemory();
            if (used != 0) {
                totalUsedMemory -= used;
                toCleanup.capture().getModule().release();
            }
        }
    }

    private static Files createDirFiles(Uri start) throws Exception {
        final VfsObject obj = VfsArchive.resolveForced(start, StubProgressCallback.instance());
        return obj != null ? listFolder(obj) : new Files(Collections.<VfsFile>emptyIterator());
    }

    @VisibleForTesting
    static Files listFolder(VfsObject obj) throws Exception {
        final ArrayList<VfsFile> result = new ArrayList<>();
        if (!(obj instanceof VfsFile)) {
            final Object feed = obj.getExtension(VfsExtensions.FEED);
            return new Files(
                    feed != null ? (java.util.Iterator<VfsFile>) feed : result.listIterator());
        }
        final VfsObject parent = obj.getParent();
        if (parent == null) {
            result.add((VfsFile) obj);
            return new Files(result.listIterator());
        }
        final VfsFile parentFile = parent instanceof VfsFile ? (VfsFile) parent : null;
        final VfsDir parentDir =
                parent instanceof VfsDir ? (VfsDir) parent : (VfsDir) parentFile.getParent();
        {
            final Object feed = findFeed(parentDir);
            if (feed != null) {
                // Feeds are forward-only streams, nothing precedes the started item
                return new Files(
                        new FeedIterator((VfsFile) obj, (java.util.Iterator<VfsFile>) feed));
            }
        }
        parentDir.enumerate(
                new VfsDir.Visitor() {
                    @Override
                    public void onDir(VfsDir dir) {}

                    @Override
                    public void onFile(VfsFile file) {
                        result.add(file);
                    }
                });
        final Object extension = parentDir.getExtension(VfsExtensions.COMPARATOR);
        final Comparator<VfsObject> comparator =
                extension instanceof Comparator<?>
                        ? (Comparator<VfsObject>) extension
                        : DefaultComparator.instance();
        Collections.sort(result, comparator);
        // Resolved file may be incomparable with dir content due to lack of some properties
        // E.g. track from zxart/Top doesn't have rating info
        // So use plain linear search by uri
        final Uri normalizedUri = obj.getUri();
        final Uri parentFileUri = parentFile != null ? parentFile.getUri() : null;
        for (int idx = 0, lim = result.size(); idx < lim; ++idx) {
            final VfsFile cur = result.get(idx);
            final Uri curUri = cur.getUri();
            if (normalizedUri.equals(curUri) || curUri.equals(parentFileUri)) {
                return new Files(result.listIterator(idx), result.subList(0, idx));
            }
        }
        return new Files(result.listIterator());
    }

    @Nullable
    private static Object findFeed(VfsObject start) {
        for (VfsObject obj = start; obj != null; obj = obj.getParent()) {
            final Object feed = obj.getExtension(VfsExtensions.FEED);
            if (feed != null) {
                return feed;
            }
        }
        return null;
    }

    // Adapter to prepend feed with already known item
    private static class FeedIterator implements java.util.Iterator<VfsFile> {

        private final java.util.Iterator<VfsFile> delegate;
        @Nullable private VfsFile first;

        FeedIterator(VfsFile first, java.util.Iterator<VfsFile> delegate) {
            this.first = first;
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return first != null || delegate.hasNext();
        }

        @Override
        public VfsFile next() {
            if (first != null) {
                final VfsFile res = first;
                first = null;
                return res;
            } else {
                return delegate.next();
            }
        }
    }
}
