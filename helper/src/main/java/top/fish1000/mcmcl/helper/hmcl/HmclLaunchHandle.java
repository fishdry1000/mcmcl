package top.fish1000.mcmcl.helper.hmcl;

/** Opaque handle retained by an HMCL adapter for one accepted launch. */
@FunctionalInterface
public interface HmclLaunchHandle {
    void stop() throws Exception;
}
