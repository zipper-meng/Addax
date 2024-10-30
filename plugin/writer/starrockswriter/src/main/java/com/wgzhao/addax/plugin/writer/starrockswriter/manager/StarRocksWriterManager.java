package com.wgzhao.addax.plugin.writer.starrockswriter.manager;

import com.google.common.base.Strings;
import com.wgzhao.addax.plugin.writer.starrockswriter.StarRocksWriterOptions;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class StarRocksWriterManager
{

    private static final Logger LOG = LoggerFactory.getLogger(StarRocksWriterManager.class);

    private final StarRocksStreamLoadVisitor starrocksStreamLoadVisitor;
    private final StarRocksWriterOptions writerOptions;

    private final List<byte[]> buffer = new ArrayList<>();
    private final LinkedBlockingDeque<StarRocksFlushTuple> flushQueue;
    private int batchCount = 0;
    private long batchSize = 0;
    private volatile boolean closed = false;
    private volatile Exception flushException;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledFuture;

    public StarRocksWriterManager(StarRocksWriterOptions writerOptions)
    {
        this.writerOptions = writerOptions;
        this.starrocksStreamLoadVisitor = new StarRocksStreamLoadVisitor(writerOptions);
        flushQueue = new LinkedBlockingDeque<>(writerOptions.getFlushQueueLength());
        this.startScheduler();
        this.startAsyncFlushing();
    }

    public void startScheduler()
    {
        stopScheduler();
        this.scheduler = Executors.newScheduledThreadPool(1, new BasicThreadFactory.Builder().namingPattern("starrocks-interval-flush").daemon(true).build());
        this.scheduledFuture = this.scheduler.schedule(() -> {
            synchronized (StarRocksWriterManager.this) {
                if (!closed) {
                    try {
                        String label = createBatchLabel();
                        LOG.info(String.format("StarRocks interval Sinking triggered: label[%s].", label));
                        if (batchCount == 0) {
                            startScheduler();
                        }
                        flush(label, false);
                    }
                    catch (Exception e) {
                        flushException = e;
                    }
                }
            }
        }, writerOptions.getFlushInterval(), TimeUnit.MILLISECONDS);
    }

    public void stopScheduler()
    {
        if (this.scheduledFuture != null) {
            scheduledFuture.cancel(false);
            this.scheduler.shutdown();
        }
    }

    public final synchronized void writeRecord(String record)
            throws IOException
    {
        checkFlushException();
        try {
            byte[] bts = record.getBytes(StandardCharsets.UTF_8);
            buffer.add(bts);
            batchCount++;
            batchSize += bts.length;
            if (batchCount >= writerOptions.getBatchRows() || batchSize >= writerOptions.getBatchSize()) {
                String label = createBatchLabel();
                LOG.debug(String.format("StarRocks buffer Sinking triggered: rows[%d] label[%s].", batchCount, label));
                flush(label, false);
            }
        }
        catch (Exception e) {
            throw new IOException("Writing records to StarRocks failed.", e);
        }
    }

    public synchronized void flush(String label, boolean waitUtilDone)
            throws Exception
    {
        checkFlushException();
        if (batchCount == 0) {
            if (waitUtilDone) {
                waitAsyncFlushingDone();
            }
            return;
        }
        flushQueue.put(new StarRocksFlushTuple(label, batchSize, new ArrayList<>(buffer)));
        if (waitUtilDone) {
            // wait the last flush
            waitAsyncFlushingDone();
        }
        buffer.clear();
        batchCount = 0;
        batchSize = 0;
    }

    public synchronized void close()
    {
        if (!closed) {
            closed = true;
            try {
                String label = createBatchLabel();
                if (batchCount > 0) {
                    LOG.debug(String.format("StarRocks Sink is about to close: label[%s].", label));
                }
                flush(label, true);
            }
            catch (Exception e) {
                throw new RuntimeException("Writing records to StarRocks failed.", e);
            }
        }
        checkFlushException();
    }

    public String createBatchLabel()
    {
        return UUID.randomUUID().toString();
    }

    private void startAsyncFlushing()
    {
        // start flush thread
        Thread flushThread = new Thread(() -> {
            while (true) {
                try {
                    asyncFlush();
                }
                catch (Exception e) {
                    flushException = e;
                }
            }
        });
        flushThread.setDaemon(true);
        flushThread.start();
    }

    private void waitAsyncFlushingDone()
            throws InterruptedException
    {
        // wait previous flushings
        for (int i = 0; i <= writerOptions.getFlushQueueLength(); i++) {
            flushQueue.put(new StarRocksFlushTuple("", 0L, null));
        }
        checkFlushException();
    }

    private void asyncFlush()
            throws Exception
    {
        StarRocksFlushTuple flushData = flushQueue.take();
        if (Strings.isNullOrEmpty(flushData.getLabel())) {
            return;
        }
        stopScheduler();
        LOG.debug(String.format("Async stream load: rows[%d] bytes[%d] label[%s].", flushData.getRows().size(), flushData.getBytes(), flushData.getLabel()));
        for (int i = 0; i <= writerOptions.getMaxRetries(); i++) {
            try {
                // flush to StarRocks with stream load
                starrocksStreamLoadVisitor.doStreamLoad(flushData);
                LOG.info(String.format("Async stream load finished: label[%s].", flushData.getLabel()));
                startScheduler();
                break;
            }
            catch (Exception e) {
                LOG.warn("Failed to flush batch data to StarRocks, retry times = {}", i, e);
                if (i >= writerOptions.getMaxRetries()) {
                    throw new IOException(e);
                }
                if (e instanceof StarRocksStreamLoadFailedException && ((StarRocksStreamLoadFailedException) e).needReCreateLabel()) {
                    String newLabel = createBatchLabel();
                    LOG.warn(String.format("Batch label changed from [%s] to [%s]", flushData.getLabel(), newLabel));
                    flushData.setLabel(newLabel);
                }
                try {
                    TimeUnit.SECONDS.sleep(Math.min(i + 1, 10));
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Unable to flush, interrupted while doing another attempt", e);
                }
            }
        }
    }

    private void checkFlushException()
    {
        if (flushException != null) {
            throw new RuntimeException("Writing records to StarRocks failed.", flushException);
        }
    }
}
