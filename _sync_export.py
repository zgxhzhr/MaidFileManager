# -*- coding: utf-8 -*-
"""把导出 handler 下沉同步到五端。"""
import io
import os
import re

BASE = r"E:\Mod\MaidFileManager"
SRC = os.path.join(BASE, "maid_file_manager-forge-1.20.1")
CREL = "common/src/main/java/io/github/zgxhzhr/maidfm"

# port -> (module, impl 类名, 编码类, 编码类全名, chunk 方法, 是否 26.1.2)
PORTS = {
    "fabric-1.20.1":   ("fabric",   "FabricServerMessaging",   "MaidFileModFabric", "io.github.zgxhzhr.maidfm.MaidFileModFabric", "MaidFileModFabric.sendChunkedDownload", False),
    "fabric-1.21.1":   ("fabric",   "FabricServerMessaging",   "MaidFileModFabric", "io.github.zgxhzhr.maidfm.MaidFileModFabric", "MaidFileModFabric.sendChunkedDownload", False),
    "neoforge-1.21.1": ("neoforge", "NeoForgeServerMessaging", "MaidFilePayloads",  "io.github.zgxhzhr.maidfm.network.MaidFilePayloads", "MaidFilePayloads.sendChunks", False),
    "neoforge-26.1.2": ("neoforge", "NeoForgeServerMessaging", "MaidFilePayloads",  "io.github.zgxhzhr.maidfm.network.MaidFilePayloads", "MaidFilePayloads.sendChunks", True),
    "fabric-26.1.2":   ("fabric",   "FabricServerMessaging",   "MaidFilePayloads",  "io.github.zgxhzhr.maidfm.network.MaidFilePayloads", "MaidFilePayloads.sendChunks", True),
}


def eol_of(p):
    with open(p, "rb") as f:
        return "CRLF" if b"\r\n" in f.read(4000) else "LF"


def wt(path, text, eol):
    t = text.replace("\r\n", "\n")
    if eol == "CRLF":
        t = t.replace("\n", "\r\n")
    with open(path, "wb") as f:
        f.write(t.encode("utf-8"))


def find_method(lines, name):
    pat = re.compile(r"^\s+(?:public|private|protected)\s+(?:static\s+)?[\w<>,\[\]\.\? ]+\s+" + re.escape(name) + r"\s*\(")
    s = -1
    for i, l in enumerate(lines):
        if pat.match(l):
            s = i
            break
    if s < 0:
        return None
    d = 0
    started = False
    for k in range(s, len(lines)):
        for ch in lines[k]:
            if ch == "{":
                d += 1
                started = True
            elif ch == "}":
                d -= 1
        if started and d == 0:
            return (s, k)
    return None


for port, (mod, impl, encoder, enc_ref, chunk_call, is2612) in PORTS.items():
    print("=== %s ===" % port)
    root = os.path.join(BASE, "maid_file_manager-" + port)

    # 1) 复制 3 个 common
    for rel in ["network/ServerPacketHandlers.java", "platform/PacketSender.java",
                "platform/services/ServerMessaging.java"]:
        s = os.path.join(SRC, CREL.replace("/", os.sep), rel.replace("/", os.sep))
        d = os.path.join(root, CREL.replace("/", os.sep), rel.replace("/", os.sep))
        wt(d, io.open(s, encoding="utf-8").read(), eol_of(d))
    print("  复制 3 个 common 文件")

    # 2) 实现类加 2 个方法
    ip = os.path.join(root, mod, "src/main/java/io/github/zgxhzhr/maidfm/platform", impl + ".java")
    with io.open(ip, encoding="utf-8") as f:
        ic = f.read()
    if "sendExportBatchResult" not in ic:
        if is2612:
            body = '''
    @Override
    public void sendExportBatchResult(ServerPlayer player, byte[] encodedPayload) {
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new io.github.zgxhzhr.maidfm.network.MaidFilePayloads.ExportBatchResultPayload(encodedPayload));
    }

    @Override
    public boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
        return io.github.zgxhzhr.maidfm.network.MaidFilePayloads.sendChunks(player, kind, payload);
    }
''' if mod == "fabric" else '''
    @Override
    public void sendExportBatchResult(ServerPlayer player, byte[] encodedPayload) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new io.github.zgxhzhr.maidfm.network.MaidFilePayloads.ExportBatchResultPayload(encodedPayload));
    }

    @Override
    public boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
        return io.github.zgxhzhr.maidfm.network.MaidFilePayloads.sendChunks(player, kind, payload);
    }
'''
        else:
            if mod == "fabric":
                send_line = ("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,\n"
                             "                io.github.zgxhzhr.maidfm.network.MaidPayload.of(\n"
                             "                        io.github.zgxhzhr.maidfm.network.MaidFilePackets.ID_EXPORT_BATCH_RESULT, out));"
                             ) if port == "fabric-1.21.1" else (
                             "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,\n"
                             "                io.github.zgxhzhr.maidfm.network.MaidFilePackets.ID_EXPORT_BATCH_RESULT, out);")
                buf_line = ("net.minecraft.network.FriendlyByteBuf out = io.github.zgxhzhr.maidfm.network.MaidPayload.buffer();"
                            if port == "fabric-1.21.1" else
                            "net.minecraft.network.FriendlyByteBuf out = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();")
            else:
                buf_line = None
                send_line = ("net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,\n"
                             "                new io.github.zgxhzhr.maidfm.network.MaidFilePayloads.ExportBatchResultPayload(encodedPayload));")
            if buf_line:
                body = '''
    @Override
    public void sendExportBatchResult(ServerPlayer player, byte[] encodedPayload) {
        %s
        out.writeBytes(encodedPayload);
        %s
    }

    @Override
    public boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
        return %s(player, kind, payload);
    }
''' % (buf_line, send_line, chunk_call)
            else:
                body = '''
    @Override
    public void sendExportBatchResult(ServerPlayer player, byte[] encodedPayload) {
        %s
    }

    @Override
    public boolean sendChunkedDownload(ServerPlayer player, String kind, byte[] payload) {
        return %s(player, kind, payload);
    }
''' % (send_line, chunk_call)
        idx = ic.rstrip().rfind("}")
        ic = ic[:idx] + body + ic[idx:]
        wt(ip, ic, eol_of(ip))
        print("  %s 加 2 个方法" % impl)

    # 3) 编码类：sendChunkedDownload 改 public（fabric）
    if mod == "fabric":
        ep = os.path.join(root, mod, "src/main/java/io/github/zgxhzhr/maidfm", encoder + ".java")
        with io.open(ep, encoding="utf-8") as f:
            ec = f.read()
        ec2 = re.sub(r"(?m)^(\s*)private static boolean sendChunkedDownload\(", r"\1public static boolean sendChunkedDownload(", ec)
        if ec2 != ec:
            wt(ep, ec2, eol_of(ep))
            print("  %s.sendChunkedDownload 改 public" % encoder)

    # 4) handler 改转发 + 删除 sendExportResults
    ep = os.path.join(root, mod, "src/main/java/io/github/zgxhzhr/maidfm",
                      "network" if mod == "neoforge" else "", encoder + ".java")
    if mod == "fabric":
        ep = os.path.join(root, mod, "src/main/java/io/github/zgxhzhr/maidfm", encoder + ".java")
    with io.open(ep, encoding="utf-8") as f:
        lines = f.read().split("\n")

    # ---- 4a) 两个导出 handler 改转发 ----
    if mod == "fabric":
        # registerC2SReceiver(ID_EXPORT_MAID / ID_EXPORT_BATCH) 整块替换
        for cname, kindname in [("ID_EXPORT_BATCH", "batch"), ("ID_EXPORT_MAID", "maid")]:
            start = next((i for i, l in enumerate(lines)
                          if cname in l and "registerC2SReceiver" in l), -1)
            if start < 0:
                print("  MISS %s" % cname)
                continue
            d = 0
            started = False
            end = start
            for k in range(start, len(lines)):
                for ch in lines[k]:
                    if ch == "{":
                        d += 1
                        started = True
                    elif ch == "}":
                        d -= 1
                if started and d == 0:
                    end = k
                    break
            ind = lines[start][: len(lines[start]) - len(lines[start].lstrip())]
            if kindname == "maid":
                new_block = [
                    ind + "registerC2SReceiver(MaidFilePackets.ID_EXPORT_MAID, (server, player, buf) -> {",
                    ind + "    io.github.zgxhzhr.maidfm.network.ServerPacketHandlers.exportMaid(player, buf.readInt());",
                    ind + "});",
                ]
            else:
                new_block = [
                    ind + "registerC2SReceiver(MaidFilePackets.ID_EXPORT_BATCH, (server, player, buf) -> {",
                    ind + "    java.util.List<Integer> ids = MaidFilePackets.readIntList(buf);",
                    ind + "    boolean removeAfter = buf.readBoolean();",
                    ind + "    io.github.zgxhzhr.maidfm.network.ServerPacketHandlers.exportBatch(player, ids, removeAfter);",
                    ind + "});",
                ]
            lines[start:end + 1] = new_block
            print("  %s 改转发" % cname)

    # 删 sendExportResults（整块）
    r = find_method(lines, "sendExportResults")
    if r:
        s, e = r
        ds = s
        while ds > 0:
            pv = lines[ds - 1].strip()
            if pv.startswith("//") or pv.startswith("*") or pv.startswith("/**") or pv.startswith("/*"):
                ds -= 1
                if lines[ds].strip().startswith("/*"):
                    break
            else:
                break
        del lines[ds:e + 1]
        print("  删除 sendExportResults（L%d-%d）" % (ds + 1, e + 1))

    wt(ep, "\n".join(lines), eol_of(ep))
