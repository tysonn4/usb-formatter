package com.noam.usbformatter

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Formateur ext4 en Kotlin pur : blocs de 4 Ko, journal, extents, groupes « uninit » (formatage rapide). */
object Ext4 {
    private const val BS = 4096
    private const val BPG = 32768
    private const val INODE_SIZE = 256

    private fun crc16(init: Int, d: ByteArray, off: Int, len: Int): Int {
        var crc = init and 0xFFFF
        for (i in off until off + len) {
            crc = crc xor (d[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }

    private fun hasBackup(g: Int): Boolean {
        if (g == 0 || g == 1) return true
        for (base in intArrayOf(3, 5, 7)) {
            var n = base.toLong()
            while (n < g) n *= base
            if (n == g.toLong()) return true
        }
        return false
    }

    private fun uuidBytes(u: UUID): ByteArray {
        val b = ByteBuffer.allocate(16)
        b.putLong(u.mostSignificantBits); b.putLong(u.leastSignificantBits)
        return b.array()
    }

    private fun setBits(bm: ByteArray, from: Int, to: Int) {
        for (i in from until to) bm[i / 8] = (bm[i / 8].toInt() or (1 shl (i % 8))).toByte()
    }

    private fun inode(mode: Int, links: Int, size: Long, blocks512: Long, extStart: Long, extLen: Int, now: Int): ByteArray {
        val a = ByteArray(INODE_SIZE)
        val b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0, mode.toShort())
        b.putInt(4, size.toInt())
        b.putInt(8, now); b.putInt(12, now); b.putInt(16, now)
        b.putShort(26, links.toShort())
        b.putInt(28, blocks512.toInt())
        b.putInt(32, 0x80000) // EXTENTS_FL
        // arbre d'extents dans i_block
        b.putShort(40, 0xF30A.toShort()); b.putShort(42, 1); b.putShort(44, 4); b.putShort(46, 0)
        b.putInt(52, 0); b.putShort(56, extLen.toShort()); b.putShort(58, (extStart ushr 32).toShort()); b.putInt(60, extStart.toInt())
        b.putInt(108, (size ushr 32).toInt())
        b.putShort(128, 32) // i_extra_isize
        b.putInt(144, now) // crtime
        return a
    }

    private class Gd(var bb: Long, var ib: Long, var it: Long, var free: Int, var freeInodes: Int, var dirs: Int, var flags: Int, var unused: Int)

    fun format(dev: BlockDev, label: String, gpt: Boolean, p: Progress) {
        Formatters.check(dev, gpt)
        val dbs = dev.blockSize
        val start = Formatters.startLba(dev)
        val vol = if (gpt) Formatters.lastUsable(dev) - start + 1 else dev.blockCount - start
        var total = vol * dbs / BS
        if (total > 0xFFFFFFFFL) throw IllegalStateException("Volume trop grand pour ext4 sans 64bit")

        val bytesPerInode = if (total * BS <= 4294967296L) 16384 else 65536
        val ipg = BPG * BS / bytesPerInode
        val itb = ipg * INODE_SIZE / BS
        fun gdtBlocks(g: Int) = (g * 32 + BS - 1) / BS
        var groups: Int
        while (true) {
            groups = ((total + BPG - 1) / BPG).toInt()
            val last = total - (groups - 1).toLong() * BPG
            val ov = (if (hasBackup(groups - 1)) 1 + gdtBlocks(groups) else 0) + 2 + itb
            if (groups > 1 && last < ov + 50) total -= last else break
        }
        val gdt = gdtBlocks(groups)
        val journalBlocks = (total / 64).coerceIn(1024, 16384).toInt()
        val now = (System.currentTimeMillis() / 1000).toInt()
        val uuid = UUID.randomUUID()
        val uuidB = uuidBytes(uuid)

        p(1, "Nettoyage des anciennes tables…")
        Formatters.wipeGpt(dev)

        fun wblk(n: Long, data: ByteArray) = dev.write(start + n * (BS / dbs), data)

        // --- disposition ---
        val gds = ArrayList<Gd>()
        val blocksInGroup = IntArray(groups) { g -> minOf(BPG.toLong(), total - g.toLong() * BPG).toInt() }
        val metaCount = IntArray(groups)
        for (g in 0 until groups) {
            val gs = g.toLong() * BPG
            val metaStart = gs + (if (hasBackup(g)) 1 + gdt else 0)
            val dataStart = metaStart + 2 + itb
            metaCount[g] = (dataStart - gs).toInt()
            gds.add(Gd(metaStart, metaStart + 1, metaStart + 2, 0, ipg, 0, 0, ipg))
        }
        val data0 = gds[0].it + itb
        val rootBlk = data0
        val lfBlk = data0 + 1
        val jBlk = data0 + 5
        val used0 = (data0 + 5 + journalBlocks).toInt()

        for (g in 0 until groups) {
            val d = gds[g]
            val used = if (g == 0) used0 else metaCount[g]
            d.free = blocksInGroup[g] - used
            if (g == 0) {
                d.freeInodes = ipg - 11; d.dirs = 2; d.unused = ipg - 11; d.flags = 0
            } else if (g == groups - 1) {
                d.flags = 1 // INODE_UNINIT
            } else {
                d.flags = 3 // INODE_UNINIT | BLOCK_UNINIT
            }
        }
        val totalFree = gds.sumOf { it.free.toLong() }
        val inodesTotal = ipg.toLong() * groups

        // --- inodes ---
        val jsize = journalBlocks.toLong() * BS
        val ijournal = inode(0x8180, 1, jsize, journalBlocks.toLong() * (BS / 512), jBlk, journalBlocks, now)
        val iroot = inode(0x41FF, 3, BS.toLong(), (BS / 512).toLong(), rootBlk, 1, now)
        val ilf = inode(0x41C0, 2, 4L * BS, 4L * (BS / 512), lfBlk, 4, now)

        // --- superblock ---
        fun superblock(groupNr: Int): ByteArray {
            val a = ByteArray(1024)
            val b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(0, inodesTotal.toInt()); b.putInt(4, total.toInt())
            b.putInt(12, totalFree.toInt()); b.putInt(16, (inodesTotal - 11).toInt())
            b.putInt(20, 0); b.putInt(24, 2); b.putInt(28, 2)
            b.putInt(32, BPG); b.putInt(36, BPG); b.putInt(40, ipg)
            b.putInt(48, now)
            b.putShort(54, 0xFFFF.toShort())
            b.putShort(56, 0xEF53.toShort()); b.putShort(58, 1); b.putShort(60, 1)
            b.putInt(64, now); b.putInt(76, 1)
            b.putInt(84, 11); b.putShort(88, INODE_SIZE.toShort()); b.putShort(90, groupNr.toShort())
            b.putInt(92, 0x4 or 0x8 or 0x20)            // has_journal, ext_attr, dir_index
            b.putInt(96, 0x2 or 0x40)                   // filetype, extents
            b.putInt(100, 0x1 or 0x2 or 0x8 or 0x10 or 0x20 or 0x40) // sparse_super, large_file, huge_file, gdt_csum, dir_nlink, extra_isize
            uuidB.copyInto(a, 104)
            val lab = label.take(16).toByteArray(Charsets.ISO_8859_1)
            lab.copyInto(a, 120)
            b.putInt(224, 8)                             // journal inode
            uuidBytes(UUID.randomUUID()).copyInto(a, 236) // hash seed
            a[252] = 1; a[253] = 1
            b.putInt(256, 0x0C)                          // user_xattr, acl
            b.putInt(264, now)
            ijournal.copyInto(a, 268, 40, 100)           // s_jnl_blocks[0..14]
            b.putInt(328, (jsize ushr 32).toInt()); b.putInt(332, jsize.toInt())
            b.putShort(348, 32); b.putShort(350, 32)
            b.putInt(352, 1)
            return a
        }

        // --- descripteurs de groupes ---
        val gdtBuf = ByteArray(gdt * BS)
        for (g in 0 until groups) {
            val d = gds[g]
            val o = g * 32
            val b = ByteBuffer.wrap(gdtBuf).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(o, d.bb.toInt()); b.putInt(o + 4, d.ib.toInt()); b.putInt(o + 8, d.it.toInt())
            b.putShort(o + 12, d.free.toShort()); b.putShort(o + 14, d.freeInodes.toShort())
            b.putShort(o + 16, d.dirs.toShort()); b.putShort(o + 18, d.flags.toShort())
            b.putShort(o + 28, d.unused.toShort())
            var crc = crc16(0xFFFF, uuidB, 0, 16)
            val gnum = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(g).array()
            crc = crc16(crc, gnum, 0, 4)
            crc = crc16(crc, gdtBuf, o, 30)
            b.putShort(o + 30, crc.toShort())
        }

        p(5, "Superblocs et descripteurs…")
        for (g in 0 until groups) {
            if (!hasBackup(g)) continue
            val gs = g.toLong() * BPG
            val sbBlock = ByteArray(BS)
            if (g == 0) superblock(0).copyInto(sbBlock, 1024) else superblock(g).copyInto(sbBlock, 0)
            wblk(gs, sbBlock)
            wblk(gs + 1, gdtBuf)
        }

        // --- bitmaps du groupe 0 et du dernier groupe ---
        fun blockBitmap(g: Int, used: Int): ByteArray {
            val bm = ByteArray(BS)
            setBits(bm, 0, used)
            setBits(bm, blocksInGroup[g], BS * 8)
            return bm
        }
        wblk(gds[0].bb, blockBitmap(0, used0))
        val ib0 = ByteArray(BS)
        setBits(ib0, 0, 11); setBits(ib0, ipg, BS * 8)
        wblk(gds[0].ib, ib0)
        if (groups > 1) wblk(gds[groups - 1].bb, blockBitmap(groups - 1, metaCount[groups - 1]))

        // --- table d'inodes du groupe 0 ---
        p(10, "Table d'inodes…")
        val it0 = ByteArray(itb * BS)
        iroot.copyInto(it0, 1 * INODE_SIZE)
        ijournal.copyInto(it0, 7 * INODE_SIZE)
        ilf.copyInto(it0, 10 * INODE_SIZE)
        wblk(gds[0].it, it0)

        // --- répertoire racine, lost+found ---
        fun dirent(b: ByteBuffer, off: Int, ino: Int, recLen: Int, name: String, type: Int) {
            b.putInt(off, ino); b.putShort(off + 4, recLen.toShort())
            b.put(off + 6, name.length.toByte()); b.put(off + 7, type.toByte())
            for (i in name.indices) b.put(off + 8 + i, name[i].code.toByte())
        }
        val rootDir = ByteArray(BS)
        val rb = ByteBuffer.wrap(rootDir).order(ByteOrder.LITTLE_ENDIAN)
        dirent(rb, 0, 2, 12, ".", 2); dirent(rb, 12, 2, 12, "..", 2); dirent(rb, 24, 11, BS - 24, "lost+found", 2)
        wblk(rootBlk, rootDir)
        val lf0 = ByteArray(BS)
        val lb = ByteBuffer.wrap(lf0).order(ByteOrder.LITTLE_ENDIAN)
        dirent(lb, 0, 11, 12, ".", 2); dirent(lb, 12, 2, BS - 12, "..", 2)
        wblk(lfBlk, lf0)
        for (i in 1..3) {
            val e = ByteArray(BS)
            ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN).putShort(4, BS.toShort().let { if (BS == 65536) 0 else it })
            wblk(lfBlk + i, e)
        }

        // --- journal (superbloc jbd2, big-endian) ---
        val j = ByteArray(BS)
        val jb = ByteBuffer.wrap(j)
        jb.putInt(0, 0xC03B3998.toInt()); jb.putInt(4, 4); jb.putInt(8, 0)
        jb.putInt(12, BS); jb.putInt(16, journalBlocks); jb.putInt(20, 1); jb.putInt(24, 1)
        uuidB.copyInto(j, 48)
        jb.putInt(64, 1)
        uuidB.copyInto(j, 256)
        wblk(jBlk, j)

        p(98, "Table de partition…")
        Formatters.writeTable(dev, gpt, start, vol, 0x83, label, Formatters.LINUX_FS)
        p(100, "Terminé : ext4")
    }
}
