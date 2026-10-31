package io.github.zgxhzhr.maidfm.network;

import io.github.zgxhzhr.maidfm.data.MaidProfile;
import io.github.zgxhzhr.maidfm.data.MaidProfileView;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 女仆档案与档案视图的线格式编解码。
 *
 * <p>从 {@code MaidFilePackets} 拆出。档案本体是 NBT，视图是「扁平字段 + 档案 + 饰品图标 NBT 列表」。
 */
public final class MaidProfileCodec {

    /** 随档案视图发送的饰品图标件数上限 */
    public static final int MAX_BAUBLE_ICONS = 64;

    private MaidProfileCodec() {
    }

    private static void writeNullableUtf(FriendlyByteBuf buf, String s) {
        buf.writeBoolean(s != null);
        if (s != null) {
            buf.writeUtf(s, MaidFilePackets.MAX_TEXT_LEN);
        }
    }

    private static String readNullableUtf(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf(MaidFilePackets.MAX_TEXT_LEN) : null;
    }

    public static void writeMaidProfile(FriendlyByteBuf buf, MaidProfile profile) {
        buf.writeBoolean(profile != null);
        if (profile != null) {
            // 总是带上照片：超过单包上限的情形已由 sendSaveMaidProfile 的分片上传兜底，
            // 这里若再按版本裁剪，分片路径会连照片一起丢掉（实测「无法上传头像」即由此而来）。
            buf.writeNbt(profile.writeToNbt());
        }
    }

    public static MaidProfile readMaidProfile(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        return MaidProfile.readFromNbt(buf.readNbt());
    }

    public static void writeMaidProfileView(FriendlyByteBuf buf, MaidProfileView v) {
        buf.writeInt(v.entityId());
        writeNullableUtf(buf, v.maidUuid());
        writeNullableUtf(buf, v.ownerName());
        buf.writeUtf(v.modelId() == null ? "" : v.modelId(), MaidFilePackets.MAX_TEXT_LEN);
        buf.writeUtf(v.displayName() == null ? "" : v.displayName(), MaidFilePackets.MAX_TEXT_LEN);
        writeNullableUtf(buf, v.customName());
        buf.writeFloat(v.health());
        buf.writeFloat(v.maxHealth());
        buf.writeFloat(v.attackDamage());
        buf.writeVarInt(v.favorability());
        buf.writeBoolean(v.struckByLightning());
        // 读端一直在这里读 ysmModel，但写端此前漏写，导致 MaidProfileView 收发错位。
        // 母工程已修（1.7.2 YSM 换模显示），本次同步补齐其余五端。
        buf.writeBoolean(v.ysmModel());
        writeMaidProfile(buf, v.profile());
        List<CompoundTag> baubles = v.baubleItems() == null ? List.of() : v.baubleItems();
        int count = Math.min(baubles.size(), MAX_BAUBLE_ICONS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeNbt(baubles.get(i));
        }
    }

    public static MaidProfileView readMaidProfileView(FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        String maidUuid = readNullableUtf(buf);
        String ownerName = readNullableUtf(buf);
        String modelId = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        String displayName = buf.readUtf(MaidFilePackets.MAX_TEXT_LEN);
        String customName = readNullableUtf(buf);
        float health = buf.readFloat();
        float maxHealth = buf.readFloat();
        float attackDamage = buf.readFloat();
        int favorability = buf.readVarInt();
        boolean struckByLightning = buf.readBoolean();
        boolean ysmModel = buf.readBoolean();
        MaidProfile profile = readMaidProfile(buf);
        int count = MaidFilePackets.checkSize(buf.readVarInt(), MAX_BAUBLE_ICONS, "baubleIcons");
        List<CompoundTag> baubles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CompoundTag t = buf.readNbt();
            if (t != null) {
                baubles.add(t);
            }
        }
        return new MaidProfileView(entityId, maidUuid, ownerName, modelId, displayName, customName,
                health, maxHealth, attackDamage, favorability, struckByLightning, profile, baubles, ysmModel);
    }
}
