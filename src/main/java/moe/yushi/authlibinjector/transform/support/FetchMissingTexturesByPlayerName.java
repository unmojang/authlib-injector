package moe.yushi.authlibinjector.transform.support;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.io.UncheckedIOException;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.log;

import moe.yushi.authlibinjector.transform.CallbackMethod;
import moe.yushi.authlibinjector.transform.TransformContext;
import moe.yushi.authlibinjector.transform.TransformUnit;
import moe.yushi.authlibinjector.yggdrasil.YggdrasilClient;
import moe.yushi.authlibinjector.yggdrasil.GameProfile;
import moe.yushi.authlibinjector.yggdrasil.GameProfile.PropertyValue;

public class FetchMissingTexturesByPlayerName implements TransformUnit {
    private static volatile YggdrasilClient yggdrasilClient;
    public static void setYggdrasilClient(YggdrasilClient yggdrasilClient) {
        FetchMissingTexturesByPlayerName.yggdrasilClient = yggdrasilClient;
    }

    private static final ConcurrentHashMap<String, Optional<UUID>> nameToUUIDCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Optional<PropertyValue>> uuidToTexturesCache = new ConcurrentHashMap<>();

    @CallbackMethod
    public static Object getPackedTextures(Object instance, Object profile) {
        try {
            try {
                Class<?> gameProfileClass = profile.getClass();

                Method getProperties = gameProfileClass.getMethod("getProperties");
                Object propertiesMap = getProperties.invoke(profile);

                Method containsKey = propertiesMap.getClass().getMethod("containsKey", Object.class);
                boolean hasTextures = (boolean) containsKey.invoke(propertiesMap, "textures");

                if (!hasTextures) {
                    Method getName = gameProfileClass.getMethod("getName");
                    String name = (String) getName.invoke(profile);

                    Optional<UUID> maybeUUID = nameToUUIDCache.computeIfAbsent(name, n -> {
                        try {
                            return yggdrasilClient.queryUUID(n);
                        } catch (UncheckedIOException e) {
                            return null;
                        }
                    });
                    if (maybeUUID != null && maybeUUID.isPresent()) {
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
                        if (maybeTextures != null && maybeTextures.isPresent()) {
                            PropertyValue textures = maybeTextures.get();
                            Class<?> propertyClass = profile.getClass()
                                    .getClassLoader()
                                    .loadClass("com.mojang.authlib.properties.Property");
                            Constructor<?> propertyCtor = propertyClass.getConstructor(String.class, String.class, String.class);
                            return propertyCtor.newInstance("textures", textures.value, textures.signature);
                        }
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }

            Method m = instance.getClass().getDeclaredMethod("getPackedTextures$original", profile.getClass());
            m.setAccessible(true);
            return m.invoke(instance, profile);
        } catch (Throwable e) {
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
                            hookedMethodVisitor.visitMethodInsn(INVOKESTATIC,
                                "moe/yushi/authlibinjector/transform/support/FetchMissingTexturesByPlayerName",
                                "getPackedTextures",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                false);

                            hookedMethodVisitor.visitTypeInsn(CHECKCAST, "com/mojang/authlib/properties/Property");
                            hookedMethodVisitor.visitInsn(ARETURN);
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
