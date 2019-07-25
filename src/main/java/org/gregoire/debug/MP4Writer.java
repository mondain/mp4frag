package org.gregoire.debug;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.mina.core.buffer.IoBuffer;
import org.bouncycastle.util.encoders.Hex;
import org.mp4parser.streaming.StreamingTrack;
import org.mp4parser.streaming.input.aac.AACStreamingTrack;
import org.mp4parser.streaming.input.h264.AnnexBStreamingTrack;
import org.mp4parser.streaming.output.mp4.FragmentedMp4Writer;
import org.red5.codec.AudioCodec;
import org.red5.codec.VideoCodec;
import org.red5.io.IStreamableFile;
import org.red5.io.ITag;
import org.red5.io.ITagWriter;
import org.red5.io.mp4.IMP4;
import org.red5.media.processor.IPostProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Writer is used to write the contents of an MP4 file
 *
 * @author Paul Gregoire
 */
public class MP4Writer implements ITagWriter {

    private static Logger log = LoggerFactory.getLogger(MP4Writer.class);

    private final static boolean isTrace = log.isTraceEnabled();

    private final static boolean isDebug = log.isDebugEnabled();

    public static final String[] AVC_NAL_TYPES = { "Undefined 0", "Coded Slice 1", "Partition A 2", "Partition B 3", "Partition C 4", "IDR 5", "SEI 6", "SPS 7", "PPS 8", "AUD 9", "End of sequence 10", "End of stream 11", "Filler data 12", "SPS extension 13", "SVC Prefix NAL unit 14", "SVC Subset SPS 15", "Depth parameter set 16", "Reserved 17", "Reserved 18",
            "SVC Coded slice of an auxiliary coded picture without partitioning 19", "Coded slice extension 20", "Coded slice extension for depth view components 21", "Reserved 22", "Reserved 23", "STAP-A 24", "STAP-B 25", "Unspecified 26", "Unspecified 27", "FUA 28", "Unspecified 29", "SVC PACSI 30", "NI-MTAP 31" };

    /**
     * Executor service for tasks within this MP4Writer. Each writer manages its own futures.
     */
    private ExecutorService executor = Executors.newFixedThreadPool(2);

    /**
     * MP4 object
     */
    private static IMP4 mp4;

    /**
     * Number of bytes written
     */
    private volatile long bytesWritten;

    /**
     * Position in file
     */
    private int offset;

    /**
     * Id of the audio codec used
     */
    private volatile int audioCodecId = -1;

    /**
     * Id of the video codec used
     */
    private volatile int videoCodecId = -1;

    /**
     * If audio configuration data has been written
     */
    private AtomicBoolean audioConfigWritten = new AtomicBoolean(false);

    /**
     * If video configuration data has been written
     */
    private AtomicBoolean videoConfigWritten = new AtomicBoolean(false);

    // Path to the output file
    private Path filePath;

    private AACStreamingTrack aacTrack;

    private AnnexBStreamingTrack h264Track;

    private int audioSampleRate = 44100;

    /*
        1: 1 channel: front-center
        2: 2 channels: front-left, front-right
     */
    private int audioChannels = 1; // mono

    /*
        1: AAC Main
        2: AAC LC (Low Complexity)
        3: AAC SSR (Scalable Sample Rate)
        4: AAC LTP (Long Term Prediction)
        5: SBR (Spectral Band Replication)
        6: AAC Scalable
     */
    private int aacProfile = 2; // LC

    private int aacFrequencyIndex = -1;

    private long avgAudioBitrate = 48000;

    private long maxAudioBitrate = 128000;

    /*  SPS vuiParams:
        if(timing_info_present_flag) {
            int num_units_in_tick=u(32,buf,StartBit);
            int time_scale=u(32,buf,StartBit);
            fps=time_scale/num_units_in_tick;
            int fixed_frame_rate_flag=u(1,buf,StartBit);
            if(fixed_frame_rate_flag) {
                fps=fps/2;
            }
        }
     */

    private int videoTimescale = 90000; // time_scale

    private int videoFrametick = 3600; // num_units_in_tick (non-fixed so no-divide-by 2)

    @SuppressWarnings("unused")
    private int fps = 25; // time_scale / num_units_in_tick / 2

    private FileOutputStream fos;

    private WritableByteChannel dataChannel;

    private FragmentedMp4Writer multiTrackFragmentedMp4Writer;

    private CopyOnWriteArrayList<Future<?>> futures = new CopyOnWriteArrayList<>();

    // used to signal the end of data
    public final static byte[] DATA_END_MARKER = new byte[0];

    /**
     * Creates writer implementation with for a given file
     * 
     * @param filePath
     *            path to existing file
     */
    public MP4Writer(String filePath) {
        this(Paths.get(filePath), false);
    }

    /**
     * Creates writer implementation with given file and flag indicating whether or not to append.
     *
     * MP4.java uses this constructor so we have access to the file object
     *
     * @param file
     *            File output stream
     * @param append
     *            true if append to existing file
     */
    public MP4Writer(File file, boolean append) {
        this(file.toPath(), append);
    }

    /**
     * Creates writer implementation with given file and flag indicating whether or not to append.
     *
     * MP4.java uses this constructor so we have access to the file object
     *
     * @param path
     *            File output path
     * @param append
     *            true if append to existing file
     */
    public MP4Writer(Path path, boolean append) {
        log.debug("Writing to: {}", path);
        filePath = path;
        if (append) {
            // XXX at some later point we could use a post-proc to concatenate mp4's
            throw new UnsupportedOperationException("MP4 append not supported");
        }
        log.debug("Writing to: {} {}", filePath, mp4);
        try {
            // instance streaming tracks for a/v
            h264Track = new AnnexBStreamingTrack();
            // since our vui params are bogus, we'll force 25fps for now 
            h264Track.setFrametick(videoFrametick);
            h264Track.setTimescale(videoTimescale);
            // assuming / expecting non 'fixed_frame_rate_flag' style media
            fps = videoTimescale / videoFrametick;
            aacTrack = new AACStreamingTrack(avgAudioBitrate, maxAudioBitrate);
            // create file output and its channel for the fragment writer
            fos = new FileOutputStream(filePath.toFile());
            dataChannel = fos.getChannel();
            // write moof and mdat boxes
            multiTrackFragmentedMp4Writer = new FragmentedMp4Writer(Arrays.<StreamingTrack> asList(h264Track, aacTrack), dataChannel);
            // submit (streaming tracks) and keep references of our futures
            futures.add(executor.submit(h264Track));
            futures.add(executor.submit(aacTrack));
        } catch (Exception e) {
            log.error("Failed to create MP4 writer", e);
        }
    }

    @Override
    public void writeHeader() throws IOException {
        // no-op
    }

    @Override
    public boolean writeStream(byte[] b) {
        // not supported
        return false;
    }

    @Override
    public boolean writeTag(byte type, IoBuffer data) throws IOException {
        // not supported
        return false;
    }

    @Override
    public boolean writeTag(ITag tag) throws IOException {
        log.trace("writeTag: {}", tag);
        long prevBytesWritten = bytesWritten;
        log.trace("Previous bytes written: {}", prevBytesWritten);
        // skip tags with no data
        int bodySize = tag.getBodySize();
        if (isTrace) {
            log.trace("Tag body size: {}", bodySize);
        }
        // ensure that the channel is still open
        if (dataChannel != null && dataChannel.isOpen()) {
            // get the data type
            byte dataType = tag.getDataType();
            // when tag is ImmutableTag which is in red5-server-common.jar, tag.getBody().reset() will throw InvalidMarkException because 
            // ImmutableTag.getBody() returns a new IoBuffer instance every time.
            IoBuffer tagBody = tag.getBody();
            if (isTrace) {
                log.trace("Tag body: {}", Hex.toHexString(tagBody.array()));
            }
            if (bodySize > 0) {
                byte[] data;
                // get the audio or video codec identifier
                if (dataType == ITag.TYPE_AUDIO) {
                    int id = tagBody.get() & 0xff; // must be unsigned
                    audioCodecId = (id & ITag.MASK_SOUND_FORMAT) >> 4;
                    log.trace("Audio codec id: {}", audioCodecId);
                    // if aac use defaults
                    if (audioCodecId == AudioCodec.AAC.getId()) {
                        log.trace("AAC audio type");
                        // this is aac data, so a config chunk should be written before any media data
                        if (tagBody.get() == 0) { // position 1
                            // when this config is written set the flag
                            audioConfigWritten.set(true);
                            // pull-out in-line config data
                            byte objAndFreq = tagBody.get();
                            byte freqAndChannel = tagBody.get();
                            aacProfile = ((objAndFreq & 0xFF) >> 3) & 0x1F;
                            aacFrequencyIndex = (objAndFreq & 0x7) << 1 | (freqAndChannel >> 7) & 0x1;
                            audioSampleRate = AACStreamingTrack.samplingFrequencyIndexMap.get(aacFrequencyIndex);
                            audioChannels = (freqAndChannel & 0x78) >> 3;
                            log.debug("AAC config - profile: {} freq: {} rate: {} channels: {}", new Object[] { aacProfile, aacFrequencyIndex, audioSampleRate, audioChannels });
                            // return true, the aac streaming track impl doesnt like our af 00 configs
                            return true;
                        } else if (!audioConfigWritten.get()) {
                            // reject packet since config hasnt been written yet
                            log.debug("Rejecting AAC data since config has not yet been written");
                            return false;
                        }
                    } else {
                        log.debug("Rejecting non-AAC data");
                        return false;
                    }
                    // add ADTS header
                    // ref https://wiki.multimedia.cx/index.php/ADTS
                    data = new byte[bodySize + 5]; // (bodySize - 2) + 7 (no protection)
                    if (aacFrequencyIndex == -1) {
                        aacFrequencyIndex = AACStreamingTrack.samplingFrequencyIndexMap.get(audioSampleRate);
                    }
                    int finallength = data.length;
                    data[0] = (byte) 0xff; // syncword 0xFFF, all bits must be 1
                    data[1] = (byte) 0b11110001; // mpeg v0, layer 0, protection absent
                    data[2] = (byte) (((aacProfile - 1) << 6) + (aacFrequencyIndex << 2) + (audioChannels >> 2));
                    data[3] = (byte) (((audioChannels & 0x3) << 6) + (finallength >> 11));
                    data[4] = (byte) ((finallength & 0x7ff) >> 3);
                    data[5] = (byte) (((finallength & 7) << 5) + 0x1f);
                    data[6] = (byte) 0xfc;
                    // slice out what we want, skip af 01; offset to 7
                    tagBody.get(data, 7, bodySize - 2);
                    if (isTrace) {
                        log.trace("ADTS body: {}", Hex.toHexString(data));
                    }
                    // write to audio out
                    aacTrack.add(data);
                    // increment bytes written
                    bytesWritten += data.length;
                } else if (dataType == ITag.TYPE_VIDEO) {
                    int id = tagBody.get() & 0xff; // must be unsigned
                    videoCodecId = id & ITag.MASK_VIDEO_CODEC;
                    log.trace("Video codec id: {}", videoCodecId);
                    if (videoCodecId == VideoCodec.AVC.getId()) {
                        // this is avc/h264 data, so a config chunk should be written before any media data
                        if (tagBody.get() == 0) { // position 1
                            log.debug("Config body: {}", tagBody);
                            // move past bytes we dont care about
                            tagBody.skip(9);
                            int spsLength = ((tagBody.get() & 0xFF) << 8) | (tagBody.get() & 0xFF);
                            byte[] sps = new byte[spsLength];
                            tagBody.get(sps, 0, spsLength);
                            // write sps
                            if (isTrace) {
                                log.trace("SPS - length: {} {}", spsLength, Hex.toHexString(sps));
                            }
                            writeNal(sps);
                            tagBody.get(); // pps count
                            int ppsLength = ((tagBody.get() & 0xFF) << 8) | (tagBody.get() & 0xFF);
                            byte[] pps = new byte[ppsLength];
                            tagBody.get(pps, 0, ppsLength);
                            // write pps
                            if (isTrace) {
                                log.trace("PPS - length: {} {}", ppsLength, Hex.toHexString(pps));
                            }
                            writeNal(pps);
                            // when this config is written set the flag
                            videoConfigWritten.set(true);
                        } else if (!videoConfigWritten.get()) {
                            // reject packet since config hasnt been written yet
                            log.debug("Rejecting AVC data since config has not yet been written");
                            return false;
                        } else {
                            // skip 3 bytes that we dont need
                            tagBody.skip(3);
                            // need at least the size of the frame, so 4 bytes minimum
                            while (tagBody.remaining() >= 4) {
                                // H264 data, size prepended
                                int frameSize = (tagBody.get() & 0xFF);
                                frameSize = frameSize << 8 | (tagBody.get() & 0xFF);
                                frameSize = frameSize << 8 | (tagBody.get() & 0xFF);
                                frameSize = frameSize << 8 | (tagBody.get() & 0xFF);
                                log.debug("Frame size: {}", frameSize);
                                if (frameSize > tagBody.remaining()) {
                                    log.warn("Bad h264 frame...frameSize {} available: {}", frameSize, tagBody.remaining());
                                    return false;
                                }
                                // get the frame
                                data = new byte[frameSize];
                                //log.debug("Data length: {}", data.length);
                                //log.trace("Position: {} remaining: {} {}", tagBody.position(), tagBody.remaining(), tagBody);
                                tagBody.get(data);
                                if (isDebug) {
                                    log.debug("NAL type: {}", AVC_NAL_TYPES[data[0] & 0x1f]);
                                }
                                // write video data
                                writeNal(data);
                            }
                        }
                    } else {
                        log.debug("Rejecting non-AVC data");
                        return false;
                    }
                }
            }
            if (log.isTraceEnabled()) {
                log.trace("Tag written, check value: {}", (bytesWritten - prevBytesWritten));
            }
            return true;
        } else {
            // throw an exception and let them know the cause
            throw new IOException("MP4 write channel has been closed", new ClosedChannelException());
        }
    }

    /**
     * Writes a nalu to the video output stream.
     * 
     * @param data
     * @throws IOException
     */
    private void writeNal(byte[] data) throws IOException {
        // write to video out
        h264Track.add(data);
        // increment bytes written
        bytesWritten += data.length;
    }

    /**
     * Decode an SEI nal; if we had one, we could get timing information when its absent from
     * the SPS nal vuiParams.
     * 
     * @param data
     */
    @SuppressWarnings("unused")
    private void decodeSEI(byte[] data) {
        /*
        int payload_type = 0;
        int payload_size = 0;
        int byte = 0xFF;
        av_log(s->avctx, AV_LOG_DEBUG, "Decoding SEI\n");
        while (byte == 0xFF) {
            byte = get_bits(gb, 8);
            payload_type += byte;
        }
        byte = 0xFF;
        while (byte == 0xFF) {
            byte = get_bits(gb, 8);
            payload_size += byte;
        }
        if (s->nal_unit_type == NAL_SEI_PREFIX) {
            if (payload_type == 256) {
                decode_nal_sei_decoded_picture_hash(s);
            } else if (payload_type == 45) {
                decode_nal_sei_frame_packing_arrangement(s);
            } else if (payload_type == 47) {
                decode_nal_sei_display_orientation(s);
            } else if (payload_type == 1){
                int ret = decode_pic_timing(s);
                av_log(s->avctx, AV_LOG_DEBUG, "Skipped PREFIX SEI %d\n", payload_type);
                skip_bits(gb, 8 * payload_size);
                return ret;
            } else if (payload_type == 129){
                active_parameter_sets(s);
                av_log(s->avctx, AV_LOG_DEBUG, "Skipped PREFIX SEI %d\n", payload_type);
            } else {
                av_log(s->avctx, AV_LOG_DEBUG, "Skipped PREFIX SEI %d\n", payload_type);
                skip_bits(gb, 8*payload_size);
            }
        } else { // nal_unit_type == NAL_SEI_SUFFIX
            if (payload_type == 132)
                decode_nal_sei_decoded_picture_hash(s);
            else {
                av_log(s->avctx, AV_LOG_DEBUG, "Skipped SUFFIX SEI %d\n", payload_type);
                skip_bits(gb, 8 * payload_size);
            }
        }
        */
    }

    @Override
    public void close() {
        log.debug("close");
        // spawn a thread to finish up our mp4 writer work
        try {
            // wrap-up writing to the mp4
            if (h264Track != null) {
                // add null entry to end blocking on take
                h264Track.add(DATA_END_MARKER);
            }
            if (aacTrack != null) {
                // add null entry to end blocking on take
                aacTrack.add(DATA_END_MARKER);
            }
            // clean up futures
            for (Future<?> future : futures) {
                log.debug("Future: {}", future);
                try {
                    // don't wait too long, 5 seconds seems like more than enough
                    future.get(5L, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    log.info("Timed out waiting for callable");
                } catch (Exception e) {
                    log.warn("Exception waiting for callable", e);
                }
                // if the future isnt done yet, cancel it
                if (!future.isDone()) {
                    log.debug("Cancelling {}", future);
                    future.cancel(false);
                }
            }
            log.debug("Exited future section");
            // write the remaining samples (also calls close on the tracks internally)
            multiTrackFragmentedMp4Writer.close();
            log.debug("Fragment writer closed");
        } catch (Exception e) {
            log.warn("Exception at close", e);
        } finally {
            if (fos != null) {
                // close output stream
                try {
                    fos.close();
                } catch (IOException e) {
                }
            }
            if (executor != null && !executor.isTerminated()) {
                executor.shutdown();
            }
            futures.clear();
        }
    }

    @Override
    public void addPostProcessor(IPostProcessor postProcessor) {
        throw new UnsupportedOperationException("Post-processing not supported for MP4");
    }

    @Override
    public long getBytesWritten() {
        return bytesWritten;
    }

    @Override
    public IStreamableFile getFile() {
        return mp4;
    }

    @Override
    public int getOffset() {
        return offset;
    }

    public int getAudioSampleRate() {
        return audioSampleRate;
    }

    public void setAudioSampleRate(int audioSampleRate) {
        this.audioSampleRate = audioSampleRate;
    }

    public int getAudioChannels() {
        return audioChannels;
    }

    public void setAudioChannels(int audioChannels) {
        this.audioChannels = audioChannels;
    }

    public int getAacProfile() {
        return aacProfile;
    }

    public void setAacProfile(int aacProfile) {
        this.aacProfile = aacProfile;
    }

    public int getAacFrequencyIndex() {
        return aacFrequencyIndex;
    }

    public void setAacFrequencyIndex(int aacFrequencyIndex) {
        this.aacFrequencyIndex = aacFrequencyIndex;
    }

    public long getAvgAudioBitrate() {
        return avgAudioBitrate;
    }

    public void setAvgAudioBitrate(long avgAudioBitrate) {
        this.avgAudioBitrate = avgAudioBitrate;
    }

    public long getMaxAudioBitrate() {
        return maxAudioBitrate;
    }

    public void setMaxAudioBitrate(long maxAudioBitrate) {
        this.maxAudioBitrate = maxAudioBitrate;
    }

    public int getVideoTimescale() {
        return videoTimescale;
    }

    public void setVideoTimescale(int videoTimescale) {
        this.videoTimescale = videoTimescale;
    }

    public int getVideoFrametick() {
        return videoFrametick;
    }

    public void setVideoFrametick(int videoFrametick) {
        this.videoFrametick = videoFrametick;
    }

}
