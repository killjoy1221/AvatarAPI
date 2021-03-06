package com.minelittlepony.avatar.impl.core.mixin;

import com.google.common.collect.Streams;
import com.minelittlepony.avatar.ServiceManager;
import com.minelittlepony.avatar.impl.INetworkPlayerInfo;
import com.minelittlepony.avatar.impl.MinecraftExecutors;
import com.minelittlepony.avatar.texture.TextureData;
import com.minelittlepony.avatar.texture.TextureService;
import com.minelittlepony.avatar.texture.TextureType;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Mixin(NetworkPlayerInfo.class)
public class MixinNetworkPlayerInfo implements INetworkPlayerInfo {

    @Shadow @Final private GameProfile gameProfile;

    @Shadow private Map<Type, ResourceLocation> playerTextures;
    @Shadow private boolean playerTexturesLoaded;
    private Map<TextureType, TextureData> customTextures = new HashMap<>();

    @SuppressWarnings("InvalidMemberReference") // mc-dev bug?
    @Redirect(
            method = {
                    "getLocationSkin",
                    "getLocationCape",
                    "getLocationElytra"
            }, at = @At(value = "INVOKE", target = "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    // synthetic
    private Object getSkin(Map<Type, ResourceLocation> playerTextures, Object key) {
        return getSkin(playerTextures, (Type) key);
    }

    // with generics
    private ResourceLocation getSkin(Map<Type, ResourceLocation> playerTextures, Type type) {
        return getPlayerTexture(TextureType.from(type))
                .map(TextureData::getLocation)
                .orElse(playerTextures.get(type));
    }

    @Inject(method = "getSkinType", at = @At("RETURN"), cancellable = true)
    private void getTextureModel(CallbackInfoReturnable<String> cir) {
        getPlayerTexture(TextureType.SKIN)
                .map(TextureData::getProfile)
                .map(p -> p.getMetadata("model").orElse("default"))
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "loadPlayerTextures",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/SkinManager;loadProfileTextures("
                            + "Lcom/mojang/authlib/GameProfile;"
                            + "Lnet/minecraft/client/resources/SkinManager$SkinAvailableCallback;"
                            + "Z)V",
                    shift = Shift.BEFORE))
    private void onLoadTexture(CallbackInfo ci) {
        ServiceManager.get(TextureService.class).ifPresent(tm ->
                tm.loadProfileTextures(gameProfile).thenAcceptAsync(m -> {
                    m.forEach((type, profile) -> tm.loadTexture(type, profile, customTextures::put));
                }, MinecraftExecutors.client()));
    }

    @Override
    public Optional<TextureData> getPlayerTexture(TextureType type) {
        return Optional.ofNullable(customTextures.get(type));
    }

    @Override
    public void deleteTextures() {
        TextureManager tm = Minecraft.getMinecraft().getTextureManager();
        Streams.concat(
                this.customTextures.values().stream().map(TextureData::getLocation),
                this.playerTextures.values().stream())
                .forEach(tm::deleteTexture);
        this.customTextures.clear();
        this.playerTextures.clear();
        this.playerTexturesLoaded = false;
    }
}
