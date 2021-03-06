package com.google.common.p005io;

import com.google.common.base.Preconditions;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;

/* renamed from: com.google.common.io.CharSink */
public abstract class CharSink implements OutputSupplier<Writer> {
    public abstract Writer openStream() throws IOException;

    @Deprecated
    public final Writer getOutput() throws IOException {
        return openStream();
    }

    public Writer openBufferedStream() throws IOException {
        Writer openStream = openStream();
        return openStream instanceof BufferedWriter ? (BufferedWriter) openStream : new BufferedWriter(openStream);
    }

    public void write(CharSequence charSequence) throws IOException {
        Preconditions.checkNotNull(charSequence);
        Closer create = Closer.create();
        try {
            Writer writer = (Writer) create.register(openStream());
            writer.append(charSequence);
            writer.flush();
            create.close();
        } catch (Throwable th) {
            create.close();
            throw th;
        }
    }

    public void writeLines(Iterable<? extends CharSequence> iterable) throws IOException {
        writeLines(iterable, System.getProperty("line.separator"));
    }

    public void writeLines(Iterable<? extends CharSequence> iterable, String str) throws IOException {
        Preconditions.checkNotNull(iterable);
        Preconditions.checkNotNull(str);
        Closer create = Closer.create();
        try {
            Writer writer = (Writer) create.register(openBufferedStream());
            for (CharSequence append : iterable) {
                writer.append(append).append(str);
            }
            writer.flush();
            create.close();
        } catch (Throwable th) {
            create.close();
            throw th;
        }
    }

    public long writeFrom(Readable readable) throws IOException {
        Preconditions.checkNotNull(readable);
        Closer create = Closer.create();
        try {
            Writer writer = (Writer) create.register(openStream());
            long copy = CharStreams.copy(readable, (Appendable) writer);
            writer.flush();
            create.close();
            return copy;
        } catch (Throwable th) {
            create.close();
            throw th;
        }
    }
}
