# mp4frag
Project for debugging AAC+h264 AMF encoded data into a fragmented mp4 container.


## Data File
The test file was captured on 25 July 2019 from a live stream of me with music playing in the background with a duration of just over one minute. The a/v configuration is as follows:
 * Video codec - h.264 profile: 42c01f width: 640 height: 480 fps: ~25
 * Audio codec - AAC profile: 2 frequency index: 3 sample rate: 48000 channel config: 1


## Main Class
I run in Eclipse using the Maven project importer, but should you want to run in some other IDE, the main class is `org.gregoire.debug.App`.


### Fragmented MP4 File
This is the file that was written: [mondain](mondain.mp4)


## Primary Dependencies
 * [Mina](https://mina.apache.org/)
 * [MP4Parser](https://github.com/sannies/mp4parser)
 * [Red5](https://github.com/red5)