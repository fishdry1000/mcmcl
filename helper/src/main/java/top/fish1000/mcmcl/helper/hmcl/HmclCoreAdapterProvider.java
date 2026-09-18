package top.fish1000.mcmcl.helper.hmcl;

import java.nio.file.Path;

/**
 * Factory seam implemented only by the opt-in HMCL source/dependency profile.
 */
public interface HmclCoreAdapterProvider {
    HmclCoreAdapter create(Path repository, String downloadProvider);
}
