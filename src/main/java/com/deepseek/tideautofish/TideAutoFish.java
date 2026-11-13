package com.deepseek.tideautofish;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(TideAutoFish.MODID)
public final class TideAutoFish {
    public static final String MODID = "tideautofish";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public TideAutoFish() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC, "tideautofish-client.toml");
        }
    }
}
