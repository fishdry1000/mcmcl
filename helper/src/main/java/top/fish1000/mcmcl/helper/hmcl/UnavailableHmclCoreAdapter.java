package top.fish1000.mcmcl.helper.hmcl;

import top.fish1000.mcmcl.helper.repository.InstanceDescriptor;
import top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog;

import java.util.List;

/**
 * Honest default until a pinned HMCL checkout is wired into the adapter.
 * Listing remains useful because it only inspects the standard repository
 * layout; launching is never silently delegated to launch.json or another
 * fallback.
 */
public final class UnavailableHmclCoreAdapter implements HmclCoreAdapter {
    private final RepositoryInstanceCatalog catalog;

    public UnavailableHmclCoreAdapter(RepositoryInstanceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<InstanceDescriptor> listInstances() throws Exception {
        return catalog.list();
    }

    @Override
    public boolean isLaunchAvailable() {
        return false;
    }

    @Override
    public String unavailableMessage() {
        return "HMCL Core adapter is not configured; no launch.json fallback is available";
    }

    @Override
    public HmclLaunchHandle launch(HmclLaunchRequest request, HmclLaunchEventSink events) {
        throw new UnsupportedOperationException(unavailableMessage());
    }

    @Override
    public void stop(String instanceId) {
        throw new UnsupportedOperationException("HMCL Core adapter is not configured");
    }
}
