package org.mp4parser.streaming.input.h264;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;

import org.mp4parser.streaming.extensions.TrackIdTrackExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads H264 data from an Annex B InputStream.
 */
public class AnnexBStreamingTrack extends NalConsumingTrack implements Callable<Void> {

    private static Logger log = LoggerFactory.getLogger(AnnexBStreamingTrack.class);

    private LinkedBlockingQueue<byte[]> inputQueue = new LinkedBlockingQueue<>();

    private boolean closed;

    public AnnexBStreamingTrack() {
    }

    @Override
    public void close() throws IOException {
        closed = true;
    }

    /**
     * Adds a nal to the input queue. This is expected to be a raw-nalu with no prefix.
     * 
     * @param nal
     * @return true if added and false otherwise
     */
    public boolean add(byte[] nal) {
        return inputQueue.offer(nal);
    }

    public Void call() {
        byte[] nal;
        try {
            // loop until we get a null nal or are closed / interrupted
            while (!closed) {
                nal = inputQueue.take();
                if (nal != null && nal.length > 0) {
                    //log.info("NAL before consume {}", Hex.toHexString(nal));
                    ByteBuffer bb = ByteBuffer.wrap(nal);
                    consumeNal(bb);
                    //log.debug("NAL after consume {}", Hex.toHexString(bb.array()));
                } else {
                    // null nal, we're done here
                    break;
                }
            }
        } catch (InterruptedException e) {
            log.warn("Exception in take loop", e);
        } catch (IOException e) {
            log.warn("Exception consuming nal", e);
        }
        log.info("Pushing sample");
        try {
            pushSample(createSample(buffered, fvnd.sliceHeader, sliceNalUnitHeader), true, true);
        } catch (IOException e) {
            log.warn("Exception at exit", e);
        } finally {
            inputQueue.clear();
        }
        log.debug("Exit");
        return null;
    }

    @Override
    public String toString() {
        TrackIdTrackExtension trackIdTrackExtension = this.getTrackExtension(TrackIdTrackExtension.class);
        if (trackIdTrackExtension != null) {
            return "AnnexBStreamingTrack{trackId=" + trackIdTrackExtension.getTrackId() + "}";
        } else {
            return "AnnexBStreamingTrack{}";
        }
    }

}
