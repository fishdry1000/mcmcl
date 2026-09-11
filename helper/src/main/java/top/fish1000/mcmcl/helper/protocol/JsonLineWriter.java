package top.fish1000.mcmcl.helper.protocol;

import java.io.PrintStream;
import java.util.Map;

/** Serializes one protocol object per line and never interleaves async events. */
public final class JsonLineWriter {
    private final PrintStream output;

    public JsonLineWriter(PrintStream output) {
        this.output = output;
    }

    public synchronized void write(Map<String, Object> object) {
        output.println(Json.stringify(object));
        output.flush();
    }
}
