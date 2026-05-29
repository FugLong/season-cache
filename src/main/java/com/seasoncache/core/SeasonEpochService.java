package com.seasoncache.core;

import com.seasoncache.config.SeasonCacheConfig;
import com.seasoncache.integration.SeasonProvider;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

public final class SeasonEpochService {
    /**
     * Increment this value whenever epoch-local runtime semantics change.
     */
    public static final int SCHEMA_VERSION = 5;

    private final SeasonCacheConfig config;
    private final SeasonProvider seasonProvider;

    /**
     * Random salt generated once per server session. Including this in the epoch
     * hash makes cross-session epoch collisions impossible — chunks always
     * re-reconcile after a restart — and eliminates the within-session hash
     * collision risk that Objects.hash() carried for distinct season/config pairs.
     */
    private final int sessionSalt = new java.util.Random().nextInt();

    public SeasonEpochService(SeasonCacheConfig config, SeasonProvider seasonProvider) {
        this.config = config;
        this.seasonProvider = seasonProvider;
    }

    public int currentEpoch(ServerLevel world) {
        RuntimeTypes.SeasonSnapshot snapshot = this.seasonProvider.snapshot(world);
        return Objects.hash(
                this.sessionSalt,
                snapshot.seasonKey(),
                this.config.epochConfigHash(),
                SCHEMA_VERSION
        );
    }
}
