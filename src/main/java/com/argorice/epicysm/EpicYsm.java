package com.argorice.epicysm;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import com.argorice.epicysm.client.EpicYsmClient;

/** EpicYSM - lets Yes Steve Model characters fight with Epic Fight animations. */
@Mod(EpicYsm.MODID)
public final class EpicYsm {
    public static final String MODID = "epicysm";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EpicYsm() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            EpicYsmClient.init(FMLJavaModLoadingContext.get().getModEventBus());
        }
    }
}
