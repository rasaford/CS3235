package com.google.common.p005io;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import javax.annotation.Nullable;

/* renamed from: com.google.common.io.MultiReader */
class MultiReader extends Reader {
    private Reader current;

    /* renamed from: it */
    private final Iterator<? extends CharSource> f142it;

    MultiReader(Iterator<? extends CharSource> it) throws IOException {
        this.f142it = it;
        advance();
    }

    private void advance() throws IOException {
        close();
        if (this.f142it.hasNext()) {
            this.current = ((CharSource) this.f142it.next()).openStream();
        }
    }

    public int read(@Nullable char[] cArr, int i, int i2) throws IOException {
        Reader reader = this.current;
        if (reader == null) {
            return -1;
        }
        int read = reader.read(cArr, i, i2);
        if (read != -1) {
            return read;
        }
        advance();
        return read(cArr, i, i2);
    }

    public long skip(long j) throws IOException {
        Preconditions.checkArgument(j >= 0, "n is negative");
        if (j > 0) {
            while (true) {
                Reader reader = this.current;
                if (reader == null) {
                    break;
                }
                long skip = reader.skip(j);
                if (skip > 0) {
                    return skip;
                }
                advance();
            }
        }
        return 0;
    }

    public boolean ready() throws IOException {
        Reader reader = this.current;
        return reader != null && reader.ready();
    }

    public void close() throws IOException {
        Reader reader = this.current;
        if (reader != null) {
            try {
                reader.close();
            } finally {
                this.current = null;
            }
        }
    }
}
