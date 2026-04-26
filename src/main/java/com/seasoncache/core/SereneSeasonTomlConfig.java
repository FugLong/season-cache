package com.seasoncache.core;

import com.seasoncache.SeasonCacheMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Season-rule input loader.
 *
 * Despite the legacy class name, this no longer reads Serene Seasons' TOML file.
 * It pulls the live values from SS's loaded config/API. Reflection is used here
 * deliberately so Season Cache does not need GlitchCore's config classes on its
 * compile classpath just to inspect the already-loaded Serene Seasons config.
 */
public final class SereneSeasonTomlConfig {
    private static final String RULE_MODEL_VERSION = "seasoncache-rule-model-v2";

    private SereneSeasonTomlConfig() {}

    public static RuntimeTypes.SeasonRuleConfig load(MinecraftServer server) {
        boolean generateSnowIce = true;
        Map<String, Float> adjustments = new LinkedHashMap<>();
        List<String> orderedSubSeasons = new ArrayList<>();

        try {
            Class<?> subSeasonClass = Class.forName("sereneseasons.api.season.Season$SubSeason");
            Method valuesMethod = subSeasonClass.getMethod("values");
            Method asStringMethod = null;
            try {
                asStringMethod = subSeasonClass.getMethod("asString");
            } catch (NoSuchMethodException ignored) {
            }
            Object values = valuesMethod.invoke(null);
            int length = Array.getLength(values);

            Object seasonsConfig = null;
            try {
                Class<?> modConfigClass = Class.forName("sereneseasons.init.ModConfig");
                Field seasonsField = modConfigClass.getField("seasons");
                seasonsConfig = seasonsField.get(null);
            } catch (Exception ignored) {
            }

            Method getSeasonPropertiesMethod = null;
            Field generateSnowAndIceField = null;
            Method biomeTempAdjustmentMethod = null;
            if (seasonsConfig != null) {
                try {
                    Class<?> seasonsConfigClass = seasonsConfig.getClass();
                    getSeasonPropertiesMethod = seasonsConfigClass.getMethod("getSeasonProperties", subSeasonClass);
                    generateSnowAndIceField = seasonsConfigClass.getField("generateSnowAndIce");
                    generateSnowIce = generateSnowAndIceField.getBoolean(seasonsConfig);
                } catch (Exception ignored) {
                }
            }

            for (int i = 0; i < length; i++) {
                Object subSeason = Array.get(values, i);
                String key;
                try {
                    if (asStringMethod != null) {
                        key = String.valueOf(asStringMethod.invoke(subSeason));
                    } else if (subSeason instanceof Enum<?> enumValue) {
                        key = enumValue.name().toLowerCase(java.util.Locale.ROOT);
                    } else {
                        key = String.valueOf(subSeason).toLowerCase(java.util.Locale.ROOT);
                    }
                } catch (Exception e) {
                    if (subSeason instanceof Enum<?> enumValue) {
                        key = enumValue.name().toLowerCase(java.util.Locale.ROOT);
                    } else {
                        key = String.valueOf(subSeason).toLowerCase(java.util.Locale.ROOT);
                    }
                }
                orderedSubSeasons.add(key);

                float adjustment = 0.0f;
                if (seasonsConfig != null && getSeasonPropertiesMethod != null) {
                    try {
                        Object properties = getSeasonPropertiesMethod.invoke(seasonsConfig, subSeason);
                        if (properties != null) {
                            if (biomeTempAdjustmentMethod == null) {
                                biomeTempAdjustmentMethod = properties.getClass().getMethod("biomeTempAdjustment");
                            }
                            Object value = biomeTempAdjustmentMethod.invoke(properties);
                            if (value instanceof Number number) {
                                adjustment = number.floatValue();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                adjustments.put(key, adjustment);
            }
        } catch (Exception e) {
            SeasonCacheMod.LOGGER.warn("Season Cache: failed to read Serene Seasons live rule inputs, falling back to defaults.", e);
            if (orderedSubSeasons.isEmpty()) {
                orderedSubSeasons.addAll(List.of(
                        "early_spring", "mid_spring", "late_spring",
                        "early_summer", "mid_summer", "late_summer",
                        "early_autumn", "mid_autumn", "late_autumn",
                        "early_winter", "mid_winter", "late_winter"
                ));
                for (String key : orderedSubSeasons) {
                    adjustments.putIfAbsent(key, 0.0f);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(RULE_MODEL_VERSION);
        sb.append("|generate_snow_ice=").append(generateSnowIce);
        for (String key : orderedSubSeasons) {
            sb.append('|').append(key).append('=').append(adjustments.getOrDefault(key, 0.0f));
        }
        String hash = Integer.toHexString(sb.toString().hashCode());

        return new RuntimeTypes.SeasonRuleConfig(generateSnowIce, adjustments, orderedSubSeasons, hash, null);
    }

    public static String readCachedHash(MinecraftServer server) {
        Path hashPath = hashPath(server);
        if (!Files.exists(hashPath)) return null;
        try {
            return Files.readString(hashPath).trim();
        } catch (IOException e) {
            return null;
        }
    }

    public static void writeCachedHash(MinecraftServer server, String hash) {
        Path hashPath = hashPath(server);
        try {
            Files.createDirectories(hashPath.getParent());
            Files.writeString(hashPath, Objects.requireNonNullElse(hash, ""));
        } catch (IOException ignored) {
        }
    }

    private static Path hashPath(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT)
                .resolve("seasoncache")
                .resolve("ss_rule_hash.txt");
    }
}
