package org.gregoire.debug;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.red5.io.ITag;
import org.red5.server.stream.consumer.ImmutableTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the dat file and sends the tags to the mp4 writer.
 *
 * @author Paul Gregoire
 */
public class App {

    private static Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws IOException {
        // create our mp4 writer
        MP4Writer writer = new MP4Writer("target/output.mp4");        
        // set up our dat file processing
        RandomAccessFile raf = new RandomAccessFile("src/main/resources/rawtags-out.dat", "r");
        // loop sentinel
        boolean processTags = true;
        do {
            try {
                // data type 1 byte + timestamp 4 bytes + body size
                int tagLength = raf.readInt();
                byte dataType = raf.readByte();
                int timestamp = raf.readInt();
                int bodySize = raf.readInt();
                byte[] body = new byte[bodySize];
                int readBytes = raf.read(body);
                assert readBytes == bodySize;
                assert tagLength == 1 + 4 + 4 + readBytes;
                // build a tag
                ITag tag = ImmutableTag.build(dataType, timestamp, body);
                // write the tag
                writer.writeTag(tag);
            } catch (EOFException e) {
                log.info("End of file reached");
                processTags = false;
            } catch (IOException e) {
                log.warn("Exception processing tags", e);
                processTags = false;
            }
        } while (processTags);
        // close when done reading
        if (raf != null) {
            raf.close();
        }
        // close the writer
        writer.close();
    }

}
