package top.fish1000.mcmcl.helper.hmcl;

/** Opaque handle retained by an HMCL adapter for one accepted install/repair. */
@FunctionalInterface
public interface HmclInstallHandle {
    void cancel() throws Exception;
}
