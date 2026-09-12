package top.fish1000.mcmcl.helper.hmcl;

import top.fish1000.mcmcl.helper.repository.InstanceDescriptor;

import java.util.List;

/**
 * The only seam where HMCL Core is allowed to enter the helper.
 *
 * <p>The default build supplies an unavailable implementation; the opt-in
 * HMCL profile supplies a real implementation in {@code src/hmcl/java}.
 * HMCL's repository, manifest, AuthInfo, LaunchOptions, DefaultLauncher and
 * ProcessListener are deliberately kept on that profile side of this seam.</p>
 */
public interface HmclCoreAdapter {
    List<InstanceDescriptor> listInstances() throws Exception;

    /** Short stable identity reported during the protocol handshake. */
    default String backendName() {
        return "hmcl-core";
    }

    /** Whether launch is currently backed by a real HMCL implementation. */
    default boolean isLaunchAvailable() {
        return true;
    }

    default String unavailableMessage() {
        return "HMCL Core adapter is not configured";
    }

    /**
     * Starts an asynchronous HMCL launch.
     *
     * <p>The adapter owns the actual launch task.  Repository preparation and
     * downloads are intentionally outside this protocol. It must call
     * {@link HmclLaunchEventSink#started()} once the game process exists,
     * forward stdout/stderr as log callbacks, and call exit or error exactly
     * when the launch finishes.</p>
     */
    HmclLaunchHandle launch(HmclLaunchRequest request, HmclLaunchEventSink events) throws Exception;

    /** Cancels a pending launch or terminates a running instance. */
    void stop(String instanceId) throws Exception;

    /** Stops/cleans up adapter resources. */
    default void shutdown() throws Exception {
    }
}
