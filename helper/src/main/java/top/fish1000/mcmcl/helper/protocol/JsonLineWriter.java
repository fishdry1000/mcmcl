package top.fish1000.mcmcl.helper.protocol;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Serializes one protocol object per line and never interleaves async events. */
public final class JsonLineWriter {
    private final PrintWriter output;

    public JsonLineWriter(OutputStream output) {
        // System.out uses the host console charset on some Windows/JDK
        // combinations, even when it is connected to a pipe.  The protocol is
        // UTF-8 by contract, so encode it explicitly at the final byte boundary.
        this.output = new PrintWriter(
                new OutputStreamWriter(output, StandardCharsets.UTF_8), true);
    }

    public synchronized void write(Map<String, Object> object) {
        output.println(Json.stringify(object));
        output.flush();
    }
}
