package retrofit.mime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import p008io.fabric.sdk.android.services.network.HttpRequest;

public final class FormUrlEncodedTypedOutput implements TypedOutput {
    final ByteArrayOutputStream content = new ByteArrayOutputStream();

    public String fileName() {
        return null;
    }

    public String mimeType() {
        return "application/x-www-form-urlencoded; charset=UTF-8";
    }

    public void addField(String str, String str2) {
        addField(str, true, str2, true);
    }

    public void addField(String str, boolean z, String str2, boolean z2) {
        if (str == null) {
            throw new NullPointerException(ProductCodesColumns.NAME);
        } else if (str2 != null) {
            if (this.content.size() > 0) {
                this.content.write(38);
            }
            if (z) {
                try {
                    str = URLEncoder.encode(str, HttpRequest.CHARSET_UTF8);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if (z2) {
                str2 = URLEncoder.encode(str2, HttpRequest.CHARSET_UTF8);
            }
            this.content.write(str.getBytes(HttpRequest.CHARSET_UTF8));
            this.content.write(61);
            this.content.write(str2.getBytes(HttpRequest.CHARSET_UTF8));
        } else {
            throw new NullPointerException("value");
        }
    }

    public long length() {
        return (long) this.content.size();
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        outputStream.write(this.content.toByteArray());
    }
}
