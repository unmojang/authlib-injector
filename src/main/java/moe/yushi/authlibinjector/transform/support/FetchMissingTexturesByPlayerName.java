package moe.yushi.authlibinjector.transform.support;

import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

import static moe.yushi.authlibinjector.util.Logging.Level.ERROR;
import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.log;

import moe.yushi.authlibinjector.transform.CallbackMethod;
import moe.yushi.authlibinjector.transform.TransformContext;
import moe.yushi.authlibinjector.transform.TransformUnit;
import moe.yushi.authlibinjector.yggdrasil.GameProfile.PropertyValue;
import moe.yushi.authlibinjector.yggdrasil.GameProfile;
import moe.yushi.authlibinjector.yggdrasil.YggdrasilClient;

public class FetchMissingTexturesByPlayerName implements TransformUnit {
    private static volatile YggdrasilClient yggdrasilClient;
    public static void setYggdrasilClient(YggdrasilClient yggdrasilClient) {
        FetchMissingTexturesByPlayerName.yggdrasilClient = yggdrasilClient;
    }

    private static final ConcurrentHashMap<String, Optional<UUID>> nameToUUIDCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Optional<PropertyValue>> uuidToTexturesCache = new ConcurrentHashMap<>();

    public static Object getMissingTexturesProperty(Object profile) {
        // (com.mojang.authlib.GameProfile) -> com.mojang.authlib.properties.Property
        // Fetches missing textures for a GameProfile by player name via the yggdrasilClient.
        try {
            // If the GameProfile already has textures, return null.
            Class<?> gameProfileClass = profile.getClass();
            Method getProperties = gameProfileClass.getMethod("getProperties");
            Object propertiesMap = getProperties.invoke(profile);
            Method containsKey = propertiesMap.getClass().getMethod("containsKey", Object.class);
            boolean hasTextures = (boolean) containsKey.invoke(propertiesMap, "textures");
            if (hasTextures) {
                return null;
            }

            Method getName = gameProfileClass.getMethod("getName");
            String name = (String) getName.invoke(profile);
            Optional<UUID> maybeUUID = nameToUUIDCache.computeIfAbsent(name, n -> {
                try {
                    return yggdrasilClient.queryUUID(n);
                } catch (UncheckedIOException e) {
                    return null;
                }
            });
            if (maybeUUID == null || !maybeUUID.isPresent()) {
                return null;
            }
            UUID uuid = maybeUUID.get();

            Optional<PropertyValue> maybeTextures = uuidToTexturesCache.computeIfAbsent(uuid, u -> {
                Optional<GameProfile> maybeFullProfile;
                try {
                    maybeFullProfile = yggdrasilClient.queryProfile(u, true);
                } catch (UncheckedIOException e) {
                    return null;
                }
                return maybeFullProfile.map(fullProfile -> {
                    return fullProfile.properties.get("textures");
                });
            });
            if (maybeTextures == null || !maybeTextures.isPresent()) {
                return null;
            }
            PropertyValue textures = maybeTextures.get();

            log(INFO, "Successfully fetched missing textures for player " + name);
            Class<?> propertyClass = profile.getClass()
                .getClassLoader()
                .loadClass("com.mojang.authlib.properties.Property");
            Constructor<?> propertyConstructor = propertyClass.getConstructor(String.class, String.class, String.class);
            return propertyConstructor.newInstance("textures", textures.value, textures.signature);
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }

    @CallbackMethod
    public static Object getTextures(Object instance, Object profile, boolean requireSecure) {
        try {
            Object property = getMissingTexturesProperty(profile);
            if (property != null) {
                // Fill in the existing GameProfile with the missing textures
                Class<?> gameProfileClass = profile.getClass();

                Method getProperties = gameProfileClass.getMethod("getProperties");
                Object propertiesMap = getProperties.invoke(profile);

                Class<?> propertiesMapClass = propertiesMap.getClass();
                Method removeAll = propertiesMapClass.getMethod("removeAll", Object.class);
                removeAll.invoke(propertiesMap, "textures");
                Method put = propertiesMapClass.getMethod("put", Object.class, Object.class);
                put.invoke(propertiesMap, "textures", property);
            }

            Method m = instance.getClass().getDeclaredMethod("getTextures$original", profile.getClass(), boolean.class);
            m.setAccessible(true);
            return m.invoke(instance, profile, requireSecure);
        } catch (Throwable e) {
            log(ERROR, "Error fetching missing textures:");
            e.printStackTrace();
            return null;
        }
    }

    @CallbackMethod
    public static Object getPackedTextures(Object instance, Object profile) {
        try {
            Object property = getMissingTexturesProperty(profile);
            if (property != null) {
                return property;
            }

            Method m = instance.getClass().getDeclaredMethod("getPackedTextures$original", profile.getClass());
            m.setAccessible(true);
            return m.invoke(instance, profile);
        } catch (Throwable e) {
            log(ERROR, "Error fetching missing textures:");
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public Optional<ClassVisitor> transform(ClassLoader classLoader, String className, ClassVisitor writer, TransformContext ctx) {
        if ("com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService".equals(className)) {
            return Optional.of(new ClassVisitor(ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    if ("getPackedTextures".equals(name) &&
                        "(Lcom/mojang/authlib/GameProfile;)Lcom/mojang/authlib/properties/Property;".equals(desc)) {

                        ctx.markModified();

                        MethodVisitor originalMethodVisitor = super.visitMethod(access, name + "$original", desc, signature, exceptions);

                        MethodVisitor hookedMethodVisitor = super.visitMethod(access, name, desc, signature, exceptions);
                        if (hookedMethodVisitor != null) {
                            hookedMethodVisitor.visitCode();

                            // Load `this`
                            hookedMethodVisitor.visitVarInsn(ALOAD, 0);
                            // Load `profile`
                            hookedMethodVisitor.visitVarInsn(ALOAD, 1);
                            ctx.invokeCallback(hookedMethodVisitor, FetchMissingTexturesByPlayerName.class, "getPackedTextures");

                            hookedMethodVisitor.visitTypeInsn(CHECKCAST, "com/mojang/authlib/properties/Property");
                            hookedMethodVisitor.visitInsn(ARETURN);
                            hookedMethodVisitor.visitMaxs(2, 2);
                            hookedMethodVisitor.visitEnd();
                        }

                        return originalMethodVisitor;
                    } else if ("getTextures".equals(name) &&
                                "(Lcom/mojang/authlib/GameProfile;Z)Ljava/util/Map;".equals(desc)) {
                        ctx.markModified();

                        MethodVisitor originalMethodVisitor = super.visitMethod(access, name + "$original", desc, signature, exceptions);
                        MethodVisitor hookedMethodVisitor = super.visitMethod(access, name, desc, signature, exceptions);

                        if (hookedMethodVisitor != null) {
                            hookedMethodVisitor.visitCode();

                            // Load `this`
                            hookedMethodVisitor.visitVarInsn(ALOAD, 0);
                            // Load `profile`
                            hookedMethodVisitor.visitVarInsn(ALOAD, 1);
                            // Load `requireSecure`
                            hookedMethodVisitor.visitVarInsn(ILOAD, 2);
                            ctx.invokeCallback(hookedMethodVisitor, FetchMissingTexturesByPlayerName.class, "getTextures");

                            hookedMethodVisitor.visitTypeInsn(CHECKCAST, "java/util/Map");
                            hookedMethodVisitor.visitInsn(ARETURN);
                            hookedMethodVisitor.visitMaxs(3, 3);
                            hookedMethodVisitor.visitEnd();
                        }

                        return originalMethodVisitor;
                    }

                    return super.visitMethod(access, name, desc, signature, exceptions);
                }
            });
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "FetchMissingTexturesByPlayerName";
    }
}
