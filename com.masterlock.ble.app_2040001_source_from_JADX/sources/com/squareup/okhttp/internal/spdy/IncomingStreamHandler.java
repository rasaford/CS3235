package com.squareup.okhttp.internal.spdy;

import java.io.IOException;

public interface IncomingStreamHandler {
    public static final IncomingStreamHandler REFUSE_INCOMING_STREAMS = new IncomingStreamHandler() {
        public void receive(SpdyStream spdyStream) throws IOException {
            spdyStream.close(ErrorCode.REFUSED_STREAM);
        }
    };

    void receive(SpdyStream spdyStream) throws IOException;
}
