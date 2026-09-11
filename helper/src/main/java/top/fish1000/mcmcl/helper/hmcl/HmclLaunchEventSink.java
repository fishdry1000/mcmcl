package top.fish1000.mcmcl.helper.hmcl;

/** Callback bridge from HMCL Core's process/task lifecycle to JSON events. */
public interface HmclLaunchEventSink {
    void started();

    void log(String line);

    void exit(int code);

    void error(String message);
}
