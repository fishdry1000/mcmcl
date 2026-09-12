package top.fish1000.mcmcl.helper.hmcl;

import java.nio.file.Path;

/** Profile-only provider; this class is absent from the default build. */
public final class RealHmclCoreAdapterProvider implements HmclCoreAdapterProvider {
    @Override
    public HmclCoreAdapter create(Path repository, String downloadProvider) {
        return new RealHmclCoreAdapter(repository, downloadProvider);
    }
}
