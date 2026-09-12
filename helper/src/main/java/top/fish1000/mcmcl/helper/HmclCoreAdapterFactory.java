package top.fish1000.mcmcl.helper;

import top.fish1000.mcmcl.helper.hmcl.HmclCoreAdapter;
import top.fish1000.mcmcl.helper.hmcl.HmclCoreAdapterProvider;
import top.fish1000.mcmcl.helper.hmcl.UnavailableHmclCoreAdapter;
import top.fish1000.mcmcl.helper.repository.RepositoryInstanceCatalog;

import java.lang.reflect.Constructor;
import java.nio.file.Path;

/**
 * Loads the profile-only HMCL implementation without giving the default
 * protocol build a compile-time dependency on HMCL classes.
 */
public final class HmclCoreAdapterFactory {
    private static final String PROFILE_ADAPTER =
            "top.fish1000.mcmcl.helper.hmcl.RealHmclCoreAdapterProvider";

    private HmclCoreAdapterFactory() {
    }

    public static HmclCoreAdapter create(
            Path repository,
            RepositoryInstanceCatalog catalog,
            String downloadProvider) {
        try {
            Class<?> type = Class.forName(PROFILE_ADAPTER, true, HmclCoreAdapterFactory.class.getClassLoader());
            Constructor<?> constructor = type.getConstructor();
            HmclCoreAdapterProvider provider = (HmclCoreAdapterProvider) constructor.newInstance();
            return provider.create(repository, downloadProvider);
        } catch (ClassNotFoundException e) {
            return new UnavailableHmclCoreAdapter(catalog);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException(
                    "HMCL profile is present but its runtime dependencies could not be loaded", e);
        }
    }
}
