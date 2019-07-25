package org.mp4parser.streaming.input.aac;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import org.mp4parser.boxes.iso14496.part1.objectdescriptors.AudioSpecificConfig;
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.DecoderConfigDescriptor;
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.ESDescriptor;
import org.mp4parser.boxes.iso14496.part1.objectdescriptors.SLConfigDescriptor;
import org.mp4parser.boxes.iso14496.part12.SampleDescriptionBox;
import org.mp4parser.boxes.iso14496.part14.ESDescriptorBox;
import org.mp4parser.boxes.sampleentry.AudioSampleEntry;
import org.mp4parser.streaming.extensions.DefaultSampleFlagsTrackExtension;
import org.mp4parser.streaming.extensions.TrackIdTrackExtension;
import org.mp4parser.streaming.input.AbstractStreamingTrack;
import org.mp4parser.streaming.input.StreamingSampleImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AACStreamingTrack extends AbstractStreamingTrack implements Callable<Void> {

    private static Logger log = LoggerFactory.getLogger(AACStreamingTrack.class);

    public static Map<Integer, Integer> samplingFrequencyIndexMap = new HashMap<>();

    static {
        samplingFrequencyIndexMap.put(96000, 0);
        samplingFrequencyIndexMap.put(88200, 1);
        samplingFrequencyIndexMap.put(64000, 2);
        samplingFrequencyIndexMap.put(48000, 3);
        samplingFrequencyIndexMap.put(44100, 4);
        samplingFrequencyIndexMap.put(32000, 5);
        samplingFrequencyIndexMap.put(24000, 6);
        samplingFrequencyIndexMap.put(22050, 7);
        samplingFrequencyIndexMap.put(16000, 8);
        samplingFrequencyIndexMap.put(12000, 9);
        samplingFrequencyIndexMap.put(11025, 10);
        samplingFrequencyIndexMap.put(8000, 11);
        samplingFrequencyIndexMap.put(0x0, 96000);
        samplingFrequencyIndexMap.put(0x1, 88200);
        samplingFrequencyIndexMap.put(0x2, 64000);
        samplingFrequencyIndexMap.put(0x3, 48000);
        samplingFrequencyIndexMap.put(0x4, 44100);
        samplingFrequencyIndexMap.put(0x5, 32000);
        samplingFrequencyIndexMap.put(0x6, 24000);
        samplingFrequencyIndexMap.put(0x7, 22050);
        samplingFrequencyIndexMap.put(0x8, 16000);
        samplingFrequencyIndexMap.put(0x9, 12000);
        samplingFrequencyIndexMap.put(0xa, 11025);
        samplingFrequencyIndexMap.put(0xb, 8000);
    }

    private LinkedBlockingQueue<byte[]> inputQueue = new LinkedBlockingQueue<>();

    CountDownLatch gotFirstSample = new CountDownLatch(1);

    SampleDescriptionBox stsd = null;

    private boolean closed;

    private AdtsHeader firstHeader;

    private String lang = "eng";

    private long avgBitrate;

    private long maxBitrate;

    public AACStreamingTrack(long avgBitrate, long maxBitrate) {
        this.avgBitrate = avgBitrate;
        this.maxBitrate = maxBitrate;
        DefaultSampleFlagsTrackExtension defaultSampleFlagsTrackExtension = new DefaultSampleFlagsTrackExtension();
        defaultSampleFlagsTrackExtension.setIsLeading(2);
        defaultSampleFlagsTrackExtension.setSampleDependsOn(2);
        defaultSampleFlagsTrackExtension.setSampleIsDependedOn(2);
        defaultSampleFlagsTrackExtension.setSampleHasRedundancy(2);
        defaultSampleFlagsTrackExtension.setSampleIsNonSyncSample(false);
        this.addTrackExtension(defaultSampleFlagsTrackExtension);
    }

    @SuppressWarnings("unused")
    private static AdtsHeader readADTSHeader(byte[] entry) throws IOException {
        int cursor = 0;
        AdtsHeader hdr = new AdtsHeader();
        int x = entry[cursor++] & 0xff;
        if (x == 0xff) {
            x = entry[cursor++] & 0xff;
            if ((x & 0xF0) != 0xF0) {
                throw new IOException("Syncword missing ending bXX");
            }
        } else {
            throw new IOException("Expected Syncword bXXXX");
        }
        hdr.mpegVersion = (x & 0x8) >> 3;
        hdr.layer = (x & 0x6) >> 1;
        ; // C
        hdr.protectionAbsent = (x & 0x1); // D
        //log.debug("MPEG ver: {} layer: {} protection absent: {}", hdr.mpegVersion, hdr.layer, hdr.protectionAbsent);
        x = entry[cursor++];
        hdr.profile = ((x & 0xc0) >> 6) + 1; // E
        //log.debug("Profile {}", audioObjectTypes.get(hdr.profile));
        hdr.sampleFrequencyIndex = (x & 0x3c) >> 2;
        assert hdr.sampleFrequencyIndex != 15;
        hdr.sampleRate = samplingFrequencyIndexMap.get(hdr.sampleFrequencyIndex); // F
        //log.debug("Sample rate: {}", hdr.sampleRate);
        hdr.channelconfig = (x & 1) << 2; // H
        x = entry[cursor++];
        hdr.channelconfig += (x & 0xc0) >> 6;
        //log.debug("channelconfig: {}", hdr.channelconfig);
        hdr.original = (x & 0x20) >> 5; // I
        hdr.home = (x & 0x10) >> 4; // J
        hdr.copyrightedStream = (x & 0x8) >> 3; // K
        hdr.copyrightStart = (x & 0x4) >> 2; // L
        hdr.frameLength = (x & 0x3) << 9; // M
        x = entry[cursor++];
        hdr.frameLength += (x << 3);
        x = entry[cursor++];
        hdr.frameLength += (x & 0xe0) >> 5;
        log.debug("frameLength: {}", hdr.frameLength);
        hdr.bufferFullness = (x & 0x1f) << 6;
        x = entry[cursor++];
        hdr.bufferFullness += (x & 0xfc) >> 2;
        hdr.numAacFramesPerAdtsFrame = ((x & 0x3)) + 1;
        //log.debug("numAacFramesPerAdtsFrame: {}", hdr.numAacFramesPerAdtsFrame);
        if (hdr.numAacFramesPerAdtsFrame != 1) {
            throw new IOException("This muxer can only work with 1 AAC frame per ADTS frame");
        }
        if (hdr.protectionAbsent == 0) {
            int crc1 = entry[cursor++];
            int crc2 = entry[cursor++];
        }
        return hdr;
    }

    public boolean isClosed() {
        return closed;
    }

    public synchronized SampleDescriptionBox getSampleDescriptionBox() {
        waitForFirstSample();
        if (stsd == null) {
            stsd = new SampleDescriptionBox();
            AudioSampleEntry audioSampleEntry = new AudioSampleEntry("mp4a");
            if (firstHeader.channelconfig == 7) {
                audioSampleEntry.setChannelCount(8);
            } else {
                audioSampleEntry.setChannelCount(firstHeader.channelconfig);
            }
            audioSampleEntry.setSampleRate(firstHeader.sampleRate);
            audioSampleEntry.setDataReferenceIndex(1);
            audioSampleEntry.setSampleSize(16);

            ESDescriptorBox esds = new ESDescriptorBox();
            ESDescriptor descriptor = new ESDescriptor();
            descriptor.setEsId(0);

            SLConfigDescriptor slConfigDescriptor = new SLConfigDescriptor();
            slConfigDescriptor.setPredefined(2);
            descriptor.setSlConfigDescriptor(slConfigDescriptor);

            DecoderConfigDescriptor decoderConfigDescriptor = new DecoderConfigDescriptor();
            decoderConfigDescriptor.setObjectTypeIndication(0x40);
            decoderConfigDescriptor.setStreamType(5);
            decoderConfigDescriptor.setBufferSizeDB(1536);
            decoderConfigDescriptor.setMaxBitRate(maxBitrate);
            decoderConfigDescriptor.setAvgBitRate(avgBitrate);

            AudioSpecificConfig audioSpecificConfig = new AudioSpecificConfig();
            audioSpecificConfig.setOriginalAudioObjectType(2); // AAC LC
            audioSpecificConfig.setSamplingFrequencyIndex(firstHeader.sampleFrequencyIndex);
            audioSpecificConfig.setChannelConfiguration(firstHeader.channelconfig);
            decoderConfigDescriptor.setAudioSpecificInfo(audioSpecificConfig);

            descriptor.setDecoderConfigDescriptor(decoderConfigDescriptor);

            esds.setEsDescriptor(descriptor);

            audioSampleEntry.addBox(esds);
            stsd.addBox(audioSampleEntry);

        }
        return stsd;
    }

    void waitForFirstSample() {
        try {
            gotFirstSample.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public long getTimescale() {
        waitForFirstSample();
        return firstHeader.sampleRate;
    }

    public String getHandler() {
        return "soun";
    }

    public String getLanguage() {
        return lang;
    }

    public void setLanguage(String lang) {
        this.lang = lang;
    }

    public void close() throws IOException {
        closed = true;
    }

    /**
     * Adds an entry to the input queue.
     * 
     * @param entry
     * @return true if added and false otherwise
     */
    public boolean add(byte[] entry) {
        return inputQueue.offer(entry);
    }

    public Void call() {
        AdtsHeader header;
        try {
            // loop until we get a null entry or are closed / interrupted
            byte[] entry;
            while (!closed) {
                entry = inputQueue.take();
                if (entry != null && entry.length > 0) {
                    if ((header = readADTSHeader(entry)) != null) {
                        if (firstHeader == null) {
                            firstHeader = header;
                            gotFirstSample.countDown();
                        }
                        ByteBuffer frame = ByteBuffer.wrap(entry, header.getSize(), (header.frameLength - header.getSize()));
                        sampleSink.acceptSample(new StreamingSampleImpl(frame, 1024), this);
                    }
                } else {
                    break;
                }
            }
        } catch (InterruptedException e) {
            log.warn("Exception in take loop", e);
        } catch (IOException e) {
            log.warn("Exception consuming frame", e);
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
            return "AACStreamingTrack{trackId=" + trackIdTrackExtension.getTrackId() + "}";
        } else {
            return "AACStreamingTrack{}";
        }
    }

}
